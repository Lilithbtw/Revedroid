package app.revedroid.data.net

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

enum class CloudflareVerdict {
    /** Not a Cloudflare interstitial; handle the response normally. */
    None,

    /** A managed/JS/captcha challenge a human can pass in a WebView. */
    Challenge,

    /** A firewall block (e.g. error 1020) that no challenge will lift. */
    Blocked,
}

object CloudflareDetector {
    private val challengeStatuses = setOf(403, 429, 503)

    private val challengeMarkers =
        listOf(
            "_cf_chl_opt",
            "cf_chl_",
            "challenge-platform",
            "cf-challenge",
            "challenge-form",
            "Just a moment...",
        )

    /**
     * Instances without Cloudflare never produce anything but [CloudflareVerdict.None]
     * here: every other verdict requires Cloudflare's own response headers.
     */
    fun classify(response: Response): CloudflareVerdict {
        if (response.header("cf-mitigated").equals("challenge", ignoreCase = true)) {
            return CloudflareVerdict.Challenge
        }
        if (response.code !in challengeStatuses) return CloudflareVerdict.None
        val fromCloudflare =
            response.header("server").orEmpty().contains("cloudflare", ignoreCase = true) ||
                response.header("cf-ray") != null
        if (!fromCloudflare) return CloudflareVerdict.None
        if (!response.header("content-type").orEmpty().contains("text/html", ignoreCase = true)) {
            return CloudflareVerdict.None
        }
        val body = runCatching { response.peekBody(BODY_PEEK_BYTES).string() }.getOrDefault("")
        return when {
            challengeMarkers.any { body.contains(it, ignoreCase = true) } -> CloudflareVerdict.Challenge
            response.code == 403 -> CloudflareVerdict.Blocked
            else -> CloudflareVerdict.None
        }
    }

    private const val BODY_PEEK_BYTES = 128L * 1024
}

/** Raised for API calls that hit a Cloudflare interstitial instead of Cloudreve. */
class CloudflareChallengeException(
    val url: String,
) : IOException("Cloudflare verification is required for $url")

class CloudflareBlockedException(
    val url: String,
) : IOException("Cloudflare blocked the request to $url")

/** Broadcasts challenges seen mid-session so the UI can open the verification screen. */
class ChallengeMonitor {
    private val _events =
        MutableSharedFlow<String>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun report(url: String) {
        _events.tryEmit(url)
    }
}

/**
 * Turns Cloudflare interstitials into typed exceptions instead of letting the
 * JSON converter choke on an HTML page, and notifies [ChallengeMonitor].
 */
class CloudflareInterceptor(
    private val monitor: ChallengeMonitor,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val url = response.request.url.toString()
        return when (CloudflareDetector.classify(response)) {
            CloudflareVerdict.None -> {
                response
            }

            CloudflareVerdict.Challenge -> {
                response.close()
                monitor.report(challengePageFor(response.request.method, url))
                throw CloudflareChallengeException(url)
            }

            CloudflareVerdict.Blocked -> {
                response.close()
                throw CloudflareBlockedException(url)
            }
        }
    }
}

/**
 * The URL to solve the challenge on. A WebView can only GET, so for other
 * methods the same API's ping route stands in, which sits behind the same rules.
 */
internal fun challengePageFor(
    method: String,
    url: String,
): String {
    if (method == "GET") return url
    val prefix = url.indexOf(ServerUrls.API_PREFIX).takeIf { it >= 0 } ?: return url
    return url.substring(0, prefix + ServerUrls.API_PREFIX.length) + "site/ping"
}

/**
 * `cf_clearance` is bound to the User-Agent that solved the challenge, so every
 * OkHttp request must present exactly the WebView's UA.
 */
class UserAgentInterceptor(
    private val userAgent: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain
                .request()
                .newBuilder()
                .header("User-Agent", userAgent)
                .build(),
        )
}
