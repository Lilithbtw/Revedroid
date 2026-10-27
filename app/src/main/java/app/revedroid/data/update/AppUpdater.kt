package app.revedroid.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.IntentCompat
import app.revedroid.BuildConfig
import app.revedroid.container
import app.revedroid.data.api.CloudreveJson
import app.revedroid.data.repo.UploadProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String = "",
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String,
)

data class AppUpdate(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
    val pageUrl: String,
)

object Versions {
    /** Compares dotted numeric versions, ignoring suffixes such as `-debug`. */
    fun isNewer(
        candidate: String,
        current: String,
    ): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }

    private fun parts(version: String): List<Int> =
        version
            .trim()
            .removePrefix("v")
            .substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
}

/**
 * Finds newer releases on GitHub and installs them over the running app with
 * [PackageInstaller]. Release builds share one signing key, so Android treats
 * the new APK as an update and keeps the app's data, sign-ins included.
 */
class AppUpdater(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private val _installError = MutableStateFlow<String?>(null)

    /** Set when the system installer reports a failure after the download finished. */
    val installError: StateFlow<String?> = _installError.asStateFlow()

    /** Only release builds can update themselves: debug builds are a different app id and key. */
    val supported: Boolean get() = !BuildConfig.DEBUG

    suspend fun check(): AppUpdate? =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(BuildConfig.UPDATE_FEED)
                    .header("Accept", "application/vnd.github+json")
                    .build()
            val release =
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Couldn't check for updates (HTTP ${response.code})")
                    CloudreveJson.decodeFromString<GithubRelease>(response.body.string())
                }
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
            val version = release.tagName.removePrefix("v")
            if (release.draft || release.prerelease || apk == null || !Versions.isNewer(version, BuildConfig.VERSION_NAME)) {
                null
            } else {
                AppUpdate(version, release.body.orEmpty().trim(), apk.downloadUrl, apk.size, release.htmlUrl)
            }
        }

    /** Downloads the APK into an install session; Android then asks the user to confirm. */
    suspend fun install(
        update: AppUpdate,
        onProgress: (UploadProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        _installError.value = null
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (update.size > 0) setSize(update.size)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                client.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code})")
                    val total = response.body.contentLength().takeIf { it > 0 } ?: update.size
                    session.openWrite("revedroid.apk", 0, total).use { out ->
                        response.body.byteStream().use { input ->
                            val buffer = ByteArray(SEGMENT)
                            var sent = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                sent += n
                                onProgress(UploadProgress(sent, total))
                            }
                        }
                        session.fsync(out)
                    }
                }
                val status =
                    PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        Intent(context, InstallStatusReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                session.commit(status.intentSender)
            }
        } catch (e: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    internal fun onInstallFailed(message: String) {
        _installError.value = message
    }

    private companion object {
        const val SEGMENT = 64 * 1024
    }
}

/** Receives the install session's status: shows the system confirmation, or records a failure. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            // Success restarts the app; aborted means the user said no.
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Unit
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed ($status)"
                Log.w("AppUpdater", message)
                context.container.updater.onInstallFailed(message)
            }
        }
    }
}
