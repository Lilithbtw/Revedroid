package app.revedroid.data.upload

import android.content.Context
import android.net.Uri
import app.revedroid.data.api.StoragePolicy
import app.revedroid.data.repo.FileRepository
import app.revedroid.ui.components.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class UploadTask(
    val id: Long,
    val name: String,
    val parent: String,
    val sent: Long = 0,
    val total: Long = 0,
    val running: Boolean = false,
    val error: String? = null,
) {
    val fraction: Float get() = if (total <= 0) 0f else (sent.toFloat() / total).coerceIn(0f, 1f)
    val active: Boolean get() = error == null
}

/**
 * [tasks] holds queued, running and failed uploads; finished ones leave the list
 * and are counted in [done] until the queue next runs dry.
 */
data class UploadsState(
    val tasks: List<UploadTask> = emptyList(),
    val done: Int = 0,
    val failed: Int = 0,
) {
    val active: List<UploadTask> get() = tasks.filter { it.active }
    val current: UploadTask? get() = tasks.firstOrNull { it.running }
    val busy: Boolean get() = tasks.any { it.active }
}

/**
 * Runs uploads one at a time in the app's scope rather than a screen's, and
 * keeps [UploadService] in the foreground while there is work, so uploads go on
 * with the app in the background and show their progress in a notification.
 */
class UploadManager(
    private val context: Context,
    private val files: FileRepository,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(UploadsState())
    val state: StateFlow<UploadsState> = _state.asStateFlow()

    private val _finished = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** The folder of each upload as it completes, so listings of it can refresh. */
    val finished: SharedFlow<String> = _finished.asSharedFlow()

    private class Pending(
        val id: Long,
        val parent: String,
        val source: Uri,
        val policy: StoragePolicy?,
    )

    private val queue = Channel<Pending>(Channel.UNLIMITED)
    private val ids = AtomicLong()
    private val running = ConcurrentHashMap<Long, Job>()

    init {
        scope.launch { for (pending in queue) run(pending) }
    }

    /** Queues [sources] for [parent]; [policy] is the folder's storage policy when the caller already knows it. */
    fun enqueue(
        parent: String,
        sources: List<Uri>,
        policy: StoragePolicy? = null,
    ) {
        if (sources.isEmpty()) return
        val pending = sources.map { Pending(ids.incrementAndGet(), parent, it, policy) }
        _state.update { s -> s.copy(tasks = s.tasks + pending.map { UploadTask(it.id, files.displayName(it.source), parent) }) }
        UploadNotifications.clearSummary(context)
        UploadService.start(context, sources)
        pending.forEach(queue::trySend)
    }

    fun cancel(id: Long) {
        _state.update { s -> s.copy(tasks = s.tasks.filterNot { it.id == id }) }
        running[id]?.cancel()
    }

    fun cancelAll() = _state.value.active.forEach { cancel(it.id) }

    fun dismiss(id: Long) = _state.update { s -> s.copy(tasks = s.tasks.filterNot { it.id == id }) }

    private suspend fun run(pending: Pending) {
        // Cancelled while it waited in the queue.
        if (_state.value.tasks.none { it.id == pending.id }) return finishIfIdle()
        coroutineScope {
            val job = launch { upload(pending) }
            running[pending.id] = job
            job.join()
            running.remove(pending.id)
        }
        finishIfIdle()
    }

    private suspend fun upload(pending: Pending) {
        patch(pending.id) { it.copy(running = true) }
        try {
            val policy =
                pending.policy
                    ?: files.list(pending.parent).storagePolicy
                    ?: error("This folder doesn't accept uploads")
            files.upload(pending.parent, pending.source, policy) { p ->
                patch(pending.id) { it.copy(sent = p.sent, total = p.total) }
            }
            _state.update { s -> s.copy(tasks = s.tasks.filterNot { it.id == pending.id }, done = s.done + 1) }
            _finished.tryEmit(pending.parent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { s ->
                s.copy(
                    tasks = s.tasks.map { if (it.id == pending.id) it.copy(running = false, error = e.userMessage()) else it },
                    failed = s.failed + 1,
                )
            }
        }
    }

    /** Once nothing is left to upload, reports how the run went and starts the counts afresh. */
    private fun finishIfIdle() {
        val s = _state.value
        if (s.busy) return
        UploadNotifications.showSummary(context, s.done, s.failed)
        _state.update { it.copy(done = 0, failed = 0) }
    }

    private fun patch(
        id: Long,
        change: (UploadTask) -> UploadTask,
    ) = _state.update { s -> s.copy(tasks = s.tasks.map { if (it.id == id) change(it) else it }) }
}
