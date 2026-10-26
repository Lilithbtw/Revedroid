package app.revedroid.data.repo

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.revedroid.data.api.CloudFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Keeps local copies of cloud files in the app cache, for viewers that need a
 * real file (PDF) and for handing files to other apps. Copies are keyed by id
 * and modification time, and the least recently used are evicted past [MAX_BYTES].
 */
class FileCache(
    private val context: Context,
    private val client: OkHttpClient,
    private val files: FileRepository,
) {
    private val root = File(context.cacheDir, DIR)

    // One fetch at a time, so two callers never write the same partial file.
    private val lock = Mutex()

    suspend fun fetch(
        file: CloudFile,
        onProgress: (UploadProgress) -> Unit = {},
    ): File =
        lock.withLock {
            val dir = File(root, safeFileName("${file.id}-${file.updatedAt.hashCode().toUInt().toString(16)}"))
            val target = File(dir, safeFileName(file.name))
            withContext(Dispatchers.IO) {
                if (target.isFile && target.length() == file.size) {
                    val now = System.currentTimeMillis()
                    target.setLastModified(now)
                    dir.setLastModified(now)
                } else {
                    download(files.downloadUrl(file, download = false), file.size, dir, target, onProgress)
                    evict(keep = dir)
                }
            }
            target
        }

    /** A content URI other apps can be granted read access to. */
    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private suspend fun download(
        url: String,
        expectedSize: Long,
        dir: File,
        target: File,
        onProgress: (UploadProgress) -> Unit,
    ) {
        dir.mkdirs()
        val part = File(dir, "${target.name}.part")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code})")
                val total = response.body.contentLength().takeIf { it > 0 } ?: expectedSize
                var sent = 0L
                onProgress(UploadProgress(0, total))
                response.body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(SEGMENT)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            sent += n
                            onProgress(UploadProgress(sent, total))
                        }
                    }
                }
            }
            if (!part.renameTo(target)) throw IOException("Couldn't save ${target.name}")
        } finally {
            part.delete()
        }
    }

    private fun evict(keep: File) {
        val entries =
            root
                .listFiles()
                .orEmpty()
                .filter { it != keep }
                .sortedByDescending { it.lastModified() }
        var used = keep.walk().sumOf { it.length() }
        entries.forEach { entry ->
            val size = entry.walk().sumOf { it.length() }
            if (used + size > MAX_BYTES) entry.deleteRecursively() else used += size
        }
    }

    companion object {
        /** Must match res/xml/file_paths.xml. */
        private const val DIR = "opened"
        private const val SEGMENT = 64 * 1024
        private const val MAX_BYTES = 512L * 1024 * 1024
    }
}
