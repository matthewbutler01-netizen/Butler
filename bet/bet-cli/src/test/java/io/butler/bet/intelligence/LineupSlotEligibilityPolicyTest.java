package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineupSlotEligibilityPolicyTest {
    private final LineupSlotEligibilityPolicy policy = new LineupSlotEligibilityPolicy();

    @Test
    void governsExactStarterAndFlexEligibilityFromProviderFantasyPositionsOnly() {
        assertTrue(policy.isPlayerEligible("QB", List.of("QB")));
        assertFalse(policy.isPlayerEligible("QB", List.of()));
        assertFalse(policy.isPlayerEligible("QB", List.of("RB")));

        assertTrue(policy.isPlayerEligible("FLEX", List.of("WR")));
        assertTrue(policy.isPlayerEligible("FLEX", List.of("RB", "WR")));
        assertFalse(policy.isPlayerEligible("FLEX", List.of("QB")));

        assertTrue(policy.isPlayerEligible("SUPER_FLEX", List.of("QB")));
        assertTrue(policy.isPlayerEligible("SUPER_FLEX", List.of("TE")));
        assertFalse(policy.isPlayerEligible("SUPER_FLEX", List.of("K")));
    }

    @Test
    void doesNotNormalizeOrInferProviderFantasyPositions() {
        assertFalse(policy.isPlayerEligible("WR", List.of("wr")));
        assertFalse(policy.isPlayerEligible("WR", List.of(" WR ")));
        assertFalse(policy.isPlayerEligible("WR", List.of("RB")));
    }

    @Test
    void classifiesBenchReserveSlotsAsNonStartingAndUnknownSlotsAsUnsupported() {
        assertEquals(LineupSlotEligibilityPolicy.SlotState.NON_STARTING, policy.ruleFor("BN").state());
        assertEquals(LineupSlotEligibilityPolicy.SlotState.NON_STARTING, policy.ruleFor("IR").state());
        assertEquals(LineupSlotEligibilityPolicy.SlotState.NON_STARTING, policy.ruleFor("TAXI").state());
        assertFalse(policy.isPlayerEligible("BN", List.of("QB")));

        assertEquals(LineupSlotEligibilityPolicy.SlotState.UNSUPPORTED, policy.ruleFor("REC_FLEX").state());
        assertThrows(IllegalStateException.class,
            () -> policy.isPlayerEligible("REC_FLEX", List.of("WR")));
    }
}
