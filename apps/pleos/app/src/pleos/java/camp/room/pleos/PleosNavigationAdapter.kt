package camp.room.pleos

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.constants.WaypointIndex

/** Request methods return Unit. Host owns lifecycle-bound NaviHelperEventListener results. */
class PleosNavigationAdapter(private val helper: NaviHelper) : AutoCloseable {
    private var connected = false
    private var connecting = false
    private var closed = false
    private var generation = 0
    private data class Pending(val success: () -> Unit, val failure: (Throwable) -> Unit)
    private val pending = mutableListOf<Pending>()

    @Synchronized fun connect(onConnected: () -> Unit, onFailed: (Throwable) -> Unit) {
        check(!closed) { "Adapter is closed" }
        if (connected) { onConnected(); return }
        pending += Pending(onConnected, onFailed)
        if (connecting) return
        connecting = true
        val epoch = ++generation
        helper.initialize({ synchronized(this) {
            if (!closed && generation == epoch) {
                connected = true; connecting = false
                val callbacks = pending.toList(); pending.clear()
                callbacks.forEach { it.success() }
            }
        } }, { error -> synchronized(this) {
            if (!closed && generation == epoch) {
                connected = false; connecting = false
                val callbacks = pending.toList(); pending.clear()
                callbacks.forEach { it.failure(error) }
            }
        } })
    }
    @Synchronized fun preview(request: TripRequest) { check(connected && !closed); helper.requestTrip(request) }
    @Synchronized fun start(route: RouteInfo) { check(connected && !closed); helper.requestRoute(route) }
    @Synchronized fun startWithWaypoints(request: RouteWithWaypointsRequest) {
        check(connected && !closed)
        require(request.waypoints.size <= 2) { "At most two waypoints are supported" }
        val indices = request.waypoints.map { it.waypointIndex }
        require(indices.distinct().size == indices.size && indices.all { it == WaypointIndex.FIRST || it == WaypointIndex.SECOND })
        helper.requestRouteWithWaypoints(request)
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true; connected = false; connecting = false; generation++; pending.clear()
        helper.release()
    }
}
