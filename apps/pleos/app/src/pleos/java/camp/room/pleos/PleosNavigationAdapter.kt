package camp.room.pleos

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.helper.NaviHelper
import camp.room.app.NavigationConnectionState

/** Request methods return Unit. Host owns lifecycle-bound NaviHelperEventListener results. */
class PleosNavigationAdapter(
    private val helper: NaviHelper,
) : AutoCloseable {
    private val state = NavigationConnectionState()

    fun connect(
        onConnected: () -> Unit,
        onFailed: (Throwable) -> Unit,
    ) = state.connect(onConnected, onFailed) { success, failure -> helper.initialize(success, failure) }

    fun preview(request: TripRequest) {
        state.requireConnected()
        helper.requestTrip(request)
    }

    fun start(route: RouteInfo) {
        state.requireConnected()
        helper.requestRoute(route)
    }

    fun startWithWaypoints(request: RouteWithWaypointsRequest) {
        state.requireConnected()
        require(request.waypoints.size <= 2) { "At most two waypoints are supported" }
        val indices = request.waypoints.map { it.waypointIndex }
        require(indices.distinct().size == indices.size && indices.all { it == WaypointIndex.FIRST || it == WaypointIndex.SECOND })
        helper.requestRouteWithWaypoints(request)
    }

    override fun close() = state.close { helper.release() }
}
