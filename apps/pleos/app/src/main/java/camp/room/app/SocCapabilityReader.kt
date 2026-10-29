package camp.room.app

import java.util.concurrent.TimeoutException

internal fun interface VehicleScheduledTask { fun cancel() }

/** The production SOC request lifecycle, independent of Android/vendor classes for regression tests. */
internal class SocCapabilityReader(
    private val check: ((Boolean) -> Unit, (Exception) -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> VehicleScheduledTask,
    private val dispatch: (() -> Unit) -> Boolean,
    private val onReadable: (Int) -> Unit,
    private val onError: (Int, Exception) -> Unit,
    private val isActive: (Int) -> Boolean = { true },
    private val isRetryable: (Exception) -> Boolean = {
        it !is SecurityException && it !is UnsupportedOperationException
    },
    private val trace: (String, Int, Long?) -> Unit = { _, _, _ -> },
) {
    private class Session(val epoch: Int) {
        var attempts = 0
        var request: Any? = null
        var retry: Any? = null
        var timeoutTask: VehicleScheduledTask? = null
        var retryTask: VehicleScheduledTask? = null
        var complete = false
    }
    private val lock = Any()
    private var session: Session? = null

    /** Synchronous on the existing IO caller; retries use dispatch, never the timer thread. */
    fun start(epoch: Int) {
        stop()
        val next = Session(epoch)
        synchronized(lock) { session = next }
        query(next)
    }

    fun stop(epoch: Int? = null) {
        val old = synchronized(lock) {
            session?.takeIf { epoch == null || it.epoch == epoch }?.also { session = null }
        }
        old?.timeoutTask?.cancel()
        old?.retryTask?.cancel()
    }

    private fun query(run: Session) {
        if (!isActive(run.epoch)) { abandon(run); return }
        val token = synchronized(lock) {
            if (session !== run || run.complete || run.request != null || run.attempts >= 5) return
            run.attempts++
            Any().also { run.request = it }
        }
        val timer = schedule(2200) {
            failed(run, token, TimeoutException("EV battery SOC capability check timed out"))
        }
        synchronized(lock) {
            if (session === run && run.request === token) run.timeoutTask = timer else timer.cancel()
        }
        trace("request", run.attempts, null)
        try {
            check({ readable ->
                val accepted = synchronized(lock) {
                    if (session !== run || run.request !== token) false else {
                        run.request = null
                        run.complete = true
                        run.timeoutTask?.cancel()
                        run.timeoutTask = null
                        true
                    }
                }
                if (accepted && isActive(run.epoch)) {
                    trace("capability", run.attempts, null)
                    if (readable) onReadable(run.epoch)
                    else onError(run.epoch, UnsupportedOperationException("EV battery SOC does not support reads"))
                }
            }, { error -> failed(run, token, error) })
        } catch (error: Exception) { failed(run, token, error) }
    }

    private fun failed(run: Session, token: Any, error: Exception) {
        if (!isActive(run.epoch)) { abandon(run); return }
        val retry = synchronized(lock) {
            if (session !== run || run.request !== token) return
            run.request = null
            run.timeoutTask?.cancel()
            run.timeoutTask = null
            if (run.attempts >= 5 || !isRetryable(error)) { run.complete = true; null } else {
                val marker = Any()
                run.retry = marker
                marker to longArrayOf(2000, 4000, 8000, 15000)[run.attempts - 1]
            }
        }
        trace(if (error is TimeoutException) "timeout" else "error", run.attempts, retry?.second)
        onError(run.epoch, error)
        if (retry == null) return
        val (marker, delay) = retry
        val timer = schedule(delay) {
            val accepted = synchronized(lock) {
                if (session !== run || run.retry !== marker || run.complete) false
                else { run.retry = null; run.retryTask = null; true }
            }
            if (accepted && !dispatch { query(run) }) {
                synchronized(lock) { if (session === run) run.complete = true }
                onError(run.epoch, IllegalStateException("SOC retry dispatch unavailable"))
            }
        }
        synchronized(lock) {
            if (session === run && run.retry === marker) run.retryTask = timer else timer.cancel()
        }
    }

    private fun abandon(run: Session) {
        val abandoned = synchronized(lock) { if (session === run) { session = null; true } else false }
        if (abandoned) { run.timeoutTask?.cancel(); run.retryTask?.cancel() }
    }
}
