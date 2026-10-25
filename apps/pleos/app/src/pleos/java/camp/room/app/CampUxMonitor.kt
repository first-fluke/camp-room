package camp.room.app

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Listens to the platform's UI policy; it never infers gear or permission to actuate. */
@ActivityScoped
class CampUxMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutable = MutableStateFlow(CampUxState())
    val state = mutable.asStateFlow()
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var started = false
    private val callbacks = CampUxCallbackEpoch()

    fun start() {
        if (started) return
        started = true
        val session = callbacks.start()
        mutable.value = CampUxState()
        try {
            car = Car.createCar(
                context,
                Handler(Looper.getMainLooper()),
                Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
            ) { connectedCar, ready ->
                if (!started || !callbacks.isActive(session)) return@createCar
                invalidateBinding()
                if (!ready) {
                    return@createCar
                }
                val binding = callbacks.captureBinding()
                try {
                    val current = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE)
                        as? CarUxRestrictionsManager
                    if (current == null) {
                        mutable.value = CampUxState()
                        return@createCar
                    }
                    manager = current
                    current.registerListener { restrictions ->
                        if (started && callbacks.isCurrent(binding) && manager === current) {
                            update(restrictions)
                        }
                    }
                    update(current.currentCarUxRestrictions)
                } catch (_: Exception) {
                    invalidateBinding()
                }
            }
        } catch (_: Exception) {
            invalidateBinding()
        } catch (_: LinkageError) {
            // An APK opened on a non-car host must not assume an unrestricted state.
            invalidateBinding()
        }
    }

    private fun update(restrictions: CarUxRestrictions?) {
        mutable.value = restrictions?.let {
            CampUxState(
                observed = true,
                requiresOptimization = it.isRequiresDistractionOptimization,
                activeRestrictions = it.activeRestrictions,
            )
        } ?: CampUxState()
    }

    private fun invalidateBinding() {
        callbacks.invalidateBinding()
        val previous = manager
        manager = null
        mutable.value = CampUxState()
        previous?.let { runCatching { it.unregisterListener() } }
    }

    fun stop() {
        started = false
        callbacks.stop()
        invalidateBinding()
        car?.let { runCatching { it.disconnect() } }
        car = null
    }
}
