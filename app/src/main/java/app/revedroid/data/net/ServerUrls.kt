package app.revedroid.data.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServerUrls {
    const val API_PREFIX = "api/v4/"

    /**
     * Turns what the user typed into a canonical base URL without a trailing
     * slash: adds `https://` when no scheme is given and drops a pasted
     * `/api/v4` suffix. Sub-path installs (`https://host/cloud`) are kept.
     * Returns null when the input can't be a URL.
     */
    fun normalize(input: String): String? {
        var text = input.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) text = "https://$text"
        val url = text.toHttpUrlOrNull() ?: return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        if (segments.size >= 2 && segments[segments.size - 2] == "api" && segments.last() == "v4") {
            segments.removeAt(segments.size - 1)
            segments.removeAt(segments.size - 1)
        }
        val rebuilt =
            url
                .newBuilder()
                .query(null)
                .fragment(null)
                .encodedPath("/")
        segments.forEach { rebuilt.addPathSegment(it) }
        return rebuilt.build().toString().trimEnd('/')
    }

    fun apiBase(server: String): String = "$server/$API_PREFIX"

    fun api(
        server: String,
        path: String,
    ): String = apiBase(server) + path.trimStart('/')

    /** Resolves URLs the API returns, which may be relative to the server root. */
    fun resolve(
        server: String,
        url: String,
    ): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val base = server.toHttpUrlOrNull() ?: return url
        return base.resolve(url)?.toString() ?: url
    }

    fun sameOrigin(
        server: String,
        url: HttpUrl,
    ): Boolean {
        val base = server.toHttpUrlOrNull() ?: return false
        return base.scheme == url.scheme && base.host == url.host && base.port == url.port
    }

    fun displayHost(server: String): String =
        server.toHttpUrlOrNull()?.let {
            if (it.port == HttpUrl.defaultPort(it.scheme)) it.host else "${it.host}:${it.port}"
        } ?: server
}
