package app.revedroid.data.repo

import app.revedroid.AppContainer
import app.revedroid.data.api.CloudFile
import app.revedroid.data.api.Share
import app.revedroid.data.api.ShareCreateRequest
import app.revedroid.data.api.ShareListResponse
import app.revedroid.data.api.decode
import app.revedroid.data.api.ensureOk
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

    suspend fun delete(share: Share) {
        container.authed { deleteShare(share.id).ensureOk() }
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
