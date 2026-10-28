package app.revedroid.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Preview
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.revedroid.R
import app.revedroid.data.api.CloudFile
import app.revedroid.data.repo.ShareExpiry
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class FileAction { Open, Preview, OpenWith, Download, Share, Move, Select, Rename, Delete, Restore, DeleteForever }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileActionsSheet(
    file: CloudFile,
    inTrash: Boolean,
    onAction: (FileAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FileThumbnail(file, Modifier.size(56.dp), iconSize = 28.dp, cornerRadius = 16.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        fileSubtitle(context, file),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (inTrash) {
                SheetAction(Icons.Rounded.CheckCircleOutline, stringResource(R.string.files_select)) { onAction(FileAction.Select) }
                SheetAction(Icons.Rounded.RestoreFromTrash, stringResource(R.string.files_restore)) { onAction(FileAction.Restore) }
                SheetAction(Icons.Rounded.DeleteForever, stringResource(R.string.files_delete_forever), destructive = true) {
                    onAction(FileAction.DeleteForever)
                }
            } else {
                if (file.isFolder) {
                    SheetAction(Icons.Rounded.FolderOpen, stringResource(R.string.files_open)) { onAction(FileAction.Open) }
                } else {
                    if (previewKindOf(file) != null) {
                        SheetAction(Icons.Rounded.Preview, stringResource(R.string.files_open)) { onAction(FileAction.Preview) }
                    }
                    SheetAction(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.files_open_with)) {
                        onAction(FileAction.OpenWith)
                    }
                    SheetAction(Icons.Rounded.Download, stringResource(R.string.files_download)) { onAction(FileAction.Download) }
                }
                SheetAction(Icons.Rounded.Share, stringResource(R.string.files_share)) { onAction(FileAction.Share) }
                SheetAction(Icons.AutoMirrored.Rounded.DriveFileMove, stringResource(R.string.files_move_to)) { onAction(FileAction.Move) }
                SheetAction(Icons.Rounded.DriveFileRenameOutline, stringResource(R.string.files_rename)) { onAction(FileAction.Rename) }
                SheetAction(Icons.Rounded.CheckCircleOutline, stringResource(R.string.files_select)) { onAction(FileAction.Select) }
                SheetAction(Icons.Rounded.DeleteOutline, stringResource(R.string.files_delete), destructive = true) {
                    onAction(FileAction.Delete)
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewItemSheet(
    onUpload: () -> Unit,
    onNewFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                stringResource(R.string.files_new),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            SheetAction(Icons.Rounded.UploadFile, stringResource(R.string.files_upload)) { onUpload() }
            SheetAction(Icons.Rounded.CreateNewFolder, stringResource(R.string.files_new_folder)) { onNewFolder() }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = if (destructive) color else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .clickable(onClick = onClick),
    )
}

/** Text input dialog used for new folders and renames; preselects the name before the extension. */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val stemEnd = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stemEnd))) }
    val focus = remember { FocusRequester() }
    val valid = value.text.isNotBlank() && value.text.none { it == '/' || it == '\\' } && value.text != initial
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.files_name)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(value.text) }),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = valid) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Where several shared items go: the new folder's name, and whether they move there or are copied. */
data class ShareFolder(
    val name: String,
    val move: Boolean,
)

/**
 * Options for a new share link: how long it lasts and whether it needs a password.
 * A link covers one file or folder, so for several [files] it also asks for the new
 * folder they go into and whether to copy or move them there.
 */
@Composable
fun ShareLinkDialog(
    files: List<CloudFile>,
    initialExpiry: ShareExpiry,
    onCreate: (ShareExpiry, Boolean, ShareFolder?) -> Unit,
    onDismiss: () -> Unit,
) {
    val single = files.singleOrNull()
    var expiry by rememberSaveable(initialExpiry) { mutableStateOf(initialExpiry) }
    var protected by rememberSaveable { mutableStateOf(false) }
    val defaultFolder = stringResource(R.string.share_folder_default, remember { LocalDateTime.now().format(FOLDER_STAMP) })
    var folderName by rememberSaveable { mutableStateOf(defaultFolder) }
    var move by rememberSaveable { mutableStateOf(false) }
    val folderValid = folderName.isNotBlank() && folderName.none { it == '/' || it == '\\' }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Link, contentDescription = null) },
        title = {
            Text(
                if (single != null) {
                    stringResource(R.string.share_dialog_title, single.name)
                } else {
                    pluralStringResource(R.plurals.share_dialog_title_many, files.size, files.size)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (single == null) {
                    Text(
                        stringResource(R.string.share_folder_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(12.dp))
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        singleLine = true,
                        isError = !folderValid,
                        label = { Text(stringResource(R.string.share_folder_name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(12.dp))
                    val modes = listOf(false to R.string.share_folder_copy, true to R.string.share_folder_move)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { index, (moves, label) ->
                            SegmentedButton(
                                selected = move == moves,
                                onClick = { move = moves },
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                            ) { Text(stringResource(label)) }
                        }
                    }
                    Spacer(Modifier.size(4.dp))
                    Text(
                        stringResource(if (move) R.string.share_folder_move_body else R.string.share_folder_copy_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(16.dp))
                }
                Text(stringResource(R.string.share_expiry), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.size(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShareExpiry.entries.forEach { option ->
                        FilterChip(
                            selected = expiry == option,
                            onClick = { expiry = option },
                            label = { Text(stringResource(option.label())) },
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .toggleable(value = protected, role = Role.Switch, onValueChange = { protected = it })
                            .padding(vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.share_password), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.share_password_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = protected, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(expiry, protected, if (single == null) ShareFolder(folderName.trim(), move) else null) },
                enabled = single != null || folderValid,
            ) { Text(stringResource(R.string.share_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// Sorts by date and stays clear of characters that aren't allowed in names.
private val FOLDER_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")

fun ShareExpiry.label(): Int =
    when (this) {
        ShareExpiry.Never -> R.string.share_expiry_never
        ShareExpiry.Hour -> R.string.share_expiry_hour
        ShareExpiry.Day -> R.string.share_expiry_day
        ShareExpiry.Week -> R.string.share_expiry_week
        ShareExpiry.Month -> R.string.share_expiry_month
    }
