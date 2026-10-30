package camp.room.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampUxStateTest {
    @Test fun unavailableHostCannotEnablePlanningOrShopping() {
        assertFalse(CampUxState().allowsInteraction)
        assertFalse(CampUxState(requiresOptimization = false).allowsInteraction)
    }

    @Test fun hostOptimizationRequirementBlocksTheNonDoApplicationWithoutIndividualFlags() {
        assertFalse(CampUxState(observed = true, requiresOptimization = true).allowsInteraction)
        assertFalse(CampUxState(observed = true, requiresOptimization = true, activeRestrictions = 1).allowsInteraction)
    }

    @Test fun onlyObservedUnrestrictedHostEnablesInteraction() {
        assertTrue(CampUxState(observed = true, requiresOptimization = false).allowsInteraction)
        // The platform says that individual flags apply only when optimization is required.
        assertTrue(CampUxState(observed = true, requiresOptimization = false, activeRestrictions = 1).allowsInteraction)
    }
}
