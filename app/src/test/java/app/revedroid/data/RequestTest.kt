package app.revedroid.data

import app.revedroid.data.api.CloudreveJson
import app.revedroid.data.api.CreateFileRequest
import app.revedroid.data.api.FileUrlRequest
import org.junit.Assert.assertTrue
import org.junit.Test

/** The encoder leaves out default values, so flags the server defaults to false must still be sent. */
class RequestTest {
    @Test
    fun newFolderFailsInsteadOfReusingAnExistingOne() {
        // Otherwise Cloudreve returns the existing folder, and a multi-item share would fill a folder that's already shared.
        val body = CloudreveJson.encodeToString(CreateFileRequest("cloudreve://my/Shared", "folder", errOnConflict = true))
        assertTrue(body, "\"err_on_conflict\":true" in body)
    }

    @Test
    fun downloadsAskForAnAttachment() {
        val body = CloudreveJson.encodeToString(FileUrlRequest(listOf("cloudreve://my/notes.txt"), download = true))
        assertTrue(body, "\"download\":true" in body)
    }
}
