package camp.room.app

import java.util.concurrent.Executor

/** Metadata stays independent of vendor IPC, including synchronous SDK callbacks. */
internal class NavigationSdkIo(
    val stateLock: Any = Any(),
    executor: Executor = Executor { task ->
        Thread(task, "camp-navigation-followup").apply { isDaemon = true }.start()
    },
    nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onFailure: (Exception) -> Unit = {},
    private val retainedOwner: RetainedCleanupOwner = processCleanup,
    private val cleanupWaitMillis: Long = 500,
) {
    private companion object { val processCleanup = RetainedCleanupOwner() }
    private class Cleanup(val identity: Any, val action: () -> Unit)
    private val cleanupLock = Any()
    private val cleanupByIdentity = java.util.IdentityHashMap<Any, Cleanup>()
    // Only the process owner's budgeted action can drain this lane. Automatic
    // preview/admission checks cannot create additional native cleanup attempts.
    private val cleanup = VehicleListenerCleanup<Cleanup>(maxAttempts=Int.MAX_VALUE, nowMs=nowMs)
    private val lane = VehicleSdkIo(executor, onFailure)

    fun <T> call(isCurrent: () -> Boolean = { true }, action: () -> T): T? {
        check(!Thread.holdsLock(stateLock)) { "Navigation SDK call while holding metadata lock" }
        // Retirement is asynchronous. Wait for its already admitted pass before
        // taking the native lane, so a healthy fresh preview does not lose a race
        // to cleanup. A stuck/exhausted cleanup stays pending after this bound.
        check(retainedOwner.awaitClear(cleanupWaitMillis)) { "Previous navigation cleanup remains pending" }
        return lane.run {
            retainedOwner.requireClear()
            check(!cleanup.hasPending()) { "Previous navigation cleanup is unresolved" }
            if (synchronized(stateLock) { isCurrent() }) action() else null
        }
    }

    /** Callback threads only enqueue and never wait for another vendor operation. */
    fun enqueue(key: String, isCurrent: () -> Boolean, action: () -> Unit): Boolean {
        check(!Thread.holdsLock(stateLock)) { "Navigation follow-up while holding metadata lock" }
        return lane.request(key) {
            if (!cleanup.hasPending() && !retainedOwner.hasPending() && synchronized(stateLock) { isCurrent() }) action()
        }
    }

    /** Retention and queue admission do not execute SDK code, so lifecycle callers stay free. */
    fun retire(identity: Any, action: () -> Unit) {
        synchronized(cleanupLock) {
            cleanupByIdentity[identity] ?: Cleanup(identity, action).also {
                cleanupByIdentity[identity] = it
                cleanup.retain(it)
            }
        }
        // Static owner survives a retired ViewModel and periodically re-enters this
        // exact lane/bundle. It is the sole authority for automatic native attempts.
        retainedOwner.retire(this) { lane.run { drainCleanup(); !cleanup.hasPending() } }
    }

    /** Only a verified user-operation boundary may request a fresh bounded burst. */
    fun retryAfterExplicitRequest() = retainedOwner.retryAfterExplicitRequest()

    fun cleanupPending(): Boolean = cleanup.hasPending()

    private fun drainCleanup() {
        check(!Thread.holdsLock(stateLock)) { "Navigation cleanup while holding metadata lock" }
        cleanup.drain({ entry ->
            entry.action()
            synchronized(cleanupLock) {
                if (cleanupByIdentity[entry.identity] === entry) cleanupByIdentity.remove(entry.identity)
            }
        }, onFailure)
    }
}
