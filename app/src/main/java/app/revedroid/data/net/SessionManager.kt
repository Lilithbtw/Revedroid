package app.revedroid.data.net

import app.revedroid.data.api.ApiEnvelope
import app.revedroid.data.api.ApiException
import app.revedroid.data.api.CloudreveJson
import app.revedroid.data.api.LoginResponse
import app.revedroid.data.api.NO_AUTH_HEADER
import app.revedroid.data.api.RefreshTokenRequest
import app.revedroid.data.api.Token
import app.revedroid.data.api.User
import app.revedroid.data.api.decode
import app.revedroid.data.store.AppStore
import app.revedroid.data.store.StoredAccounts
import app.revedroid.data.store.StoredSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * Holds the signed-in accounts and refreshes the active account's access token
 * shortly before it expires. Refresh runs synchronously on OkHttp's thread so
 * the interceptor can attach a valid token to the request it is processing.
 */
class SessionManager(
    private val store: AppStore,
    private val scope: CoroutineScope,
) {
    private val _accounts = MutableStateFlow<List<StoredSession>>(emptyList())

    /** Every signed-in account, in the order they were added. */
    val accounts: StateFlow<List<StoredSession>> = _accounts.asStateFlow()

    private val _session = MutableStateFlow<StoredSession?>(null)

    /** The account the app is using. */
    val session: StateFlow<StoredSession?> = _session.asStateFlow()

    // Conflated, so the store always ends up with the latest state, written in order.
    private val writes = Channel<StoredAccounts>(Channel.CONFLATED)

    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Fires when the server rejects the refresh token and the user must sign in again. */
    val expired: SharedFlow<Unit> = _expired.asSharedFlow()

    private val loaded = CompletableDeferred<Unit>()

    /** Client used for the refresh call: cookies + UA, but no auth interceptor. */
    lateinit var refreshClient: OkHttpClient

    fun load() {
        scope.launch {
            val saved = store.readAccounts()
            synchronized(this@SessionManager) {
                _accounts.value = saved.accounts
                _session.value = saved.accounts.firstOrNull { it.key == saved.active }
            }
            loaded.complete(Unit)
            for (accounts in writes) store.writeAccounts(accounts)
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    /** Adds the account (or replaces the same user's older session) and makes it active. */
    fun signIn(
        serverUrl: String,
        login: LoginResponse,
    ) {
        val stored = StoredSession(serverUrl, login.user, login.token)
        update { accounts -> (accounts.filterNot { it.key == stored.key } + stored) to stored }
    }

    /** Forgets the active account. Returns the account to continue with, if any are left. */
    fun signOut(): StoredSession? {
        val active = _session.value ?: return null
        update { accounts -> accounts.filterNot { it.key == active.key } to null }
        return _accounts.value.firstOrNull()
    }

    fun remove(key: String) = update { accounts -> accounts.filterNot { it.key == key } to _session.value?.takeIf { it.key != key } }

    fun switchTo(key: String): StoredSession? {
        val target = _accounts.value.firstOrNull { it.key == key } ?: return null
        update { accounts -> accounts to target }
        return target
    }

    /** Keeps the stored profile (name, email) in step with what the server reports. */
    fun updateUser(user: User) {
        val current = _session.value ?: return
        if (current.user.id != user.id || current.user == user) return
        val updated = current.copy(user = user)
        update { accounts -> accounts.map { if (it.key == updated.key) updated else it } to updated }
    }

    /** Keeps the accounts but uses none of them, so the next sign-in adds one. */
    fun deactivate() = update { accounts -> accounts to null }

    /**
     * Makes a signed-in account on [serverUrl] active, preferring the current one.
     * Returns false when there is none whose refresh token is still valid.
     */
    fun useAccountFor(serverUrl: String): Boolean {
        val now = Instant.now()
        val candidates = _accounts.value.filter { it.serverUrl == serverUrl && it.token.refreshExpiresAt.isAfter(now) }
        val pick = candidates.firstOrNull { it.key == _session.value?.key } ?: candidates.firstOrNull() ?: return false
        if (pick != _session.value) switchTo(pick.key)
        return true
    }

    @Synchronized
    private fun update(change: (List<StoredSession>) -> Pair<List<StoredSession>, StoredSession?>) {
        val (accounts, active) = change(_accounts.value)
        _accounts.value = accounts
        _session.value = active
        if (loaded.isCompleted) writes.trySend(StoredAccounts(accounts, active?.key))
    }

    /**
     * Returns a usable access token for [serverUrl], refreshing it first if it
     * expires within [REFRESH_MARGIN]. Network failures (including a Cloudflare
     * challenge) propagate as [IOException] and leave the session intact; only an
     * explicit server rejection signs the user out.
     */
    @Synchronized
    fun accessTokenBlocking(serverUrl: String): String? {
        val current = _session.value?.takeIf { it.serverUrl == serverUrl } ?: return null
        val now = Instant.now()
        if (current.token.accessExpiresAt.isAfter(now.plus(REFRESH_MARGIN))) {
            return current.token.accessToken
        }
        if (!current.token.refreshExpiresAt.isAfter(now)) {
            expire()
            return null
        }
        val fresh =
            try {
                refresh(serverUrl, current.token.refreshToken)
            } catch (e: ApiException) {
                expire()
                return null
            }
        val updated = current.copy(token = fresh)
        update { accounts -> accounts.map { if (it.key == updated.key) updated else it } to updated }
        return fresh.accessToken
    }

    /** Called when an authenticated request still comes back 401. */
    fun onUnauthorized() = expire()

    private fun expire() {
        if (_session.value == null) return
        signOut()
        _expired.tryEmit(Unit)
    }

    private fun refresh(
        serverUrl: String,
        refreshToken: String,
    ): Token {
        val body =
            CloudreveJson
                .encodeToString(RefreshTokenRequest.serializer(), RefreshTokenRequest(refreshToken))
                .toRequestBody(JSON)
        val request =
            Request
                .Builder()
                .url(ServerUrls.api(serverUrl, "session/token/refresh"))
                .post(body)
                .build()
        refreshClient.newCall(request).execute().use { response ->
            when (CloudflareDetector.classify(response)) {
                CloudflareVerdict.Challenge -> throw CloudflareChallengeException(request.url.toString())
                CloudflareVerdict.Blocked -> throw CloudflareBlockedException(request.url.toString())
                CloudflareVerdict.None -> Unit
            }
            if (!response.isSuccessful) throw IOException("Token refresh failed: HTTP ${response.code}")
            val envelope = CloudreveJson.decodeFromString<ApiEnvelope>(response.body.string())
            return envelope.decode()
        }
    }

    private companion object {
        val REFRESH_MARGIN: Duration = Duration.ofSeconds(60)
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Adds `Authorization: Bearer <token>` to requests aimed at the configured
 * Cloudreve server. Requests to other hosts (storage nodes, CDNs) and requests
 * tagged with [NO_AUTH_HEADER] go out untouched.
 */
class AuthInterceptor(
    private val sessions: SessionManager,
    private val serverUrl: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val anonymous = original.header(NO_AUTH_HEADER) != null
        val builder = original.newBuilder().removeHeader(NO_AUTH_HEADER)
        val server = serverUrl()
        if (anonymous || server == null || !ServerUrls.sameOrigin(server, original.url)) {
            return chain.proceed(builder.build())
        }
        val token = sessions.accessTokenBlocking(server)
        if (token != null) builder.header("Authorization", "Bearer $token")
        return chain.proceed(builder.build())
    }
}
