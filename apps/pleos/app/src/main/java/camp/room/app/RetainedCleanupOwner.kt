package camp.room.app

import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Process owners keep exact failed resources reachable after their screen/host is gone. */
internal class RetainedCleanupOwner(
    private val schedule: (Long, () -> Unit) -> Unit = defaultScheduler(),
    private val retryDelaysMillis: List<Long> = listOf(2000, 4000, 8000, 15000),
    private val onFailure: (Exception) -> Unit = {},
) {
    private class Entry(
        val identity: Any,
        val cleanup: () -> Boolean,
    ) {
        var revision = 0L
        var attempts = 0
        var queued = false
        var running = false
        var explicitRetryRequested = false
    }

    private val lock = java.lang.Object()
    private val entries = IdentityHashMap<Any, Entry>()

    /** Preserve/notify only. Late automatic failures never replenish an exhausted budget. */
    fun retire(
        identity: Any,
        cleanup: () -> Boolean,
    ) {
        val (entry, initialAdmission) =
            synchronized(lock) {
                (entries[identity] ?: Entry(identity, cleanup).also { entries[identity] = it })
                    .also {
                        it.revision++
                    }.let { it to (it.attempts == 0 && !it.queued && !it.running) }
            }
        // Existing obligations are already owned by their scheduled pass or
        // exhausted burst. A late notification cannot expedite its backoff.
        if (initialAdmission) enqueue(entry, 0)
    }

    fun hasPending(): Boolean = synchronized(lock) { entries.isNotEmpty() }

    fun hasPendingExcept(identity: Any): Boolean = synchronized(lock) { entries.keys.any { it !== identity } }

    /** Called only at a verified user-operation boundary, never by lifecycle/callback cleanup. */
    fun retryAfterExplicitRequest() {
        val pending =
            synchronized(lock) {
                entries.values.toList().onEach {
                    if (it.running) {
                        it.explicitRetryRequested = true
                    } else {
                        it.attempts = 0
                        it.explicitRetryRequested = false
                    }
                }
            }
        pending.forEach { enqueue(it, 0) }
    }

    fun requireClear() {
        check(!hasPending()) { "Previous SDK cleanup remains pending" }
    }

    fun requireClearExcept(identity: Any) {
        check(!hasPendingExcept(identity)) { "Previous SDK owner cleanup remains pending" }
    }

    /** Await only work already admitted by the coordinator, never grant another attempt. */
    fun awaitClear(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        synchronized(lock) {
            while (entries.isNotEmpty()) {
                if (entries.values.any { !it.running && !it.queued && it.attempts > retryDelaysMillis.size }) return false
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                TimeUnit.NANOSECONDS.timedWait(lock, remaining)
            }
            return true
        }
    }

    private fun enqueue(
        entry: Entry,
        delay: Long,
    ) {
        val accepted =
            synchronized(lock) {
                if (entries[entry.identity] !== entry || entry.queued || entry.running || entry.attempts > retryDelaysMillis.size) {
                    false
                } else {
                    entry.queued = true
                    true
                }
            }
        if (!accepted) return
        try {
            schedule(delay) { drain(entry) }
        } catch (error: Exception) {
            synchronized(lock) {
                if (entries[entry.identity] === entry) entry.queued = false
                lock.notifyAll()
            }
            runCatching { onFailure(error) }
        }
    }

    private fun drain(entry: Entry) {
        val revision =
            synchronized(lock) {
                if (entries[entry.identity] !== entry || !entry.queued || entry.running) return
                entry.queued = false
                entry.running = true
                entry.attempts++
                entry.revision
            }
        var error: Exception? = null
        // No caller metadata monitor is held across native calls or native-lane waits.
        val cleaned =
            try {
                entry.cleanup()
            } catch (failure: Exception) {
                error = failure
                false
            }
        val nextDelay =
            synchronized(lock) {
                entry.running = false
                val delay =
                    if (entries[entry.identity] !== entry) {
                        null
                    } else if (cleaned && entry.revision == revision) {
                        entries.remove(entry.identity)
                        null
                    } else if (entry.explicitRetryRequested) {
                        entry.explicitRetryRequested = false
                        entry.attempts = 0
                        0L
                    } else if (cleaned && entry.attempts <= retryDelaysMillis.size) {
                        // A newer automatic obligation needs another check, but only from
                        // this burst's remaining budget. Notification is never a retry grant.
                        0L
                    } else {
                        retryDelaysMillis.getOrNull(entry.attempts - 1)
                    }
                lock.notifyAll()
                delay
            }
        error?.let { runCatching { onFailure(it) } }
        if (nextDelay != null) enqueue(entry, nextDelay)
    }

    private companion object {
        fun defaultScheduler(): (Long, () -> Unit) -> Unit {
            val executor =
                Executors.newSingleThreadScheduledExecutor { task ->
                    Thread(task, "camp-sdk-retained-cleanup").apply { isDaemon = true }
                }
            return { delay, action ->
                executor.schedule(action, delay, TimeUnit.MILLISECONDS)
                Unit
            }
        }
    }
}
