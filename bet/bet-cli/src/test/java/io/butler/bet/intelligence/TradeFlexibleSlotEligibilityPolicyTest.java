package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeFlexibleSlotEligibilityPolicyTest {
    @Test
    void locksVersionedPolicyId() {
        assertEquals("trade-flexible-slot-eligibility-v1-explicit-lineup",
            TradeFlexibleSlotEligibilityPolicy.POLICY_ID);
    }

    @Test
    void flexAllowsOnlyRbWrTe() {
        assertEquals(Set.of("RB", "WR", "TE"),
            TradeFlexibleSlotEligibilityPolicy.eligiblePositions(
                TradeFlexibleSlotEligibilityPolicy.SlotType.FLEX));
        assertFalse(TradeFlexibleSlotEligibilityPolicy.isEligible(
            TradeFlexibleSlotEligibilityPolicy.SlotType.FLEX, "QB"));
        assertTrue(TradeFlexibleSlotEligibilityPolicy.isEligible(
            TradeFlexibleSlotEligibilityPolicy.SlotType.FLEX, " wr "));
    }

    @Test
    void superflexAllowsAllFourCorePositions() {
        assertEquals(Set.of("QB", "RB", "WR", "TE"),
            TradeFlexibleSlotEligibilityPolicy.eligiblePositions(
                TradeFlexibleSlotEligibilityPolicy.SlotType.SUPERFLEX));
        assertTrue(TradeFlexibleSlotEligibilityPolicy.isEligible(
            TradeFlexibleSlotEligibilityPolicy.SlotType.SUPERFLEX, "qb"));
        assertFalse(TradeFlexibleSlotEligibilityPolicy.isEligible(
            TradeFlexibleSlotEligibilityPolicy.SlotType.SUPERFLEX, "K"));
    }

    @Test
    void exposureKeepsFlexAndSuperflexSeparate() {
        var exposure = TradeFlexibleSlotEligibilityPolicy.exposure(2, 1);

        assertEquals(2, exposure.slots(TradeFlexibleSlotEligibilityPolicy.SlotType.FLEX));
        assertEquals(1, exposure.slots(TradeFlexibleSlotEligibilityPolicy.SlotType.SUPERFLEX));
        assertTrue(exposure.active(TradeFlexibleSlotEligibilityPolicy.SlotType.FLEX));
        assertTrue(exposure.active(TradeFlexibleSlotEligibilityPolicy.SlotType.SUPERFLEX));
    }

    @Test
    void rejectsInvalidInputs() {
        assertThrows(IllegalArgumentException.class,
            () -> TradeFlexibleSlotEligibilityPolicy.exposure(-1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> TradeFlexibleSlotEligibilityPolicy.isEligible(
                TradeFlexibleSlotEligibilityPolicy.SlotType.FLEX, " "));
        assertThrows(NullPointerException.class,
            () -> TradeFlexibleSlotEligibilityPolicy.eligiblePositions(null));
    }
}
