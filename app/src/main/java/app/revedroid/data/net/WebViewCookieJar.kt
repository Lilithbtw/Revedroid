package app.revedroid.data.net

import android.util.Log
import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Cookie jar backed by the WebView [CookieManager], so the `cf_clearance`
 * cookie that Cloudflare hands the challenge WebView is sent by OkHttp too, and
 * anything OkHttp receives is visible to later WebViews. CookieManager also
 * persists cookies across process restarts.
 *
 * If the device has no usable WebView provider the jar degrades to an
 * in-memory store; the app still works against servers without Cloudflare.
 */
class WebViewCookieJar : CookieJar {
    private val manager: CookieManager? =
        runCatching {
            CookieManager.getInstance().apply { setAcceptCookie(true) }
        }.onFailure { Log.w(TAG, "WebView CookieManager unavailable", it) }.getOrNull()

    private val memory = ConcurrentHashMap<String, MutableMap<String, Cookie>>()

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        val m = manager
        if (m == null) {
            val bucket = memory.getOrPut(url.host) { ConcurrentHashMap() }
            cookies.forEach { bucket[it.name] = it }
            return
        }
        cookies.forEach { m.setCookie(url.toString(), it.toString()) }
        m.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val m = manager
        if (m == null) {
            val now = System.currentTimeMillis()
            return memory[url.host]?.values?.filter { it.expiresAt > now && it.matches(url) }.orEmpty()
        }
        val header = m.getCookie(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }

    /** The raw `Cookie` header value for [url], used when handing URLs to DownloadManager. */
    fun cookieHeader(url: String): String? {
        val m = manager ?: return null
        return m.getCookie(url)
    }

    fun hasCloudflareClearance(url: String): Boolean = cookieHeader(url)?.split(';')?.any { it.trim().startsWith("cf_clearance=") } == true

    fun flush() {
        manager?.flush()
    }

    private companion object {
        const val TAG = "WebViewCookieJar"
    }
}
