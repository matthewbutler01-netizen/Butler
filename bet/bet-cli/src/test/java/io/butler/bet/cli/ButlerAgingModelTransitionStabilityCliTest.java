package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAgingModelTransitionStabilityCliTest {
    @Test
    void acceptsOnlyArgumentFreeTransitionStabilityCommand() {
        assertTrue(ButlerAgingModelTransitionStabilityCli.isCommand(
            new String[]{"aging-model", "transition-stability"}));
        assertDoesNotThrow(() -> ButlerAgingModelTransitionStabilityCli.parse(
            new String[]{"aging-model", "transition-stability"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelTransitionStabilityCli.parse(
            new String[]{"aging-model", "transition-stability", "extra"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelTransitionStabilityCli.parse(
            new String[]{"aging-model", "smoothing-sensitivity"}));
    }
}
