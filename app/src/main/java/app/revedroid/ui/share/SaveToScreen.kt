package app.revedroid.ui.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.revedroid.R
import app.revedroid.container
import app.revedroid.data.repo.CloudUris
import app.revedroid.ui.components.rememberNotificationPermission
import app.revedroid.ui.files.FolderPicker
import app.revedroid.ui.files.FolderPickerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration

/** Reads what another app shared: files as content URIs, or text saved to a file. */
object SharedItems {
    /** Must match res/xml/file_paths.xml. */
    private const val TEXT_DIR = "shared"

    fun from(
        context: Context,
        intent: Intent,
    ): List<Uri> {
        val streams =
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                }

                Intent.ACTION_SEND_MULTIPLE -> {
                    IntentCompat
                        .getParcelableArrayListExtra(
                            intent,
                            Intent.EXTRA_STREAM,
                            Uri::class.java,
                        ).orEmpty()
                }

                else -> {
                    return emptyList()
                }
            }
        if (streams.isNotEmpty()) return streams
        val clipped =
            intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
        if (clipped.isNotEmpty()) return clipped
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(saveText(context, text, intent.getStringExtra(Intent.EXTRA_SUBJECT)))
    }

    /** Shared text (a link, a note) is uploaded as a .txt file. */
    private fun saveText(
        context: Context,
        text: String,
        subject: String?,
    ): Uri {
        val root = File(context.cacheDir, TEXT_DIR)
        val cutoff = System.currentTimeMillis() - Duration.ofDays(1).toMillis()
        root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        val title =
            subject
                ?.replace(Regex("[\\\\/:*?\"<>|\\n]"), "_")
                ?.trim()
                ?.take(80)
                ?.takeIf { it.isNotEmpty() }
                ?: context.getString(R.string.save_text_name)
        // Keyed by content, so recreating the activity reuses the same file.
        val dir = File(root, text.hashCode().toUInt().toString(16)).apply { mkdirs() }
        val file = File(dir, "$title.txt").apply { writeText(text) }
        return context.container.fileCache.uriFor(file)
    }
}

/** Asks which folder the shared files go to, then hands them to the background uploader. */
@Composable
fun SaveToScreen(
    items: List<Uri>,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val withNotifications = rememberNotificationPermission()
    // The last folder used, if it belongs to the account that's signed in now.
    val start by produceState<String?>(null) {
        val account =
            container.sessions.session.value
                ?.key
        val saved = container.store.lastUploadFolder()?.split('\n', limit = 2)
        value = saved?.takeIf { it.size == 2 && it[0] == account }?.get(1) ?: CloudUris.MY_ROOT
    }
    val names by produceState(emptyList<String>(), items) {
        value = withContext(Dispatchers.IO) { items.map(container.fileRepository::displayName) }
    }

    val startUri = start
    if (startUri == null) {
        Surface(Modifier.fillMaxSize()) {}
        return
    }
    val picker = viewModel { FolderPickerViewModel(container, startUri) }
    val toastText = pluralStringResource(R.plurals.save_started, items.size, items.size)
    FolderPicker(
        viewModel = picker,
        title = pluralStringResource(R.plurals.save_title, items.size, items.size),
        confirmLabel = stringResource(R.string.save_here),
        onConfirm = { target ->
            withNotifications {
                container.uploads.enqueue(target.uri, items, target.policy)
                val account =
                    container.sessions.session.value
                        ?.key
                container.appScope.launch { container.store.setLastUploadFolder("$account\n${target.uri}") }
                Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
                onDone()
            }
        },
        onCancel = onDone,
        header = { Incoming(names) },
    )
}

@Composable
private fun Incoming(names: List<String>) {
    if (names.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                names.take(MAX_NAMES).forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (names.size > MAX_NAMES) {
                    val more = names.size - MAX_NAMES
                    Text(
                        pluralStringResource(R.plurals.save_more, more, more),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

private const val MAX_NAMES = 3
