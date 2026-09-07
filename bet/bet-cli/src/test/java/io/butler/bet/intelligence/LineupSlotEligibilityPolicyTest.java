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
        assertFalse(policy.isPlayerEligible("FLEX", List.of("K")));
        assertFalse(policy.isPlayerEligible("FLEX", List.of("DEF")));

        assertTrue(policy.isPlayerEligible("SUPER_FLEX", List.of("QB")));
        assertTrue(policy.isPlayerEligible("SUPER_FLEX", List.of("TE")));
        assertFalse(policy.isPlayerEligible("SUPER_FLEX", List.of("K")));
        assertFalse(policy.isPlayerEligible("SUPER_FLEX", List.of("DEF")));
    }

    @Test
    void supportsDedicatedKickerAndTeamDefenseSlotsFromExactProviderPositionsOnly() {
        assertEquals(LineupSlotEligibilityPolicy.SlotState.STARTING_SUPPORTED, policy.ruleFor("K").state());
        assertEquals(List.of("K"), policy.ruleFor("K").eligibleFantasyPositions());
        assertTrue(policy.isPlayerEligible("K", List.of("K")));
        assertFalse(policy.isPlayerEligible("K", List.of("QB")));
        assertFalse(policy.isPlayerEligible("K", List.of("DEF")));

        assertEquals(LineupSlotEligibilityPolicy.SlotState.STARTING_SUPPORTED, policy.ruleFor("DEF").state());
        assertEquals(List.of("DEF"), policy.ruleFor("DEF").eligibleFantasyPositions());
        assertTrue(policy.isPlayerEligible("DEF", List.of("DEF")));
        assertFalse(policy.isPlayerEligible("DEF", List.of("DL")));
        assertFalse(policy.isPlayerEligible("DEF", List.of("LB")));
        assertFalse(policy.isPlayerEligible("DEF", List.of("DB")));
        assertFalse(policy.isPlayerEligible("DEF", List.of("IDP")));
        assertFalse(policy.isPlayerEligible("DEF", List.of("K")));
    }

    @Test
    void doesNotNormalizeOrInferProviderFantasyPositions() {
        assertFalse(policy.isPlayerEligible("WR", List.of("wr")));
        assertFalse(policy.isPlayerEligible("WR", List.of(" WR ")));
        assertFalse(policy.isPlayerEligible("WR", List.of("RB")));
        assertFalse(policy.isPlayerEligible("K", List.of("k")));
        assertFalse(policy.isPlayerEligible("DEF", List.of("DST")));
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
