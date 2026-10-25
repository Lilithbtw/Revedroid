package app.revedroid

import android.app.Application
import android.content.Context
import android.webkit.WebSettings
import app.revedroid.data.api.ApiCode
import app.revedroid.data.api.ApiException
import app.revedroid.data.api.CloudreveApi
import app.revedroid.data.api.CloudreveJson
import app.revedroid.data.net.AuthInterceptor
import app.revedroid.data.net.ChallengeMonitor
import app.revedroid.data.net.CloudflareInterceptor
import app.revedroid.data.net.ServerUrls
import app.revedroid.data.net.SessionManager
import app.revedroid.data.net.UserAgentInterceptor
import app.revedroid.data.net.WebViewCookieJar
import app.revedroid.data.repo.AuthRepository
import app.revedroid.data.repo.Downloader
import app.revedroid.data.repo.FileRepository
import app.revedroid.data.repo.ServerRepository
import app.revedroid.data.store.AppStore
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class RevedroidApp :
    Application(),
    SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /** Coil shares the cookie jar and User-Agent so thumbnails get through Cloudflare too. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader
            .Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.coreClient })) }
            .crossfade(true)
            .build()
}

val Context.container: AppContainer get() = (applicationContext as RevedroidApp).container

/** Manual dependency graph; small enough that a DI framework isn't worth it. */
class AppContainer(
    context: Context,
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = AppStore(context)
    val cookieJar = WebViewCookieJar()
    val challengeMonitor = ChallengeMonitor()
    val sessions = SessionManager(store, appScope)

    /** Must equal the WebView's UA, since Cloudflare binds clearance cookies to it. */
    val userAgent: String =
        runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrElse { System.getProperty("http.agent") ?: "Revedroid" }

    @Volatile
    var serverUrl: String? = null
        private set

    /** Cookies + UA only. Used for ping, token refresh, images, and non-Cloudreve hosts. */
    val coreClient: OkHttpClient =
        OkHttpClient
            .Builder()
            .cookieJar(cookieJar)
            .addInterceptor(UserAgentInterceptor(userAgent))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    /** Cloudreve API client: adds challenge detection and bearer auth. */
    val apiClient: OkHttpClient =
        coreClient
            .newBuilder()
            .addInterceptor(CloudflareInterceptor(challengeMonitor))
            .addInterceptor(AuthInterceptor(sessions) { serverUrl })
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
                }
            }.build()

    val uploadClient: OkHttpClient =
        apiClient
            .newBuilder()
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()

    private var api: CloudreveApi? = null

    val serverRepository = ServerRepository(coreClient)
    val authRepository = AuthRepository(this)
    val fileRepository = FileRepository(this, context.contentResolver)
    val downloader = Downloader(context, cookieJar, userAgent)

    init {
        sessions.refreshClient = coreClient
        sessions.load()
    }

    @Synchronized
    fun setServer(url: String) {
        if (url == serverUrl && api != null) return
        serverUrl = url
        api =
            Retrofit
                .Builder()
                .baseUrl(ServerUrls.apiBase(url))
                .client(uploadClient)
                .addConverterFactory(CloudreveJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(CloudreveApi::class.java)
    }

    fun requireServer(): String = serverUrl ?: error("No server configured")

    @Synchronized
    fun api(): CloudreveApi = api ?: error("No server configured")

    /** Runs an authenticated call, signing out if the server says the session is gone. */
    suspend fun <T> authed(block: suspend CloudreveApi.() -> T): T =
        try {
            api().block()
        } catch (e: ApiException) {
            if (e.code == ApiCode.LOGIN_REQUIRED) sessions.onUnauthorized()
            throw e
        }
}
