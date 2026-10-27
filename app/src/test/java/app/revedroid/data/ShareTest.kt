package app.revedroid.data

import app.revedroid.data.api.CloudreveJson
import app.revedroid.data.api.FileType
import app.revedroid.data.api.ShareListResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Parses the share list exactly as Cloudreve 4.19 returns it. */
class ShareTest {
    private val json =
        """
        {"shares":[
          {"id":"D8S2","name":"notes.txt","remain_downloads":5,"visited":3,
           "expires":"2026-10-28T08:03:55.081988279+08:00","unlocked":true,"source_type":0,
           "owner":{"id":"L1SM"},"created_at":"2026-10-27T08:03:55.082297491+08:00",
           "expired":false,"url":"http://localhost:5212/s/D8S2","size":0},
          {"id":"Lzf8","name":"Projects","visited":0,"unlocked":true,"password_protected":true,
           "source_type":1,"created_at":"2026-10-27T08:03:55.09373157+08:00","expired":false,
           "url":"http://localhost:5212/s/Lzf8/w1sy1472","is_private":true,"password":"w1sy1472"}
        ],"pagination":{"page":0,"page_size":50,"is_cursor":true}}
        """.trimIndent()

    private val shares = CloudreveJson.decodeFromString<ShareListResponse>(json).shares

    @Test
    fun readsExpiryDownloadsAndPassword() {
        val file = shares[0]
        assertEquals(Instant.parse("2026-10-28T00:03:55.081988279Z"), file.expiresAt)
        assertEquals(5L, file.remainingDownloads)
        assertEquals(3L, file.visited)

        val folder = shares[1]
        assertNull(folder.expiresAt)
        assertTrue(folder.isPrivate)
        assertEquals("w1sy1472", folder.password)
        assertEquals(FileType.FOLDER, folder.sourceType)
    }

    @Test
    fun emptyListComesBackAsNull() {
        val empty = CloudreveJson.decodeFromString<ShareListResponse>("""{"shares":null,"pagination":{"page":0,"page_size":50}}""")
        assertTrue(empty.shares.isEmpty())
    }

    @Test
    fun expiryFollowsTheClockAndTheServerFlag() {
        val file = shares[0]
        assertFalse(file.isExpired(Instant.parse("2026-10-27T12:00:00Z")))
        assertTrue(file.isExpired(Instant.parse("2026-10-29T00:00:00Z")))
        assertTrue(file.copy(expired = true).isExpired(Instant.parse("2026-10-27T12:00:00Z")))
        assertTrue(file.copy(remainingDownloads = 0).isExpired(Instant.parse("2026-10-27T12:00:00Z")))
        assertFalse(shares[1].isExpired())
    }
}
