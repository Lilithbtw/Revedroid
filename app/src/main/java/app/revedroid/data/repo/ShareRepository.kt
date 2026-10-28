package app.revedroid.data.repo

import app.revedroid.AppContainer
import app.revedroid.data.api.ApiCode
import app.revedroid.data.api.ApiException
import app.revedroid.data.api.CloudFile
import app.revedroid.data.api.Share
import app.revedroid.data.api.ShareCreateRequest
import app.revedroid.data.api.ShareListResponse
import app.revedroid.data.api.decode
import app.revedroid.data.api.ensureOk
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration

/** How long a new share link stays valid. */
enum class ShareExpiry(
    val lifetime: Duration?,
) {
    Never(null),
    Hour(Duration.ofHours(1)),
    Day(Duration.ofDays(1)),
    Week(Duration.ofDays(7)),
    Month(Duration.ofDays(30)),
}

class ShareRepository(
    private val container: AppContainer,
) {
    /** The signed-in user's share links, newest first. */
    suspend fun list(nextPageToken: String? = null): ShareListResponse =
        container.authed {
            listShares(PAGE_SIZE, orderBy = "created_at", orderDirection = "desc", nextPageToken = nextPageToken).decode()
        }

    /** Returns the link. A [protected] link gets a password, which the server appends to the URL. */
    suspend fun create(
        file: CloudFile,
        expiry: ShareExpiry,
        protected: Boolean,
    ): String =
        container.authed {
            createShare(ShareCreateRequest(file.path, isPrivate = protected, expire = expiry.lifetime?.seconds)).decode()
        }

    /**
     * Cloudreve links a single file or folder, so several [files] first go into a new folder
     * called [folderName] inside [parent], and the link points to that folder. With [move]
     * the items move there; otherwise they are copied and the originals stay put.
     *
     * Returns the link and the folder's final name, which gets a number if [folderName] is taken.
     */
    suspend fun createForMany(
        files: List<CloudFile>,
        parent: String,
        folderName: String,
        move: Boolean,
        expiry: ShareExpiry,
        protected: Boolean,
    ): Pair<String, String> {
        val items = outermostItems(files)
        // Search results can hold same-named items from different folders, and one folder can't take both.
        items.groupBy { it.name }.values.firstOrNull { it.size > 1 }?.let {
            throw IOException("Two of the items are called \"${it.first().name}\". Rename one of them first.")
        }
        val folder = createFreeFolder(parent, folderName)
        try {
            container.fileRepository.move(items, folder.path, copy = !move)
        } catch (e: Exception) {
            // The server handles the items one by one, so some may have made it before the error.
            withContext(NonCancellable) { runCatching { undoBundle(folder, items, move) } }
            throw e
        }
        return create(folder, expiry, protected) to folder.name
    }

    /** Sends moved items back where they came from, then deletes [folder] unless something is still in it. */
    private suspend fun undoBundle(
        folder: CloudFile,
        items: List<CloudFile>,
        moved: Boolean,
    ) {
        val fileRepo = container.fileRepository
        if (moved) {
            val landed = fileRepo.list(folder.path).files.mapTo(HashSet()) { it.name }
            items.filter { it.name in landed }.groupBy { CloudUris.parent(it.path) }.forEach { (origin, group) ->
                fileRepo.move(group.map { it.copy(path = CloudUris.child(folder.path, it.name)) }, origin)
            }
            // Something that couldn't go back stays where the user can still find it.
            if (fileRepo.list(folder.path).files.isNotEmpty()) return
        }
        // Copies are new, so the folder holds nothing the user had before.
        fileRepo.delete(listOf(folder), permanently = true)
    }

    /** Creates `name`, or `name (2)`, `name (3)` and so on when that's taken. */
    private suspend fun createFreeFolder(
        parent: String,
        name: String,
    ): CloudFile {
        var attempt = 1
        while (true) {
            try {
                return container.fileRepository.createFolder(parent, if (attempt == 1) name else "$name ($attempt)")
            } catch (e: ApiException) {
                if (e.code != ApiCode.OBJECT_EXISTED || attempt == MAX_NAME_ATTEMPTS) throw e
                attempt++
            }
        }
    }

    suspend fun delete(share: Share) {
        container.authed { deleteShare(share.id).ensureOk() }
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_NAME_ATTEMPTS = 20
    }
}

/** Drops items that sit inside another of the folders in [files]; they come along with that folder. */
fun outermostItems(files: List<CloudFile>): List<CloudFile> {
    val folders = files.filter { it.isFolder }.map { it.path.trimEnd('/') + "/" }
    return files.filterNot { file -> folders.any { file.path.startsWith(it) } }
}
