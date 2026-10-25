package app.revedroid.data

import app.revedroid.data.repo.PingResult
import app.revedroid.data.repo.ServerRepository
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Covers both deployments: plain Cloudreve, and Cloudreve behind Cloudflare. */
class PingTest {
    private lateinit var server: MockWebServer
    private val repo = ServerRepository(OkHttpClient())

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val base get() = server.url("/").toString().trimEnd('/')

    @Test
    fun `plain cloudreve ping succeeds and hits the v4 route`() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"code":0,"data":"4.3.0","msg":""}"""))
            assertEquals(PingResult.Ok("4.3.0"), repo.ping(base))
            assertEquals("/api/v4/site/ping", server.takeRequest().path)
        }

    @Test
    fun `managed challenge is detected from cf-mitigated header`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setHeader("cf-mitigated", "challenge")
                    .setHeader("Content-Type", "text/html")
                    .setBody("<html><title>Just a moment...</title></html>"),
            )
            assertEquals(PingResult.Challenge, repo.ping(base))
        }

    @Test
    fun `legacy challenge page is detected from body markers`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(503)
                    .setHeader("Server", "cloudflare")
                    .setHeader("Content-Type", "text/html; charset=UTF-8")
                    .setBody("<script>window._cf_chl_opt={cvId:'3'}</script>"),
            )
            assertEquals(PingResult.Challenge, repo.ping(base))
        }

    @Test
    fun `firewall block is not mistaken for a challenge`() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setHeader("Server", "cloudflare")
                    .setHeader("Content-Type", "text/html")
                    .setBody("<h1>Error 1020</h1> Access denied"),
            )
            assertEquals(PingResult.Blocked, repo.ping(base))
        }

    @Test
    fun `a 503 from a non-cloudflare proxy is a retryable failure`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Server", "nginx").setBody("<html>Bad gateway</html>"))
            val result = repo.ping(base)
            assertTrue(result is PingResult.Failed && !result.fatal)
        }

    @Test
    fun `non-cloudreve response is a fatal failure`() =
        runTest {
            server.enqueue(MockResponse().setBody("<html>hello</html>"))
            val result = repo.ping(base)
            assertTrue(result is PingResult.Failed && result.fatal)
        }

    @Test
    fun `error envelope is a retryable failure`() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"code":50001,"msg":"Database Operation Failed"}"""))
            assertEquals(PingResult.Failed("Database Operation Failed", fatal = false), repo.ping(base))
        }
}
