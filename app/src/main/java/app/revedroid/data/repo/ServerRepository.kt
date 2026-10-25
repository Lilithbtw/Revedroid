package app.revedroid.data.repo

import app.revedroid.data.api.ApiEnvelope
import app.revedroid.data.api.CloudreveJson
import app.revedroid.data.net.CloudflareDetector
import app.revedroid.data.net.CloudflareVerdict
import app.revedroid.data.net.ServerUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

sealed interface PingResult {
    data class Ok(
        val version: String,
    ) : PingResult

    /** Cloudflare served an interstitial; the user has to pass it in a WebView. */
    data object Challenge : PingResult

    /** Cloudflare's firewall refused the request outright. */
    data object Blocked : PingResult

    /** [fatal] failures (bad host, TLS) won't fix themselves, so retrying is pointless. */
    data class Failed(
        val message: String,
        val fatal: Boolean,
    ) : PingResult
}

class ServerRepository(
    private val client: OkHttpClient,
) {
    /**
     * One `GET /api/v4/site/ping`. Succeeds only on HTTP 200 with a Cloudreve
     * envelope whose code is 0; `data` carries the backend version.
     */
    suspend fun ping(server: String): PingResult =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(ServerUrls.api(server, "site/ping"))
                    .header("Accept", "application/json")
                    .get()
                    .build()
            try {
                client.newCall(request).execute().use { response ->
                    when (CloudflareDetector.classify(response)) {
                        CloudflareVerdict.Challenge -> return@withContext PingResult.Challenge
                        CloudflareVerdict.Blocked -> return@withContext PingResult.Blocked
                        CloudflareVerdict.None -> Unit
                    }
                    if (response.code != 200) {
                        return@withContext PingResult.Failed("Server answered HTTP ${response.code}", fatal = response.code == 404)
                    }
                    val envelope =
                        runCatching {
                            CloudreveJson.decodeFromString<ApiEnvelope>(response.body!!.string())
                        }.getOrNull() ?: return@withContext PingResult.Failed(
                            "This doesn't look like a Cloudreve v4 server",
                            fatal = true,
                        )
                    if (envelope.code != 0) {
                        return@withContext PingResult.Failed(envelope.msg.ifBlank { "Ping failed (code ${envelope.code})" }, fatal = false)
                    }
                    PingResult.Ok(envelope.data?.jsonPrimitive?.contentOrNull ?: "unknown")
                }
            } catch (e: UnknownHostException) {
                PingResult.Failed("Couldn't find ${ServerUrls.displayHost(server)}", fatal = true)
            } catch (e: SSLException) {
                PingResult.Failed("Secure connection failed: ${e.message}", fatal = true)
            } catch (e: IOException) {
                PingResult.Failed(e.message ?: "Network error", fatal = false)
            } catch (e: IllegalArgumentException) {
                PingResult.Failed("Invalid server address", fatal = true)
            }
        }
}
