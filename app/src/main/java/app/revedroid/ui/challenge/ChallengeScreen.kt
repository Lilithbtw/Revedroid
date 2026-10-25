package app.revedroid.ui.challenge

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import app.revedroid.R
import app.revedroid.container
import kotlinx.coroutines.delay

/**
 * Hosts Cloudflare's interstitial for [url] in a WebView. The WebView shares
 * its cookie store with OkHttp (see WebViewCookieJar) and uses the same
 * User-Agent, so the clearance cookie it earns is valid for API calls.
 *
 * Success is detected by inspecting the page: once the URL renders a
 * Cloudreve JSON envelope instead of a challenge, the user is through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChallengeScreen(
    url: String,
    onPassed: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val cookieJar = context.container.cookieJar
    var progress by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }

    val webView =
        remember {
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            }
        }

    fun complete() {
        if (finished) return
        finished = true
        cookieJar.flush()
        onPassed()
    }

    fun inspect(view: WebView) {
        if (finished) return
        view.evaluateJavascript(DETECT_JS) { result ->
            if (result?.trim('"') == "ok") complete()
        }
    }

    DisposableEffect(webView) {
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView,
                    url: String?,
                    favicon: Bitmap?,
                ) {
                    progress = 0
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String?,
                ) {
                    inspect(view)
                }
            }
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(
                    view: WebView,
                    newProgress: Int,
                ) {
                    progress = newProgress
                }
            }
        webView.loadUrl(url)
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    // Some challenge variants finish without a navigation the client sees.
    LaunchedEffect(webView) {
        while (!finished) {
            delay(POLL_INTERVAL_MS)
            inspect(webView)
        }
    }

    BackHandler(onBack = onCancel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.challenge_title))
                        Text(
                            stringResource(R.string.challenge_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                },
                actions = {
                    IconButton(onClick = { webView.reload() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_reload))
                    }
                    // Escape hatch if a proxy rewrites the success page so detection misses it.
                    TextButton(onClick = ::complete) { Text(stringResource(R.string.challenge_done)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private const val POLL_INTERVAL_MS = 1_500L

/** "challenge" while Cloudflare's page is up, "ok" once a Cloudreve envelope renders. */
private val DETECT_JS =
    """
    (function() {
      try {
        if (window._cf_chl_opt) return 'challenge';
        if (document.querySelector('#challenge-form, #challenge-running, #challenge-stage, #cf-challenge-running')) return 'challenge';
        if (/just a moment|attention required/i.test(document.title || '')) return 'challenge';
        var text = ((document.body && document.body.innerText) || '').trim();
        if (text.charAt(0) === '{') {
          var parsed = JSON.parse(text);
          if (parsed && typeof parsed.code !== 'undefined') return 'ok';
        }
        return 'other';
      } catch (e) {
        return 'other';
      }
    })();
    """.trimIndent()
