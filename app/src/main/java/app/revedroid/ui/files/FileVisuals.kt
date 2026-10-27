package app.revedroid.ui.files

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import app.revedroid.container
import app.revedroid.data.api.CloudFile
import coil3.compose.AsyncImage

private enum class Kind(
    val icon: ImageVector,
) {
    Folder(Icons.Rounded.Folder),
    Image(Icons.Rounded.Image),
    Video(Icons.Rounded.Movie),
    Audio(Icons.Rounded.AudioFile),
    Pdf(Icons.Rounded.PictureAsPdf),
    Archive(Icons.Rounded.FolderZip),
    Code(Icons.Rounded.Code),
    Document(Icons.Rounded.Description),
    Sheet(Icons.Rounded.TableChart),
    Slides(Icons.Rounded.Slideshow),
    Apk(Icons.Rounded.Android),
    Other(Icons.AutoMirrored.Rounded.InsertDriveFile),
}

private val extensionKinds: Map<String, Kind> =
    buildMap {
        listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "tiff", "raw", "dng")
            .forEach { put(it, Kind.Image) }
        listOf("mp4", "mkv", "mov", "avi", "webm", "flv", "m4v", "wmv", "ts").forEach { put(it, Kind.Video) }
        listOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma").forEach { put(it, Kind.Audio) }
        put("pdf", Kind.Pdf)
        listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst").forEach { put(it, Kind.Archive) }
        listOf("kt", "java", "js", "ts", "py", "go", "rs", "c", "cpp", "h", "json", "xml", "yml", "yaml", "sh", "html", "css")
            .forEach { put(it, Kind.Code) }
        listOf("txt", "md", "doc", "docx", "odt", "rtf", "epub").forEach { put(it, Kind.Document) }
        listOf("xls", "xlsx", "csv", "ods").forEach { put(it, Kind.Sheet) }
        listOf("ppt", "pptx", "odp", "key").forEach { put(it, Kind.Slides) }
        put("apk", Kind.Apk)
    }

private fun kindOf(file: CloudFile): Kind = if (file.isFolder) Kind.Folder else extensionKinds[file.extension] ?: Kind.Other

fun fileIcon(file: CloudFile): ImageVector = kindOf(file).icon

@Composable
private fun kindColors(file: CloudFile): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (kindOf(file)) {
        Kind.Folder -> c.primaryContainer to c.onPrimaryContainer
        Kind.Image, Kind.Video -> c.tertiaryContainer to c.onTertiaryContainer
        Kind.Pdf, Kind.Slides -> c.errorContainer to c.onErrorContainer
        Kind.Audio, Kind.Sheet -> c.secondaryContainer to c.onSecondaryContainer
        else -> c.surfaceContainerHighest to c.onSurfaceVariant
    }
}

/**
 * Server thumbnail when one exists, otherwise a tinted type icon. Pass
 * [thumbnail] = false for stand-ins that aren't real files, such as share links.
 * (Android lint misreads the `value =` assignment inside `produceState` under K2.)
 */
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun FileThumbnail(
    file: CloudFile,
    modifier: Modifier = Modifier,
    iconSize: Dp,
    cornerRadius: Dp,
    thumbnail: Boolean = true,
) {
    val repo = LocalContext.current.container.fileRepository
    val url by produceState<String?>(null, file.id, file.updatedAt) { if (thumbnail) value = repo.thumbnailUrl(file) }
    val (container, content) = kindColors(file)
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(fileIcon(file), contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

fun formatSize(
    context: Context,
    bytes: Long,
): String = Formatter.formatShortFileSize(context, bytes)

fun formatModified(file: CloudFile): String? {
    val instant = file.updatedAtInstant ?: return null
    return DateUtils
        .getRelativeTimeSpanString(
            instant.toEpochMilli(),
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE or DateUtils.FORMAT_ABBREV_MONTH,
        ).toString()
}

fun fileSubtitle(
    context: Context,
    file: CloudFile,
): String =
    listOfNotNull(
        if (file.isFolder) null else formatSize(context, file.size),
        formatModified(file),
    ).joinToString(" · ")
