package camp.room.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleConnectionTest {
    @Test
    fun connectionRequiresConfirmedPleosAdapter() {
        assertTrue(vehicle(mode = "pleos", confirmed = true).isConnected)
        assertFalse(vehicle(mode = "pleos", confirmed = false).isConnected)
        for (mode in listOf("demo", "unknown", "real", "", "future-adapter")) {
            assertFalse("Unexpected connected mode: $mode", vehicle(mode, confirmed = true).isConnected)
        }
    }

    @Test
    fun disconnectRemovesPreviouslyObservedTelemetryAndCapabilities() {
        val disconnected = vehicle(mode = "pleos", confirmed = true).disconnected()

        assertFalse(disconnected.isConnected)
        assertFalse(disconnected.connectionConfirmed)
        assertNull(disconnected.soc)
        assertNull(disconnected.measuredTemperature)
        assertNull(disconnected.targetTemperature)
        assertEquals(CabinZone(null, null, null), disconnected.front)
        assertEquals(CabinZone(null, null, null), disconnected.rear)
        assertNull(disconnected.doorsClosed)
        assertNull(disconnected.windowsClosed)
        assertEquals("unknown", disconnected.motionState)
        assertEquals("", disconnected.sceneId)
        assertEquals("", disconnected.updatedAt)
        assertEquals("unknown", disconnected.layoutSource)
        assertTrue(disconnected.controlAreas.isEmpty())
        assertTrue(disconnected.unknownFeatures.isEmpty())
    }

    private fun vehicle(mode: String, confirmed: Boolean) = Vehicle(
        soc = 78.0,
        measuredTemperature = 23.0,
        targetTemperature = 22.0,
        front = CabinZone(true, 65, 35),
        rear = CabinZone(false, 15, 80),
        doorsClosed = true,
        windowsClosed = true,
        motionState = "parked",
        sceneId = "split",
        updatedAt = "2026-10-26T12:00:00Z",
        mode = mode,
        layoutSource = "sdk_capabilities",
        controlAreas = listOf(
            CabinControlGroup(CabinArea("seat", listOf("ROW_1_LEFT")), "readingLight", true, true, 1.0),
        ),
        unknownFeatures = listOf("blindLevel"),
        connectionConfirmed = confirmed,
    )
}
