package camp.room.app

import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serializes vendor IPC without sharing the adapter's short-lived state monitor. */
internal class VehicleSdkIo(
    private val executor: Executor =
        Executor { task ->
            Thread(task, "camp-vehicle-followup").apply { isDaemon = true }.start()
        },
    private val onFailure: (Exception) -> Unit = {},
    private val capacity: Int = 32,
) {
    private val vendor = ReentrantLock()
    private val queueLock = Any()
    private val pending = linkedMapOf<String, () -> Unit>()
    private var draining = false

    /** Used by the existing IO lifecycle caller: initialize finishes before start returns. */
    fun <T> run(block: () -> T): T = vendor.withLock(block)

    /** Callbacks/timers only enqueue; they never wait for a blocked vendor call. */
    fun request(
        key: String,
        block: () -> Unit,
    ): Boolean {
        val dispatch =
            synchronized(queueLock) {
                if (key !in pending && pending.size >= capacity) return false
                pending[key] = block
                if (draining) {
                    false
                } else {
                    draining = true
                    true
                }
            }
        if (dispatch) {
            try {
                executor.execute(::drain)
            } catch (error: RuntimeException) {
                synchronized(queueLock) {
                    draining = false
                    pending.clear()
                }
                onFailure(error)
                return false
            }
        }
        return true
    }

    private fun drain() {
        while (true) {
            val task =
                synchronized(queueLock) {
                    val key = pending.keys.firstOrNull()
                    if (key == null) {
                        draining = false
                        null
                    } else {
                        pending.remove(key)
                    }
                } ?: return
            try {
                run(task)
            } catch (error: Exception) {
                onFailure(error)
            }
        }
    }
}
