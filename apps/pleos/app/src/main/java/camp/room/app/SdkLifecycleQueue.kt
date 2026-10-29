package camp.room.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal enum class SdkLifecycleStep { START, STOP, CLOSE }

/** One dispatched FIFO consumer. Pending repeated states coalesce, final cleanup never does. */
internal class SdkLifecycleQueue(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val withGuard: suspend (suspend () -> Unit) -> Unit = { it() },
    private val onFailure: (Exception) -> Unit = {},
) {
    private data class Task(val step: SdkLifecycleStep, val work: suspend () -> Unit)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    // Three fixed keys bound pending work. Removing/reinserting preserves the
    // latest transition's order, while CLOSE always stays after admitted work.
    private val pending = linkedMapOf<SdkLifecycleStep, Task>()
    private var accepting = true
    private var draining = false

    fun submit(step: SdkLifecycleStep, work: suspend () -> Unit): Boolean {
        val dispatch = synchronized(lock) {
            if (!accepting) return false
            pending.remove(step)
            pending[step] = Task(step, work)
            if (step == SdkLifecycleStep.CLOSE) accepting = false
            if (draining) false else { draining = true; true }
        }
        // DEFAULT dispatch is essential: UNDISPATCHED can run on caller Main
        // even though the coroutine context advertises Dispatchers.IO.
        if (dispatch) scope.launch { drain() }
        return true
    }

    /** Old cleanup is always attempted, but an old stop cannot clobber a new session. */
    fun submitStop(
        shouldStop: () -> Boolean,
        cleanup: suspend () -> Unit,
        stop: suspend () -> Unit,
    ): Boolean = submit(SdkLifecycleStep.STOP) {
        cleanup()
        if (shouldStop()) stop()
    }

    private suspend fun drain() {
        while (true) {
            val task = synchronized(lock) {
                val key = pending.keys.firstOrNull()
                if (key == null) { draining = false; null } else pending.remove(key)
            } ?: return
            try { withGuard { task.work() } } catch (error: Exception) { runCatching { onFailure(error) } }
            if (task.step == SdkLifecycleStep.CLOSE) {
                synchronized(lock) { draining = false }
                scope.cancel()
                return
            }
        }
    }
}
