package app.revedroid.data

import app.revedroid.data.net.ServerUrls
import app.revedroid.data.net.challengePageFor
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlsTest {
    @Test
    fun `adds https when scheme is missing`() {
        assertEquals("https://cloud.example.com", ServerUrls.normalize("cloud.example.com"))
    }

    @Test
    fun `keeps explicit http and port`() {
        assertEquals("http://192.168.1.10:5212", ServerUrls.normalize("http://192.168.1.10:5212/"))
    }

    @Test
    fun `strips pasted api prefix, query and whitespace`() {
        assertEquals("https://cloud.example.com", ServerUrls.normalize("  https://cloud.example.com/api/v4/?x=1 "))
    }

    @Test
    fun `keeps sub-path installs`() {
        assertEquals("https://example.com/cloud", ServerUrls.normalize("example.com/cloud/"))
    }

    @Test
    fun `rejects garbage`() {
        assertNull(ServerUrls.normalize(""))
        assertNull(ServerUrls.normalize("https://"))
    }

    @Test
    fun `builds api urls`() {
        assertEquals("https://a.b/api/v4/site/ping", ServerUrls.api("https://a.b", "site/ping"))
        assertEquals("https://a.b/cloud/api/v4/", ServerUrls.apiBase("https://a.b/cloud"))
    }

    @Test
    fun `resolves relative urls against the server`() {
        assertEquals("https://a.b/api/v4/file/content/1", ServerUrls.resolve("https://a.b", "/api/v4/file/content/1"))
        assertEquals("https://cdn.x/y", ServerUrls.resolve("https://a.b", "https://cdn.x/y"))
    }

    @Test
    fun `same origin only matches scheme host and port`() {
        assertTrue(ServerUrls.sameOrigin("https://a.b", "https://a.b/api/v4/file".toHttpUrl()))
        assertFalse(ServerUrls.sameOrigin("https://a.b", "https://storage.a.b/x".toHttpUrl()))
        assertFalse(ServerUrls.sameOrigin("https://a.b", "http://a.b/x".toHttpUrl()))
    }
}

class ChallengePageTest {
    @Test
    fun `GET requests are solved on their own URL`() {
        assertEquals(
            "https://a.b/api/v4/file?uri=x",
            challengePageFor("GET", "https://a.b/api/v4/file?uri=x"),
        )
    }

    @Test
    fun `other methods fall back to the ping route`() {
        assertEquals(
            "https://a.b/cloud/api/v4/site/ping",
            challengePageFor("POST", "https://a.b/cloud/api/v4/file/create"),
        )
    }
}
