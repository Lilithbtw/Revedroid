package app.revedroid.ui.login

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Renders reCAPTCHA, Turnstile or Cap in a small WebView. The page is loaded
 * with the Cloudreve server as its origin because widget site keys are tied to
 * the site's domain. Tokens come back through a JavaScript bridge.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun WebCaptcha(
    kind: CaptchaKind,
    serverUrl: String,
    generation: Int,
    dark: Boolean,
    onToken: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnToken by rememberUpdatedState(onToken)
    var heightDp by remember { mutableIntStateOf(MIN_HEIGHT_DP) }
    val html = remember(kind, dark) { captchaHtml(kind, dark) } ?: return

    key(generation) {
        AndroidView(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(heightDp.coerceAtLeast(MIN_HEIGHT_DP).dp),
            factory = { context ->
                val main = Handler(Looper.getMainLooper())
                WebView(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onToken(token: String) = main.post { currentOnToken(token.ifEmpty { null }) }

                            @JavascriptInterface
                            fun onExpired() = main.post { currentOnToken(null) }

                            @JavascriptInterface
                            fun onHeight(px: Int) = main.post { heightDp = px.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP) }
                        },
                        "Android",
                    )
                    loadDataWithBaseURL("$serverUrl/", html, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

private const val MIN_HEIGHT_DP = 80
private const val MAX_HEIGHT_DP = 600

private fun captchaHtml(
    kind: CaptchaKind,
    dark: Boolean,
): String? {
    val theme = if (dark) "dark" else "light"
    val (head, body) =
        when (kind) {
            is CaptchaKind.Turnstile ->
                """<script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>""" to
                    """<div class="cf-turnstile" data-sitekey="${kind.siteKey.attr()}" data-theme="$theme"
                   data-callback="tok" data-expired-callback="exp" data-error-callback="exp"></div>"""
            is CaptchaKind.ReCaptcha ->
                """<script src="https://www.google.com/recaptcha/api.js" async defer></script>""" to
                    """<div class="g-recaptcha" data-sitekey="${kind.siteKey.attr()}" data-theme="$theme"
                   data-callback="tok" data-expired-callback="exp"></div>"""
            is CaptchaKind.Cap ->
                """<script>window.CAP_CUSTOM_WASM_URL = "${kind.wasmUrl.attr()}";</script>
               <script src="${kind.scriptUrl.attr()}" async></script>""" to
                    """<cap-widget id="cap" data-cap-api-endpoint="${kind.apiEndpoint.attr()}"></cap-widget>
                   <script>document.getElementById('cap').addEventListener('solve', function (e) { tok(e.detail.token); });</script>"""
            CaptchaKind.Image, CaptchaKind.None -> return null
        }
    return """
        <!doctype html>
        <html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="color-scheme" content="$theme">
        <style>html, body { margin: 0; padding: 4px 0; background: transparent; } body { display: flex; justify-content: center; }</style>
        <script>
          function tok(t) { Android.onToken(t || ''); }
          function exp() { Android.onExpired(); }
        </script>
        $head
        </head><body>
        $body
        <script>
          new ResizeObserver(function () {
            Android.onHeight(Math.ceil(document.documentElement.scrollHeight));
          }).observe(document.body);
        </script>
        </body></html>
        """.trimIndent()
}

private fun String.attr(): String = replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
