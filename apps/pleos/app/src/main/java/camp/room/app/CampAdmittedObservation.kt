package camp.room.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Recheck after queueing, before the first native dispatch. No admission monitor wraps SDK work. */
internal suspend fun <T> withAdmittedObservation(
    guard: Mutex,
    admitted: () -> Boolean,
    unavailable: () -> T,
    operation: suspend () -> T,
): T =
    guard.withLock {
        if (!observationStillAdmitted(admitted)) unavailable() else operation()
    }

/** Revalidate after an awaited binding/result; this cannot cancel IPC already dispatched. */
internal suspend fun observationStillAdmitted(admitted: () -> Boolean): Boolean {
    currentCoroutineContext().ensureActive()
    return admitted()
}
