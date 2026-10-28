package app.revedroid.data.upload

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.revedroid.MainActivity
import app.revedroid.R
import app.revedroid.container
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while [UploadManager] has work, and shows
 * its progress. The upload sources ride along as the start intent's ClipData
 * with a read grant: the grant then belongs to this service, so files shared
 * from other apps stay readable after the activity that received them closes.
 */
class UploadService : Service() {
    private val scope = MainScope()
    private var watcher: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        lastStartId = startId
        val uploads = container.uploads
        if (intent?.action == ACTION_CANCEL) {
            uploads.cancelAll()
        } else {
            // Every startForegroundService() must be answered, even if the work is already done.
            try {
                ServiceCompat.startForeground(
                    this,
                    UploadNotifications.PROGRESS_ID,
                    UploadNotifications.progress(this, uploads.state.value),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } catch (e: Exception) {
                // Not allowed from the background; uploads still run while the process lives.
                Log.w(TAG, "Couldn't enter the foreground", e)
            }
        }
        if (watcher != null) {
            if (!uploads.state.value.busy) stopSelfResult(startId)
        } else {
            watcher =
                scope.launch {
                    uploads.state.collect { state ->
                        if (!state.busy) {
                            // Only stops if no newer start arrived in the meantime.
                            stopSelfResult(lastStartId)
                        } else {
                            UploadNotifications.notify(this@UploadService, UploadNotifications.PROGRESS_ID) {
                                UploadNotifications.progress(this@UploadService, state)
                            }
                            delay(REFRESH_MILLIS)
                        }
                    }
                }
        }
        // Keeps each start, and the URI grants it carries, until stopSelfResult releases it.
        return START_REDELIVER_INTENT
    }

    /** Android 15 caps data sync services at six hours a day. */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "UploadService"
        private const val ACTION_CANCEL = "app.revedroid.action.CANCEL_UPLOADS"
        private const val REFRESH_MILLIS = 500L

        fun start(
            context: Context,
            sources: List<Uri>,
        ) {
            val intent = Intent(context, UploadService::class.java)
            val content = sources.filter { it.scheme == ContentResolver.SCHEME_CONTENT }
            if (content.isNotEmpty()) {
                intent.clipData = ClipData.newRawUri(null, content.first()).apply { content.drop(1).forEach { addItem(ClipData.Item(it)) } }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start the upload service", e)
            }
        }

        fun cancelIntent(context: Context): PendingIntent =
            PendingIntent.getService(
                context,
                0,
                Intent(context, UploadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

object UploadNotifications {
    const val CHANNEL = "uploads"
    const val PROGRESS_ID = 1
    private const val SUMMARY_ID = 2

    fun createChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.notification_channel_uploads))
                .build(),
        )
    }

    fun progress(
        context: Context,
        state: UploadsState,
    ): Notification {
        val current = state.current ?: state.active.firstOrNull()
        val total = state.done + state.failed + state.active.size
        val title =
            if (current == null) {
                context.getString(R.string.upload_notification_preparing)
            } else {
                context.getString(R.string.upload_notification_title, state.done + state.failed + 1, total)
            }
        val builder =
            base(context)
                .setContentTitle(title)
                .setContentText(current?.name)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(0, context.getString(R.string.action_cancel), UploadService.cancelIntent(context))
        if (current == null || current.total <= 0) {
            builder.setProgress(0, 0, true)
        } else {
            builder
                .setProgress(PROGRESS_MAX, (current.fraction * PROGRESS_MAX).toInt(), false)
                .setSubText(
                    context.getString(
                        R.string.upload_notification_bytes,
                        Formatter.formatShortFileSize(context, current.sent),
                        Formatter.formatShortFileSize(context, current.total),
                    ),
                )
        }
        return builder.build()
    }

    /** Tells the user how a run of uploads ended; nothing when every upload was cancelled. */
    fun showSummary(
        context: Context,
        done: Int,
        failed: Int,
    ) {
        if (done == 0 && failed == 0) return
        val res = context.resources
        val title =
            if (failed == 0) {
                res.getQuantityString(R.plurals.upload_done, done, done)
            } else {
                res.getQuantityString(R.plurals.upload_failed, failed, failed)
            }
        val text = if (failed > 0 && done > 0) res.getQuantityString(R.plurals.upload_done, done, done) else null
        notify(context, SUMMARY_ID) {
            base(context)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        }
    }

    /** A new run of uploads replaces the report on the last one. */
    fun clearSummary(context: Context) = NotificationManagerCompat.from(context).cancel(SUMMARY_ID)

    fun notify(
        context: Context,
        id: Int,
        build: () -> Notification,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, build())
    }

    private fun base(context: Context): NotificationCompat.Builder {
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_upload)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentIntent(open)
    }

    private const val PROGRESS_MAX = 100
}
