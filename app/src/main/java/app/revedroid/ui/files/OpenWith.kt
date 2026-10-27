package app.revedroid.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.revedroid.R
import app.revedroid.data.api.CloudFile

/** File types the app can show itself; anything else goes to another app. */
enum class PreviewKind { Image, Video, Audio, Pdf }

fun mimeTypeOf(name: String): String =
    MimeTypeMap
        .getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"

// Image types Android can decode; others (SVG, TIFF, PSD, camera RAW) go to other apps.
private val decodableImages =
    setOf(
        "image/jpeg",
        "image/png",
        "image/gif",
        "image/webp",
        "image/bmp",
        "image/x-ms-bmp",
        "image/heic",
        "image/heif",
        "image/avif",
        "image/x-icon",
        "image/vnd.microsoft.icon",
    )

fun previewKindOf(file: CloudFile): PreviewKind? {
    if (file.isFolder) return null
    val mime = mimeTypeOf(file.name)
    return when {
        mime == "application/pdf" -> PreviewKind.Pdf
        mime in decodableImages -> PreviewKind.Image
        mime.startsWith("video/") -> PreviewKind.Video
        mime.startsWith("audio/") -> PreviewKind.Audio
        else -> null
    }
}

/** A file being copied to the device before another app can open it. */
data class FetchTask(
    val name: String,
    val sent: Long = 0,
    val total: Long = 0,
) {
    val fraction: Float get() = if (total <= 0) 0f else (sent.toFloat() / total).coerceIn(0f, 1f)
}

/** Shows the system app picker for a local copy of [name]. */
fun Context.openWith(
    uri: Uri,
    name: String,
) {
    val view =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeTypeOf(name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(view, getString(R.string.files_open_with)))
}

/** Hands a share link to the system share sheet. */
fun Context.sendLink(
    url: String,
    name: String,
) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, name)
            putExtra(Intent.EXTRA_TEXT, url)
        }
    startActivity(Intent.createChooser(send, name))
}

@Composable
fun FetchDialog(
    task: FetchTask,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.files_preparing)) },
        text = {
            Column {
                Text(task.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                if (task.total > 0) {
                    LinearProgressIndicator(progress = { task.fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}
