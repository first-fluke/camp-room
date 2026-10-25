package camp.room.app

import dagger.hilt.android.scopes.ActivityScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The ordinary Android demo explicitly simulates an unrestricted, stationary session. */
@ActivityScoped
class CampUxMonitor @Inject constructor() {
    private val mutable = MutableStateFlow(CampUxState(observed = true, requiresOptimization = false))
    val state = mutable.asStateFlow()

    fun start() = Unit
    fun stop() = Unit
}
