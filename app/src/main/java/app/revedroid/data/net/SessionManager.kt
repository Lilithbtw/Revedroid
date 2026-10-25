package app.revedroid.data.net

import app.revedroid.data.api.ApiEnvelope
import app.revedroid.data.api.ApiException
import app.revedroid.data.api.CloudreveJson
import app.revedroid.data.api.LoginResponse
import app.revedroid.data.api.NO_AUTH_HEADER
import app.revedroid.data.api.RefreshTokenRequest
import app.revedroid.data.api.Token
import app.revedroid.data.api.decode
import app.revedroid.data.store.AppStore
import app.revedroid.data.store.StoredSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
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
 * Holds the signed-in user's tokens and refreshes the access token shortly
 * before it expires. Refresh runs synchronously on OkHttp's thread so the
 * interceptor can attach a valid token to the request it is processing.
 */
class SessionManager(
    private val store: AppStore,
    private val scope: CoroutineScope,
) {
    private val _session = MutableStateFlow<StoredSession?>(null)
    val session: StateFlow<StoredSession?> = _session.asStateFlow()

    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Fires when the server rejects the refresh token and the user must sign in again. */
    val expired: SharedFlow<Unit> = _expired.asSharedFlow()

    private val loaded = CompletableDeferred<Unit>()

    /** Client used for the refresh call: cookies + UA, but no auth interceptor. */
    lateinit var refreshClient: OkHttpClient

    fun load() {
        scope.launch {
            _session.value = store.readSession()
            loaded.complete(Unit)
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    suspend fun signIn(
        serverUrl: String,
        login: LoginResponse,
    ) {
        val stored = StoredSession(serverUrl, login.user, login.token)
        _session.value = stored
        store.writeSession(stored)
    }

    suspend fun signOut() {
        _session.value = null
        store.writeSession(null)
    }

    fun hasSessionFor(serverUrl: String): Boolean {
        val s = _session.value ?: return false
        return s.serverUrl == serverUrl && s.token.refreshExpiresAt.isAfter(Instant.now())
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
        _session.value = updated
        scope.launch { store.writeSession(updated) }
        return fresh.accessToken
    }

    /** Called when an authenticated request still comes back 401. */
    fun onUnauthorized() = expire()

    private fun expire() {
        if (_session.value == null) return
        _session.value = null
        scope.launch { store.writeSession(null) }
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
