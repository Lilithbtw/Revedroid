package app.revedroid.data.repo

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import app.revedroid.AppContainer
import app.revedroid.data.api.ApiEnvelope
import app.revedroid.data.api.ApiException
import app.revedroid.data.api.CloudFile
import app.revedroid.data.api.CloudreveJson
import app.revedroid.data.api.CreateFileRequest
import app.revedroid.data.api.DeleteFilesRequest
import app.revedroid.data.api.DeleteUploadSessionRequest
import app.revedroid.data.api.FileUrlRequest
import app.revedroid.data.api.FileUrlResponse
import app.revedroid.data.api.ListResponse
import app.revedroid.data.api.MoveRequest
import app.revedroid.data.api.RenameRequest
import app.revedroid.data.api.ShareCreateRequest
import app.revedroid.data.api.SiteConfig
import app.revedroid.data.api.StoragePolicy
import app.revedroid.data.api.ThumbResponse
import app.revedroid.data.api.UploadCredential
import app.revedroid.data.api.UploadSessionRequest
import app.revedroid.data.api.UrisRequest
import app.revedroid.data.api.decode
import app.revedroid.data.api.ensureOk
import app.revedroid.data.net.ServerUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream

object CloudUris {
    const val MY_ROOT = "cloudreve://my/"
    const val TRASH_ROOT = "cloudreve://trash/"

    fun child(
        parent: String,
        name: String,
    ): String = parent.trimEnd('/') + "/" + Uri.encode(name)

    /** `cloudreve://my/a/b` → [("my", "cloudreve://my/"), ("a", …/a), ("b", …/a/b)] */
    fun crumbs(uri: String): List<Pair<String, String>> {
        val withoutQuery = uri.substringBefore('?')
        val afterScheme = withoutQuery.removePrefix("cloudreve://")
        val root = afterScheme.substringBefore('/')
        val rootUri = "cloudreve://$root/"
        val segments = afterScheme.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
        val result = mutableListOf<Pair<String, String>>(root to rootUri)
        var acc = rootUri.trimEnd('/')
        segments.forEach {
            acc = "$acc/$it"
            result += Uri.decode(it) to acc
        }
        return result
    }

    fun search(
        root: String,
        keyword: String,
    ): String = root.substringBefore('?').trimEnd('/') + "/?name=" + Uri.encode(keyword) + "&case_folding=true"
}

data class UploadProgress(
    val sent: Long,
    val total: Long,
)

class UnsupportedUploadException(
    policyType: String,
) : IOException("Uploading to \"$policyType\" storage isn't supported by this app yet")

class FileRepository(
    private val container: AppContainer,
    private val resolver: ContentResolver,
) {
    private val thumbCache = LruCache<String, String>(512)
    private val noThumb = HashSet<String>()
    private val thumbExtsLock = Mutex()
    private var thumbExts: Set<String>? = null

    suspend fun list(
        uri: String,
        nextPageToken: String? = null,
        page: Int? = null,
        orderBy: String = "name",
        orderDirection: String = "asc",
    ): ListResponse =
        container.authed {
            listFiles(
                uri = uri,
                page = page,
                pageSize = PAGE_SIZE,
                orderBy = orderBy,
                orderDirection = orderDirection,
                nextPageToken = nextPageToken,
            ).decode()
        }

    private suspend fun thumbExtensions(): Set<String> =
        thumbExtsLock.withLock {
            thumbExts ?: runCatching {
                container
                    .api()
                    .siteConfig("thumb")
                    .decode<SiteConfig>()
                    .thumbExts
                    .orEmpty()
                    .map { it.lowercase() }
                    .toSet()
            }.getOrDefault(DEFAULT_THUMB_EXTS).ifEmpty { DEFAULT_THUMB_EXTS }.also { thumbExts = it }
        }

    /** Absolute thumbnail URL, or null when the server has none for this file. */
    suspend fun thumbnailUrl(file: CloudFile): String? {
        if (file.isFolder || file.metadata?.containsKey("thumb:disabled") == true) return null
        if (file.extension !in thumbExtensions()) return null
        val key = "${file.id}@${file.updatedAt}"
        thumbCache.get(key)?.let { return it }
        if (synchronized(noThumb) { key in noThumb }) return null
        return try {
            val url = container.authed { thumb(file.path).decode<ThumbResponse>() }.url
            ServerUrls.resolve(container.requireServer(), url).also { thumbCache.put(key, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // The server has no thumbnail for this file; don't ask again.
            synchronized(noThumb) { noThumb += key }
            null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun createFolder(
        parent: String,
        name: String,
    ): CloudFile =
        container.authed {
            createFile(CreateFileRequest(uri = CloudUris.child(parent, name), type = "folder")).decode()
        }

    suspend fun rename(
        file: CloudFile,
        newName: String,
    ) {
        container.authed { rename(RenameRequest(file.path, newName)).ensureOk() }
    }

    /** Moves to trash, or deletes for good when [permanently] (always the case inside the trash). */
    suspend fun delete(
        files: List<CloudFile>,
        permanently: Boolean = false,
    ) {
        container.authed { delete(DeleteFilesRequest(files.map { it.path }, skipSoftDelete = permanently)).ensureOk() }
    }

    suspend fun move(
        files: List<CloudFile>,
        destination: String,
    ) {
        container.authed { move(MoveRequest(files.map { it.path }, destination)).ensureOk() }
    }

    suspend fun restore(files: List<CloudFile>) {
        container.authed { restore(UrisRequest(files.map { it.path })).ensureOk() }
    }

    suspend fun emptyTrash() {
        container.authed { emptyTrash().ensureOk() }
    }

    /** Signed URL for the file's content; [download] asks for an attachment rather than inline content. */
    suspend fun downloadUrl(
        file: CloudFile,
        download: Boolean = true,
    ): String {
        val response = container.authed { fileUrl(FileUrlRequest(listOf(file.path), download)).decode<FileUrlResponse>() }
        val url = response.urls.firstOrNull()?.url ?: throw IOException("The server returned no download link")
        return ServerUrls.resolve(container.requireServer(), url)
    }

    suspend fun createShareLink(file: CloudFile): String = container.authed { createShare(ShareCreateRequest(file.path)).decode() }

    /**
     * Uploads [source] into [parent] using the folder's storage [policy].
     * Chunks go to the Cloudreve server for `local` and relay-mode policies and
     * to the slave node for `remote` ones; other policies upload straight to
     * third-party object storage with provider-specific protocols and are not
     * handled.
     */
    suspend fun upload(
        parent: String,
        source: Uri,
        policy: StoragePolicy,
        onProgress: (UploadProgress) -> Unit,
    ): Unit =
        withContext(Dispatchers.IO) {
            val meta = queryMeta(source)
            if (uploadMode(policy) == null) throw UnsupportedUploadException(policy.type)

            val target = CloudUris.child(parent, meta.name)
            val credential =
                container.authed {
                    createUploadSession(
                        UploadSessionRequest(
                            uri = target,
                            size = meta.size,
                            policyId = policy.id,
                            lastModified = System.currentTimeMillis(),
                            mimeType = meta.mimeType,
                        ),
                    ).decode<UploadCredential>()
                }
            // The session's policy may omit `relay`, so keep the folder's flag.
            val effective = credential.storagePolicy?.let { it.copy(relay = it.relay || policy.relay) } ?: policy
            try {
                val mode = uploadMode(effective) ?: throw UnsupportedUploadException(effective.type)
                val input = resolver.openInputStream(source) ?: throw IOException("Can't read ${meta.name}")
                input.use { sendChunks(it, meta.size, credential, mode, onProgress) }
            } catch (e: Throwable) {
                // Also runs when the upload was cancelled, so the server can drop the partial file.
                withContext(NonCancellable) {
                    runCatching {
                        container.api().deleteUploadSession(DeleteUploadSessionRequest(credential.sessionId, credential.uri ?: target))
                    }
                }
                throw e
            }
        }

    private suspend fun sendChunks(
        input: InputStream,
        size: Long,
        credential: UploadCredential,
        mode: String,
        onProgress: (UploadProgress) -> Unit,
    ) {
        val chunkSize =
            (if (credential.chunkSize > 0) credential.chunkSize else size)
                .coerceIn(1, Int.MAX_VALUE.toLong())
                .toInt()
        val buffer = ByteArray(minOf(chunkSize.toLong(), maxOf(size, 1)).toInt())
        var sent = 0L
        var index = 0
        onProgress(UploadProgress(0, size))
        do {
            currentCoroutineContext().ensureActive()
            val read = input.readFully(buffer)
            val base = sent
            val body = ProgressBody(buffer, read) { written -> onProgress(UploadProgress(base + written, size)) }
            retrying {
                when (mode) {
                    "local" -> container.authed { uploadChunk(credential.sessionId, index, body).ensureOk() }
                    "remote" -> uploadToSlave(credential, index, body)
                }
            }
            sent += read
            index++
        } while (sent < size)
        onProgress(UploadProgress(size, size))
    }

    private fun uploadToSlave(
        credential: UploadCredential,
        index: Int,
        body: RequestBody,
    ) {
        val endpoint = credential.uploadUrls?.firstOrNull() ?: throw IOException("Upload node URL missing")
        val request =
            Request
                .Builder()
                .url("$endpoint?chunk=$index")
                .header("Authorization", credential.credential.orEmpty())
                .post(body)
                .build()
        container.uploadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Upload node answered HTTP ${response.code}")
            CloudreveJson.decodeFromString<ApiEnvelope>(response.body.string()).ensureOk()
        }
    }

    private suspend fun retrying(block: suspend () -> Unit) {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                if (++attempt >= CHUNK_RETRIES) throw e
                delay(1_000L * attempt)
            }
        }
    }

    fun displayName(uri: Uri): String =
        runCatching { queryMeta(uri).name }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    private data class SourceMeta(
        val name: String,
        val size: Long,
        val mimeType: String?,
    )

    private fun queryMeta(uri: Uri): SourceMeta {
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size < 0) throw IOException("Can't determine the file size")
        return SourceMeta(name ?: uri.lastPathSegment ?: "upload", size, resolver.getType(uri))
    }

    private class ProgressBody(
        private val bytes: ByteArray,
        private val length: Int,
        private val onWritten: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = OCTET_STREAM

        override fun contentLength(): Long = length.toLong()

        override fun writeTo(sink: BufferedSink) {
            var offset = 0
            while (offset < length) {
                val n = minOf(SEGMENT, length - offset)
                sink.write(bytes, offset, n)
                offset += n
                onWritten(offset.toLong())
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 100
        private const val CHUNK_RETRIES = 3
        private const val SEGMENT = 64 * 1024
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        /**
         * How chunks travel: "local" posts them to Cloudreve itself (also used for
         * any policy in relay mode), "remote" posts them to the slave node.
         */
        private fun uploadMode(policy: StoragePolicy): String? =
            when {
                policy.relay || policy.type == "local" -> "local"
                policy.type == "remote" -> "remote"
                else -> null
            }

        private val DEFAULT_THUMB_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "mp4", "mkv", "mov")
    }
}

private fun InputStream.readFully(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val n = read(buffer, total, buffer.size - total)
        if (n < 0) break
        total += n
    }
    return total
}
