package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAgingModelNormalizedStabilityCliTest {
    @Test
    void acceptsOnlyArgumentFreeNormalizedStabilityCommand() {
        assertTrue(ButlerAgingModelNormalizedStabilityCli.isCommand(
            new String[]{"aging-model", "normalized-stability"}));
        assertDoesNotThrow(() -> ButlerAgingModelNormalizedStabilityCli.parse(
            new String[]{"aging-model", "normalized-stability"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelNormalizedStabilityCli.parse(
            new String[]{"aging-model", "normalized-stability", "extra"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelNormalizedStabilityCli.parse(
            new String[]{"aging-model", "transition-stability"}));
    }
}
