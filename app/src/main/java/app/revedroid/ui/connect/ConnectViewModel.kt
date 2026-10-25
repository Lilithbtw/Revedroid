package app.revedroid.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.revedroid.AppContainer
import app.revedroid.data.net.ServerUrls
import app.revedroid.data.repo.PingResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ConnectStage {
    data object Idle : ConnectStage

    /** Pinging; [afterChallenge] means the user just passed Cloudflare. */
    data class Checking(
        val attempt: Int,
        val maxAttempts: Int,
        val afterChallenge: Boolean,
    ) : ConnectStage

    data class NeedsChallenge(
        val challengeUrl: String,
    ) : ConnectStage

    data class Failed(
        val message: String,
    ) : ConnectStage

    data class Connected(
        val version: String,
    ) : ConnectStage
}

data class ConnectUiState(
    val address: String = "",
    val addressError: Boolean = false,
    val stage: ConnectStage = ConnectStage.Idle,
    val cloudflareSeen: Boolean = false,
)

sealed interface ConnectEvent {
    data class OpenChallenge(
        val url: String,
    ) : ConnectEvent

    data class Connected(
        val signedIn: Boolean,
    ) : ConnectEvent
}

/**
 * Connection flow:
 *  1. ping `/api/v4/site/ping`;
 *  2. if Cloudflare answers with a challenge, hand its URL to the WebView screen;
 *  3. once the challenge is passed, ping again until HTTP 200 + code 0.
 * Instances without Cloudflare go straight from 1 to connected.
 */
class ConnectViewModel(
    private val container: AppContainer,
    autoConnect: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    private val _events = Channel<ConnectEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var job: Job? = null
    private var server: String? = null

    init {
        viewModelScope.launch {
            val saved = container.store.serverUrl()
            if (saved != null) {
                _state.update { it.copy(address = saved) }
                if (autoConnect) connect()
            }
        }
    }

    fun onAddressChange(value: String) {
        _state.update { it.copy(address = value, addressError = false) }
    }

    fun connect() {
        val normalized = ServerUrls.normalize(_state.value.address)
        if (normalized == null) {
            _state.update { it.copy(addressError = true) }
            return
        }
        server = normalized
        _state.update { it.copy(address = normalized, cloudflareSeen = false) }
        container.setServer(normalized)
        start(afterChallenge = false)
    }

    /** Called when the challenge screen reports success. */
    fun onChallengePassed() {
        if (server == null) return
        start(afterChallenge = true)
    }

    fun openChallengeAgain() {
        val stage = _state.value.stage as? ConnectStage.NeedsChallenge ?: return
        _events.trySend(ConnectEvent.OpenChallenge(stage.challengeUrl))
    }

    fun cancel() {
        job?.cancel()
        _state.update { it.copy(stage = ConnectStage.Idle) }
    }

    private fun start(afterChallenge: Boolean) {
        val target = server ?: return
        job?.cancel()
        job = viewModelScope.launch { probe(target, afterChallenge) }
    }

    private suspend fun probe(
        target: String,
        afterChallenge: Boolean,
    ) {
        val maxAttempts = if (afterChallenge) AFTER_CHALLENGE_ATTEMPTS else INITIAL_ATTEMPTS
        var lastError = "Server unreachable"
        for (attempt in 1..maxAttempts) {
            _state.update { it.copy(stage = ConnectStage.Checking(attempt, maxAttempts, afterChallenge)) }
            when (val result = container.serverRepository.ping(target)) {
                is PingResult.Ok -> {
                    container.store.setServerUrl(target)
                    container.cookieJar.flush()
                    _state.update { it.copy(stage = ConnectStage.Connected(result.version)) }
                    container.sessions.awaitLoaded()
                    _events.send(ConnectEvent.Connected(container.sessions.hasSessionFor(target)))
                    return
                }

                PingResult.Challenge -> {
                    // Right after a solve the clearance cookie can take a moment to
                    // be honoured; only send the user back once retries run out.
                    if (afterChallenge && attempt < maxAttempts) {
                        delay(backoff(attempt))
                        continue
                    }
                    val url = ServerUrls.api(target, "site/ping")
                    _state.update { it.copy(stage = ConnectStage.NeedsChallenge(url), cloudflareSeen = true) }
                    _events.send(ConnectEvent.OpenChallenge(url))
                    return
                }

                PingResult.Blocked -> {
                    _state.update {
                        it.copy(stage = ConnectStage.Failed("Cloudflare is blocking this device. Ask the server admin to allow it."))
                    }
                    return
                }

                is PingResult.Failed -> {
                    lastError = result.message
                    if (result.fatal) break
                    if (attempt < maxAttempts) delay(backoff(attempt))
                }
            }
        }
        _state.update { it.copy(stage = ConnectStage.Failed(lastError)) }
    }

    private fun backoff(attempt: Int): Long = (750L * attempt).coerceAtMost(4_000L)

    private companion object {
        const val INITIAL_ATTEMPTS = 3
        const val AFTER_CHALLENGE_ATTEMPTS = 8
    }
}
