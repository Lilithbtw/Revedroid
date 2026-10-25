package app.revedroid.data.repo

import app.revedroid.AppContainer
import app.revedroid.data.api.ApiCode
import app.revedroid.data.api.Capacity
import app.revedroid.data.api.CaptchaImage
import app.revedroid.data.api.LoginResponse
import app.revedroid.data.api.RefreshTokenRequest
import app.revedroid.data.api.SiteConfig
import app.revedroid.data.api.TwoFactorLoginRequest
import app.revedroid.data.api.User
import app.revedroid.data.api.decode
import app.revedroid.data.api.toException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed interface LoginOutcome {
    data class Success(
        val response: LoginResponse,
    ) : LoginOutcome

    data class TwoFactorRequired(
        val sessionId: String,
    ) : LoginOutcome
}

class AuthRepository(
    private val container: AppContainer,
) {
    private val api get() = container.api()

    /** Merges the `basic` section (captcha provider, title) with `login` (whether captcha is on). */
    suspend fun loginConfig(): SiteConfig =
        coroutineScope {
            val basic = async { api.siteConfig("basic").decode<SiteConfig>() }
            val login = async { api.siteConfig("login").decode<SiteConfig>() }
            basic.await().copy(loginCaptcha = login.await().loginCaptcha)
        }

    suspend fun captchaImage(): CaptchaImage = api.captcha().decode()

    /** [captcha] carries whatever the active provider produced (`captcha`, `ticket`). */
    suspend fun login(
        email: String,
        password: String,
        captcha: Map<String, String>,
    ): LoginOutcome {
        val body =
            buildJsonObject {
                put("email", email)
                put("password", password)
                captcha.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            }
        val envelope = api.login(body)
        if (envelope.code == ApiCode.CONTINUE) {
            val sessionId = envelope.data?.jsonPrimitive?.contentOrNull ?: throw envelope.toException()
            return LoginOutcome.TwoFactorRequired(sessionId)
        }
        val response = envelope.decode<LoginResponse>()
        container.sessions.signIn(container.requireServer(), response)
        return LoginOutcome.Success(response)
    }

    suspend fun loginTwoFactor(
        otp: String,
        sessionId: String,
    ): LoginResponse {
        val response = api.login2fa(TwoFactorLoginRequest(otp, sessionId)).decode<LoginResponse>()
        container.sessions.signIn(container.requireServer(), response)
        return response
    }

    suspend fun me(): User = container.authed { me().decode() }

    suspend fun capacity(): Capacity = container.authed { capacity().decode() }

    /** Revokes the refresh token server-side when reachable, then forgets the session locally. */
    suspend fun signOut() {
        val token =
            container.sessions.session.value
                ?.token
                ?.refreshToken
        if (token != null) runCatching { api.signOut(RefreshTokenRequest(token)) }
        container.sessions.signOut()
    }
}
