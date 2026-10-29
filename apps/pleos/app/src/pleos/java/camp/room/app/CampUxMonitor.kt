package camp.room.app

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI policy is observed separately from the trusted motion/actuation contract. */
@ActivityScoped
class CampUxMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutable = MutableStateFlow(CampUxState())
    val state = mutable.asStateFlow()
    private val callbacks = CampUxCallbackEpoch()
    private var request: UxConnectionRequest? = null

    fun start() {
        val next = synchronized(callbacks) {
            if (request != null) return
            UxConnectionRequest(this, callbacks.start(), context.applicationContext).also {
                request = it
                mutable.value = CampUxState()
            }
        }
        UxConnectionWorker.start(next)
    }

    fun stop() {
        val previous = synchronized(callbacks) {
            callbacks.stop()
            mutable.value = CampUxState()
            request.also { request = null }
        }
        previous?.let(UxConnectionWorker::stop)
    }

    internal fun isActive(session: Long) = callbacks.isActive(session)

    internal fun invalidate(session: Long) {
        callbacks.withActive(session) {
            callbacks.invalidateBinding()
            mutable.value = CampUxState()
        }
    }

    internal fun binding(session: Long): CampUxCallbackEpoch.Binding? {
        var result: CampUxCallbackEpoch.Binding? = null
        callbacks.withActive(session) {
            callbacks.invalidateBinding()
            mutable.value = CampUxState()
            result = callbacks.captureBinding()
        }
        return result
    }

    internal fun initialRead(binding: CampUxCallbackEpoch.Binding) = callbacks.captureInitialRead(binding)

    internal fun publishEvent(binding: CampUxCallbackEpoch.Binding, restrictions: CarUxRestrictions?) {
        val accepted = callbacks.publishEvent(binding) { mutable.value = restrictionState(restrictions) }
        tracePublication(binding, restrictions, accepted, "event")
    }

    internal fun publishInitial(token: CampUxCallbackEpoch.InitialReadToken, restrictions: CarUxRestrictions?) {
        val accepted = callbacks.publishInitial(token) { mutable.value = restrictionState(restrictions) }
        tracePublication(token.binding, restrictions, accepted, "initial")
    }

    private fun restrictionState(restrictions: CarUxRestrictions?) = restrictions?.let {
        CampUxState(true, it.isRequiresDistractionOptimization, it.activeRestrictions)
    } ?: CampUxState()

    private fun tracePublication(binding: CampUxCallbackEpoch.Binding, restrictions: CarUxRestrictions?, accepted: Boolean, source: String) {
        SdkRuntimeTrace.event("CarUx", "restriction.observed", "session" to binding.session,
            "binding" to binding.binding, "accepted" to accepted, "hasValue" to (restrictions != null),
            "source" to source,
            "onMainThread" to (Looper.myLooper() == Looper.getMainLooper()))
    }
}

private data class UxConnectionRequest(val owner: CampUxMonitor, val session: Long, val context: Context)

/**
 * One process-wide worker owns all Binder work. A stuck Binder read is not
 * cancelled by a fictitious timeout and cannot spawn more binding workers.
 * Only the latest desired lifecycle state is queued; the worker releases its
 * previous resources before connecting another owner. stop() fails closed now.
 */
private object UxConnectionWorker {
    private val lock = Any()
    private var desired: UxConnectionRequest? = null
    private var current: Connection? = null
    private val retryScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "camp-ux-cleanup-retry").apply { isDaemon = true }
    }
    private var cleanupRetry: ScheduledFuture<*>? = null
    private val worker = CampUxSerialWorker(
        Executors.newSingleThreadExecutor { task -> Thread(task, "camp-ux-binder").apply { isDaemon = true } },
    ) { error -> trace("worker.error", "errorClass" to error.javaClass.simpleName) }

    private class Connection(val request: UxConnectionRequest) {
        val resources = CampUxResourceCleanup<Car, CarUxRestrictionsManager,
            CarUxRestrictionsManager.OnUxRestrictionsChangedListener>()
        val cleanupRetries = CampUxCleanupRetryBudget()
        var ready = false
        var readyVersion = 0L
        var boundVersion = -1L
    }

    fun start(request: UxConnectionRequest) {
        synchronized(lock) {
            // A delayed start must not replace a newer owner/session.
            if (!request.owner.isActive(request.session)) return
            if (desired !== request) desired?.let { it.owner.invalidate(it.session) }
            desired = request
        }
        worker.request(::reconcile)
    }

    fun stop(request: UxConnectionRequest) {
        synchronized(lock) { if (desired === request) desired = null }
        worker.request(::reconcile)
    }

    private fun wanted(connection: Connection): Boolean = synchronized(lock) {
        current === connection && desired === connection.request &&
            connection.request.owner.isActive(connection.request.session)
    }

    private fun reconcile() {
        var pendingTarget = synchronized(lock) { desired }
        var connection = synchronized(lock) { current }
        val previous = connection
        if (previous != null && previous.request !== pendingTarget) {
            if (!cleanup(previous)) { scheduleCleanupRetry(previous); return }
            synchronized(lock) { if (current === previous) current = null }
            connection = null
            pendingTarget = synchronized(lock) { desired }
        }
        val target = pendingTarget ?: return
        if (!target.owner.isActive(target.session)) return
        if (connection == null) {
            val created = Connection(target)
            synchronized(lock) { current = created }
            try {
                trace("create.begin", "session" to target.session)
                val car = Car.createCar(target.context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { readyCar, ready ->
                    val accepted = synchronized(lock) {
                        if (current !== created || desired !== target || !target.owner.isActive(target.session)) false
                        else {
                            // Invalidate queued restrictions before admitting a new binding.
                            target.owner.invalidate(target.session)
                            created.resources.attachCar(readyCar)
                            created.ready = ready
                            created.readyVersion++
                            true
                        }
                    }
                    SdkRuntimeTrace.event("CarUx", "service.ready", "ready" to ready, "accepted" to accepted,
                        "session" to target.session, "onMainThread" to (Looper.myLooper() == Looper.getMainLooper()))
                    if (accepted) worker.request(::reconcile)
                }
                created.resources.attachCar(car)
                trace("create.returned", "session" to target.session)
            } catch (error: Exception) {
                target.owner.invalidate(target.session)
                trace("create.error", "errorClass" to error.javaClass.simpleName)
            } catch (error: LinkageError) {
                target.owner.invalidate(target.session)
                trace("create.error", "errorClass" to error.javaClass.simpleName)
            }
            return
        }
        val active = connection ?: return
        val (ready, version) = synchronized(lock) { active.ready to active.readyVersion }
        if (!ready || active.boundVersion != version) {
            if (!cleanupManager(active)) { scheduleCleanupRetry(active); return }
        }
        if (!ready || active.boundVersion == version || !wanted(active)) return
        active.boundVersion = version
        val binding = target.owner.binding(target.session) ?: return
        // Reserve before registration: even an event delivered by registerListener
        // must prevent a cached initial getter from overwriting its newer state.
        val initialRead = target.owner.initialRead(binding)
        try {
            trace("manager.begin", "session" to target.session, "binding" to binding.binding)
            val car = active.resources.car()
            val manager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            trace("manager.returned", "available" to (manager != null), "session" to target.session)
            if (manager == null) return
            if (!wanted(active) || synchronized(lock) { active.readyVersion != version || !active.ready }) {
                return
            }
            val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { restrictions ->
                target.owner.publishEvent(binding, restrictions)
            }
            active.resources.attachBinding(manager, listener)
            trace("register.request", "session" to target.session, "binding" to binding.binding)
            manager.registerListener(listener)
            trace("register.returned", "session" to target.session, "binding" to binding.binding)
            if (initialRead != null && wanted(active)) target.owner.publishInitial(initialRead, manager.currentCarUxRestrictions)
            trace("read.returned", "session" to target.session, "binding" to binding.binding)
        } catch (error: Exception) {
            target.owner.invalidate(target.session)
            active.boundVersion = -1L
            if (!cleanupManager(active)) scheduleCleanupRetry(active)
            trace("manager.error", "errorClass" to error.javaClass.simpleName)
        } catch (error: LinkageError) {
            target.owner.invalidate(target.session)
            active.boundVersion = -1L
            if (!cleanupManager(active)) scheduleCleanupRetry(active)
            trace("manager.error", "errorClass" to error.javaClass.simpleName)
        }
    }

    private fun cleanupManager(connection: Connection): Boolean = try {
        connection.resources.cleanupBinding { manager, _ ->
            manager.unregisterListener()
            trace("unregister.returned", "session" to connection.request.session)
        }.also { if (it) connection.cleanupRetries.reset() }
    } catch (error: Exception) {
        trace("unregister.error", "errorClass" to error.javaClass.simpleName, "retained" to true)
        false
    } catch (error: LinkageError) {
        trace("unregister.error", "errorClass" to error.javaClass.simpleName, "retained" to true)
        false
    }

    private fun cleanup(connection: Connection): Boolean {
        connection.request.owner.invalidate(connection.request.session)
        return try {
            val complete = connection.resources.cleanup({ manager, _ ->
                manager.unregisterListener()
                trace("unregister.returned", "session" to connection.request.session)
            }, { car ->
                car.disconnect()
                trace("disconnect.returned", "session" to connection.request.session)
            })
            trace("cleanup.returned", "session" to connection.request.session, "complete" to complete)
            complete
        } catch (error: Exception) {
            trace("cleanup.error", "errorClass" to error.javaClass.simpleName, "retained" to true)
            false
        } catch (error: LinkageError) {
            trace("cleanup.error", "errorClass" to error.javaClass.simpleName, "retained" to true)
            false
        }
    }

    /** Bounded wakeups reuse the one Binder worker; exhausted resources stay quarantined. */
    private fun scheduleCleanupRetry(connection: Connection) {
        synchronized(lock) {
            if (current !== connection || cleanupRetry?.isDone == false) return
            val delay = connection.cleanupRetries.nextDelayMillis() ?: return
            cleanupRetry = retryScheduler.schedule({
                synchronized(lock) { cleanupRetry = null }
                worker.request(::reconcile)
            }, delay, TimeUnit.MILLISECONDS)
            trace("cleanup.retry", "session" to connection.request.session,
                "attempt" to connection.cleanupRetries.attempts, "delayMs" to delay)
        }
    }

    private fun trace(event: String, vararg fields: Pair<String, Any?>) {
        SdkRuntimeTrace.event("CarUx", event,
            "onMainThread" to (Looper.myLooper() == Looper.getMainLooper()), *fields)
    }
}
