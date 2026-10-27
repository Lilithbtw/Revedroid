package app.revedroid.data

import app.revedroid.data.api.CloudreveJson
import app.revedroid.data.api.ListResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class ListingTest {
    /** Cloudreve 4.19 sends the trash root as a parent without a path. */
    @Test
    fun trashListingWithPathlessParentDecodes() {
        val json =
            """
            {"files":[{"type":0,"id":"RMwta","name":"Meeting notes.md","size":69,
              "metadata":{"sys:restore_uri":"cloudreve://my/Meeting%20notes.md"},
              "path":"cloudreve://trash/8b3c5dcc"}],
             "parent":{"type":0,"id":"","name":"","created_at":"0001-01-01T00:00:00Z","size":0,"metadata":null},
             "pagination":{"page":0,"page_size":100,"is_cursor":true}}
            """.trimIndent()
        val listing = CloudreveJson.decodeFromString<ListResponse>(json)
        assertEquals(listOf("Meeting notes.md"), listing.files.map { it.name })
        assertEquals("", listing.parent?.path)
    }
}
