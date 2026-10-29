package camp.room.app

import java.util.concurrent.Executor

/** One running reconciliation and one replaceable pending reconciliation. */
internal class CampUxSerialWorker(
    private val executor: Executor,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private var running = false
    private var pending: (() -> Unit)? = null

    fun request(reconcile: () -> Unit) {
        val dispatch = synchronized(this) {
            pending = reconcile
            if (running) false else { running = true; true }
        }
        if (dispatch) {
            try {
                executor.execute(::drain)
            } catch (error: RuntimeException) {
                synchronized(this) { running = false; pending = null }
                onFailure(error)
            }
        }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(this) {
                val task = pending
                pending = null
                if (task == null) running = false
                task
            } ?: return
            try {
                next()
            } catch (error: Exception) {
                onFailure(error)
            } catch (error: LinkageError) {
                onFailure(error)
            }
        }
    }
}
