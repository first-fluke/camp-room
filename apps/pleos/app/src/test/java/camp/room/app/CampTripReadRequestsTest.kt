package camp.room.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampTripReadRequestsTest {
    private fun key(id: String = "site-a") = CampTripReadKey(id, "2026-11-07", "2026-11-08", 2)

    @Test fun changedTripRejectsWeatherAndRouteEvenWithinTheSameSdkEpoch() {
        val requests = CampTripReadRequests()
        val old = requests.begin(key())
        assertFalse(requests.isCurrent(old, key("site-b")))
        assertFalse(requests.isCurrent(old, key().copy(checkOut = "2026-11-09")))
        assertFalse(requests.isCurrent(old, key().copy(guests = 3)))
    }

    @Test fun newerSameTripRequestAndExplicitInvalidationRejectOldResults() {
        val requests = CampTripReadRequests()
        val old = requests.begin(key())
        val fresh = requests.begin(key())
        assertFalse(requests.isCurrent(old, key()))
        assertTrue(requests.isCurrent(fresh, key()))
        requests.invalidate()
        assertFalse(requests.isCurrent(fresh, key()))
    }
}
