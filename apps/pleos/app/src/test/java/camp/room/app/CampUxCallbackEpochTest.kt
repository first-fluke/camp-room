package camp.room.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CampUxCallbackEpochTest {
    @Test fun restrictedEventCannotBeOverwrittenByAnOlderUnrestrictedInitialRead() {
        val epochs = CampUxCallbackEpoch()
        epochs.start()
        val binding = epochs.captureBinding()
        val token = requireNotNull(epochs.captureInitialRead(binding))
        var state = CampUxState()
        // The getter has returned, but its publication races the newer event.
        val olderGetterValue = CampUxState(observed = true, requiresOptimization = false)
        val newerEventValue = CampUxState(observed = true, requiresOptimization = true)
        assertTrue(epochs.publishEvent(binding) { state = newerEventValue })
        assertFalse(epochs.publishInitial(token) { state = olderGetterValue })
        assertTrue(state.observed)
        assertFalse(state.allowsInteraction)
    }

    @Test fun anEventBeforeInitialReservationCannotBeUndoneByCapturingALaterToken() {
        val epochs = CampUxCallbackEpoch()
        epochs.start()
        val binding = epochs.captureBinding()
        var state = CampUxState()
        assertTrue(epochs.publishEvent(binding) { state = CampUxState(true, true) })
        assertNull(epochs.captureInitialRead(binding))
        assertFalse(state.allowsInteraction)
    }

    @Test fun staleInitialAndEventsCannotInvalidateANewBindingsInitialRead() {
        val epochs = CampUxCallbackEpoch()
        epochs.start()
        val oldBinding = epochs.captureBinding()
        val oldToken = requireNotNull(epochs.captureInitialRead(oldBinding))
        epochs.invalidateBinding()
        val currentBinding = epochs.captureBinding()
        val currentToken = requireNotNull(epochs.captureInitialRead(currentBinding))
        var state = CampUxState()
        assertFalse(epochs.publishEvent(oldBinding) { state = CampUxState(true, false) })
        assertFalse(epochs.publishInitial(oldToken) { state = CampUxState(true, false) })
        assertTrue(epochs.publishInitial(currentToken) { state = CampUxState(true, true) })
        assertTrue(state.observed)
        assertFalse(state.allowsInteraction)
    }

    @Test fun currentEventsContinueAfterTheInitialReadWasAccepted() {
        val epochs = CampUxCallbackEpoch()
        epochs.start()
        val binding = epochs.captureBinding()
        val token = requireNotNull(epochs.captureInitialRead(binding))
        var state = CampUxState()
        assertTrue(epochs.publishInitial(token) { state = CampUxState(true, false) })
        assertTrue(state.allowsInteraction)
        assertTrue(epochs.publishEvent(binding) { state = CampUxState(true, true) })
        assertFalse(state.allowsInteraction)
    }

    @Test fun anInitialReadTokenCanPublishOnlyOnceForItsBinding() {
        val epochs = CampUxCallbackEpoch()
        epochs.start()
        val binding = epochs.captureBinding()
        val token = requireNotNull(epochs.captureInitialRead(binding))
        var state = CampUxState()
        assertTrue(epochs.publishInitial(token) { state = CampUxState(true, true) })
        assertFalse(epochs.publishInitial(token) { state = CampUxState(true, false) })
        assertNull(epochs.captureInitialRead(binding))
        assertFalse(state.allowsInteraction)
    }

    @Test fun stoppedSessionCannotPublishOrClearANewerObservation() {
        val epochs = CampUxCallbackEpoch()
        val oldSession = epochs.start()
        val oldBinding = epochs.captureBinding()
        var observed = false
        epochs.stop()
        observed = false
        epochs.start()
        val currentBinding = epochs.captureBinding()
        assertTrue(epochs.withCurrent(currentBinding) { observed = true })
        assertFalse(epochs.withActive(oldSession) { observed = false })
        assertFalse(epochs.withCurrent(oldBinding) { observed = false })
        assertTrue(observed)
    }

    @Test fun delayedRestrictionAfterDisconnectCannotReopenTheUi() {
        val epochs = CampUxCallbackEpoch()
        epochs.start()
        val listener = epochs.captureBinding()
        var observed = true
        epochs.invalidateBinding()
        observed = false
        assertFalse(epochs.withCurrent(listener) { observed = true })
        assertFalse(observed)
    }

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
