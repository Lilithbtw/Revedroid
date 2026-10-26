package app.revedroid.data.repo

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import app.revedroid.data.net.WebViewCookieJar

/**
 * Hands signed download URLs to the system DownloadManager. The Cloudflare
 * cookie and User-Agent are forwarded so downloads also pass the challenge.
 */
class Downloader(
    context: Context,
    private val cookieJar: WebViewCookieJar,
    private val userAgent: String,
) {
    private val manager = context.getSystemService(DownloadManager::class.java)

    fun enqueue(
        url: String,
        fileName: String,
    ): Long {
        val safeName = safeFileName(fileName)
        val request =
            DownloadManager
                .Request(Uri.parse(url))
                .setTitle(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Revedroid/$safeName")
                .addRequestHeader("User-Agent", userAgent)
        cookieJar.cookieHeader(url)?.let { request.addRequestHeader("Cookie", it) }
        return manager.enqueue(request)
    }
}

/** Replaces characters that file systems reject in names. */
fun safeFileName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
