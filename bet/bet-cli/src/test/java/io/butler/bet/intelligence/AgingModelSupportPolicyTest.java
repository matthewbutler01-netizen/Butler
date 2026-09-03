package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgingModelSupportPolicyTest {
    @Test
    void governsPublicationAtFiveDistinctSeasonTransitions() {
        assertEquals(5, AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS);
        assertFalse(AgingModelSupportPolicy.isPublicationEligible(0));
        assertFalse(AgingModelSupportPolicy.isPublicationEligible(4));
        assertTrue(AgingModelSupportPolicy.isPublicationEligible(5));
        assertTrue(AgingModelSupportPolicy.isPublicationEligible(25));
    }

    @Test
    void rejectsImpossibleNegativeSupport() {
        assertThrows(IllegalArgumentException.class,
            () -> AgingModelSupportPolicy.isPublicationEligible(-1));
    }

    @Test
    void exposesVersionedPolicyIdentifier() {
        assertEquals("aging-support-v1-min-transitions-5", AgingModelSupportPolicy.POLICY_ID);
    }
}
