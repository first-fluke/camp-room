package camp.room.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampUxCallbackEpochTest {
    @Test fun disconnectRejectsAnAlreadyQueuedUnrestrictedCallback() {
        val epochs = CampUxCallbackEpoch()
        val session = epochs.start()
        val oldListener = epochs.captureBinding()
        assertTrue(epochs.isCurrent(oldListener))

        epochs.invalidateBinding()
        assertTrue(epochs.isActive(session))
        assertFalse(epochs.isCurrent(oldListener))

        val reconnectedListener = epochs.captureBinding()
        assertTrue(epochs.isCurrent(reconnectedListener))
        assertFalse(epochs.isCurrent(oldListener))
    }

    @Test fun stopAndRestartRejectBothOldReadinessAndOldRestrictionCallbacks() {
        val epochs = CampUxCallbackEpoch()
        val firstSession = epochs.start()
        val listener = epochs.captureBinding()
        epochs.stop()
        assertFalse(epochs.isActive(firstSession))
        assertFalse(epochs.isCurrent(listener))
        val secondSession = epochs.start()
        assertTrue(epochs.isActive(secondSession))
        assertFalse(epochs.isActive(firstSession))
        assertFalse(epochs.isCurrent(listener))
    }
}
