package app.revedroid.ui.viewer

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.material3.Player
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.compose.PdfViewer
import androidx.pdf.compose.rememberPdfViewerState
import app.revedroid.R
import app.revedroid.ui.files.FetchDialog
import app.revedroid.ui.files.FetchTask
import app.revedroid.ui.files.PreviewKind
import app.revedroid.ui.files.openWith
import coil3.request.ImageRequest
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ViewerEvent.Message -> snackbar.showSnackbar(event.text)
                is ViewerEvent.OpenWith -> context.openWith(event.uri, event.name)
            }
        }
    }

    // Photos and videos sit on black; documents keep the app's surface.
    val dark = viewModel.kind != PreviewKind.Pdf
    if (dark) LightSystemBarIcons()
    val background = if (dark) Color.Black else MaterialTheme.colorScheme.surfaceContainer
    val foreground = if (dark) Color.White else MaterialTheme.colorScheme.onSurface

    Scaffold(
        containerColor = background,
        contentColor = foreground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = background,
                        titleContentColor = foreground,
                        navigationIconContentColor = foreground,
                        actionIconContentColor = foreground,
                    ),
                title = { Text(viewModel.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::openWith) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.files_open_with))
                    }
                    IconButton(onClick = viewModel::download) {
                        Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.files_download))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            val error = state.error
            val player = state.player
            val document = state.document
            val url = state.url
            when {
                error != null -> ErrorState(error, onRetry = viewModel::load, onOpenWith = viewModel::openWith)
                player != null -> MediaContent(player)
                document != null -> PdfContent(document)
                url != null -> ImageContent(url, viewModel.file.name, onError = viewModel::showError)
                else -> LoadingState(state.progress)
            }
        }
    }

    state.fetching?.let { FetchDialog(it, onCancel = viewModel::cancelFetch) }
}

@Composable
private fun ImageContent(
    url: String,
    name: String,
    onError: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val currentOnError by rememberUpdatedState(onError)
    val zoomState = rememberZoomableImageState()
    val request =
        remember(url) {
            ImageRequest
                .Builder(context)
                .data(url)
                .listener(onError = { _, result -> currentOnError(result.throwable) })
                .build()
        }
    if (!zoomState.isImageDisplayed) CircularProgressIndicator(color = LocalContentColor.current)
    ZoomableAsyncImage(
        model = request,
        contentDescription = name,
        state = zoomState,
        modifier = Modifier.fillMaxSize(),
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MediaContent(player: ExoPlayer) {
    // Keep playing through rotation, but stop when the app goes to the background.
    val activity = LocalActivity.current
    LifecycleStartEffect(player) {
        onStopOrDispose { if (activity?.isChangingConfigurations != true) player.pause() }
    }
    Player(player, Modifier.fillMaxSize())
}

@OptIn(ExperimentalPdfApi::class)
@Composable
private fun PdfContent(document: PdfDocument) {
    PdfViewer(pdfDocument = document, state = rememberPdfViewerState(), modifier = Modifier.fillMaxSize())
}

@Composable
private fun LoadingState(progress: FetchTask?) {
    if (progress == null || progress.total <= 0) {
        CircularProgressIndicator(color = LocalContentColor.current)
    } else {
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp),
        )
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    onOpenWith: () -> Unit,
) {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Rounded.BrokenImage, contentDescription = null, modifier = Modifier.size(72.dp))
        Text(stringResource(R.string.viewer_error), style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            FilledTonalButton(onClick = onOpenWith) { Text(stringResource(R.string.files_open_with)) }
        }
    }
}

/** White status and navigation bar icons while on a black background, restored on exit. */
@Composable
private fun LightSystemBarIcons() {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    val lightTheme by rememberUpdatedState(MaterialTheme.colorScheme.surface.luminance() > 0.5f)
    // A SideEffect so this lands after the theme's own one when the activity is recreated.
    SideEffect {
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
    }
    DisposableEffect(controller) {
        onDispose {
            controller.isAppearanceLightStatusBars = lightTheme
            controller.isAppearanceLightNavigationBars = lightTheme
        }
    }
}
