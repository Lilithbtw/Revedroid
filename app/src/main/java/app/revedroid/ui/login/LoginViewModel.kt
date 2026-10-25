package app.revedroid.ui.login

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.revedroid.AppContainer
import app.revedroid.data.api.ApiCode
import app.revedroid.data.api.ApiException
import app.revedroid.data.api.SiteConfig
import app.revedroid.data.repo.LoginOutcome
import app.revedroid.ui.components.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which of Cloudreve's login captcha providers the site uses. */
sealed interface CaptchaKind {
    data object None : CaptchaKind

    data object Image : CaptchaKind

    data class ReCaptcha(
        val siteKey: String,
    ) : CaptchaKind

    data class Turnstile(
        val siteKey: String,
    ) : CaptchaKind

    data class Cap(
        val apiEndpoint: String,
        val scriptUrl: String,
        val wasmUrl: String,
    ) : CaptchaKind
}

data class LoginUiState(
    val server: String = "",
    val loadingConfig: Boolean = true,
    val configError: String? = null,
    val siteTitle: String? = null,
    val email: String = "",
    val password: String = "",
    val captcha: CaptchaKind = CaptchaKind.None,
    val captchaImage: ImageBitmap? = null,
    val captchaTicket: String? = null,
    val captchaText: String = "",
    val webCaptchaToken: String? = null,
    /** Bumped whenever the captcha must be re-solved (they are single use). */
    val captchaGeneration: Int = 0,
    val twoFactorSessionId: String? = null,
    val otp: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val captchaReady: Boolean
        get() =
            when (captcha) {
                CaptchaKind.None -> true
                CaptchaKind.Image -> captchaTicket != null && captchaText.isNotBlank()
                else -> webCaptchaToken != null
            }

    val canSubmit: Boolean
        get() =
            !submitting &&
                !loadingConfig &&
                configError == null &&
                if (twoFactorSessionId != null) {
                    otp.length >= 6
                } else {
                    email.isNotBlank() && password.isNotEmpty() && captchaReady
                }
}

class LoginViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val auth = container.authRepository

    private val _state = MutableStateFlow(LoginUiState(server = container.serverUrl.orEmpty()))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private val _loggedIn = Channel<Unit>(Channel.CONFLATED)
    val loggedIn = _loggedIn.receiveAsFlow()

    init {
        loadConfig()
    }

    fun loadConfig() {
        _state.update { it.copy(loadingConfig = true, configError = null) }
        viewModelScope.launch {
            try {
                val config = auth.loginConfig()
                _state.update {
                    it.copy(
                        loadingConfig = false,
                        siteTitle = config.title,
                        captcha = captchaKindOf(config),
                    )
                }
                if (_state.value.captcha == CaptchaKind.Image) refreshImageCaptcha()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingConfig = false, configError = e.userMessage()) }
            }
        }
    }

    fun onEmailChange(v: String) = _state.update { it.copy(email = v, error = null) }

    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, error = null) }

    fun onCaptchaTextChange(v: String) = _state.update { it.copy(captchaText = v, error = null) }

    fun onOtpChange(v: String) = _state.update { it.copy(otp = v.filter(Char::isDigit).take(6), error = null) }

    fun onWebCaptchaToken(token: String?) = _state.update { it.copy(webCaptchaToken = token) }

    fun cancelTwoFactor() = _state.update { it.copy(twoFactorSessionId = null, otp = "", error = null) }

    fun refreshImageCaptcha() {
        viewModelScope.launch {
            _state.update { it.copy(captchaImage = null, captchaTicket = null, captchaText = "") }
            runCatching {
                val captcha = auth.captchaImage()
                val bitmap = withContext(Dispatchers.Default) { decodeDataUrl(captcha.image) }
                _state.update { it.copy(captchaImage = bitmap, captchaTicket = captcha.ticket) }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
    }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                if (s.twoFactorSessionId != null) {
                    auth.loginTwoFactor(s.otp, s.twoFactorSessionId)
                    _loggedIn.send(Unit)
                } else {
                    when (val outcome = auth.login(s.email.trim(), s.password, captchaParams(s))) {
                        is LoginOutcome.Success -> _loggedIn.send(Unit)
                        is LoginOutcome.TwoFactorRequired ->
                            _state.update {
                                it.copy(twoFactorSessionId = outcome.sessionId, otp = "")
                            }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val twoFactorExpired = e is ApiException && e.code == ApiCode.LOGIN_SESSION_NOT_EXIST
                if (twoFactorExpired) cancelTwoFactor()
                _state.update { it.copy(error = e.userMessage()) }
                // A failed password attempt consumes the captcha; a wrong 2FA code doesn't.
                if (s.twoFactorSessionId == null || twoFactorExpired) resetCaptcha()
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }

    private fun resetCaptcha() {
        _state.update { it.copy(webCaptchaToken = null, captchaGeneration = it.captchaGeneration + 1) }
        if (_state.value.captcha == CaptchaKind.Image) refreshImageCaptcha()
    }

    /** Field names match what each provider's widget hands the web frontend. */
    private fun captchaParams(s: LoginUiState): Map<String, String> =
        when (s.captcha) {
            CaptchaKind.None -> emptyMap()
            CaptchaKind.Image -> mapOf("captcha" to s.captchaText.trim(), "ticket" to s.captchaTicket.orEmpty())
            is CaptchaKind.ReCaptcha -> mapOf("captcha" to s.webCaptchaToken.orEmpty())
            is CaptchaKind.Turnstile, is CaptchaKind.Cap -> mapOf("ticket" to s.webCaptchaToken.orEmpty())
        }

    private fun captchaKindOf(config: SiteConfig): CaptchaKind {
        if (!config.loginCaptcha) return CaptchaKind.None
        return when (config.captchaType) {
            "recaptcha" -> CaptchaKind.ReCaptcha(config.reCaptchaKey.orEmpty())
            "turnstile" -> CaptchaKind.Turnstile(config.turnstileSiteId.orEmpty())
            "cap" -> {
                val instance = config.capInstanceUrl.orEmpty().trimEnd('/')
                val (script, wasm) =
                    when (config.capAssetServer) {
                        "instance" -> "$instance/assets/widget.js" to "$instance/assets/cap_wasm_bg.wasm"
                        "unpkg" -> "https://unpkg.com/@cap.js/widget" to "https://unpkg.com/@cap.js/wasm/browser/cap_wasm_bg.wasm"
                        else ->
                            "https://cdn.jsdelivr.net/npm/@cap.js/widget" to
                                "https://cdn.jsdelivr.net/npm/@cap.js/wasm/browser/cap_wasm_bg.wasm"
                    }
                CaptchaKind.Cap("$instance/${config.capSiteKey.orEmpty()}/", script, wasm)
            }
            else -> CaptchaKind.Image
        }
    }

    private fun decodeDataUrl(data: String): ImageBitmap? {
        val payload = data.substringAfter("base64,", data)
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
}
