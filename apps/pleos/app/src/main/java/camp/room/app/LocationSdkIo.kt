package camp.room.app

/** Native serialization never shares the monitor used to invalidate location callbacks. */
internal class LocationSdkIo {
    val stateLock = Any()
    private val lane = VehicleSdkIo()
    private var generation = 0

    fun advance(): Int = synchronized(stateLock) { ++generation }
    fun current(epoch: Int): Boolean = synchronized(stateLock) { generation == epoch }
    fun <T> native(action: () -> T): T {
        check(!Thread.holdsLock(stateLock)) { "Location SDK call while holding callback metadata" }
        return lane.run(action)
    }
    fun enqueue(key: String, action: () -> Unit): Boolean {
        check(!Thread.holdsLock(stateLock)) { "Location SDK enqueue while holding callback metadata" }
        return lane.request(key, action)
    }
}
