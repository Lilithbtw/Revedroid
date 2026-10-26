package app.revedroid.ui.viewer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.pdf.PdfDocument
import app.revedroid.AppContainer
import app.revedroid.data.api.CloudFile
import app.revedroid.ui.components.userMessage
import app.revedroid.ui.files.FetchTask
import app.revedroid.ui.files.PreviewKind
import app.revedroid.ui.files.previewKindOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ViewerUiState(
    /** Image URL; video and audio stream through [player], PDFs render from [document]. */
    val url: String? = null,
    val player: ExoPlayer? = null,
    val document: PdfDocument? = null,
    /** PDFs are copied to the device before they render. */
    val progress: FetchTask? = null,
    val error: String? = null,
    /** Copy in progress for "Open with". */
    val fetching: FetchTask? = null,
)

sealed interface ViewerEvent {
    data class Message(
        val text: String,
    ) : ViewerEvent

    data class OpenWith(
        val uri: Uri,
        val name: String,
    ) : ViewerEvent
}

class ViewerViewModel(
    private val container: AppContainer,
    val file: CloudFile,
) : ViewModel() {
    private val repo = container.fileRepository
    val kind: PreviewKind = requireNotNull(previewKindOf(file)) { "${file.name} can't be previewed" }

    private val _state = MutableStateFlow(ViewerUiState())
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private val _events = Channel<ViewerEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var fetchJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        releaseContent()
        _state.update { ViewerUiState(fetching = it.fetching) }
        loadJob =
            viewModelScope.launch {
                try {
                    when (kind) {
                        PreviewKind.Image -> {
                            _state.update { it.copy(url = repo.downloadUrl(file, download = false)) }
                        }

                        PreviewKind.Video, PreviewKind.Audio -> {
                            val player = container.newMediaPlayer()
                            player.addListener(
                                object : Player.Listener {
                                    override fun onPlayerError(error: PlaybackException) = showError(error)
                                },
                            )
                            _state.update { it.copy(player = player) }
                            player.setMediaItem(MediaItem.fromUri(repo.downloadUrl(file, download = false)))
                            player.prepare()
                            player.playWhenReady = true
                        }

                        PreviewKind.Pdf -> {
                            _state.update { it.copy(progress = FetchTask(file.name)) }
                            val local =
                                container.fileCache.fetch(file) { p ->
                                    _state.update { it.copy(progress = it.progress?.copy(sent = p.sent, total = p.total)) }
                                }
                            val document = container.pdfLoader.openDocument(container.fileCache.uriFor(local))
                            _state.update { it.copy(document = document, progress = null) }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showError(e)
                }
            }
    }

    /** Also reported by the screen when the image fails to decode or download. */
    fun showError(error: Throwable) {
        releaseContent()
        _state.update { ViewerUiState(error = error.userMessage(), fetching = it.fetching) }
    }

    fun openWith() {
        if (fetchJob?.isActive == true) return
        fetchJob =
            viewModelScope.launch {
                _state.update { it.copy(fetching = FetchTask(file.name)) }
                try {
                    val local =
                        container.fileCache.fetch(file) { p ->
                            _state.update { it.copy(fetching = it.fetching?.copy(sent = p.sent, total = p.total)) }
                        }
                    _events.send(ViewerEvent.OpenWith(container.fileCache.uriFor(local), file.name))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _events.send(ViewerEvent.Message(e.userMessage()))
                } finally {
                    _state.update { it.copy(fetching = null) }
                }
            }
    }

    fun cancelFetch() {
        fetchJob?.cancel()
    }

    fun download() {
        viewModelScope.launch {
            try {
                container.downloader.enqueue(repo.downloadUrl(file), file.name)
                _events.send(ViewerEvent.Message("Downloading ${file.name}"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ViewerEvent.Message(e.userMessage()))
            }
        }
    }

    private fun releaseContent() {
        val s = _state.value
        s.player?.release()
        runCatching { s.document?.close() }
    }

    override fun onCleared() {
        releaseContent()
    }
}
