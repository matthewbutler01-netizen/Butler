package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAgingModelAgeBandStabilityCliTest {
    @Test
    void acceptsExactCommand() {
        String[] args = {"aging-model", "age-band-stability"};
        assertTrue(ButlerAgingModelAgeBandStabilityCli.isCommand(args));
        assertDoesNotThrow(() -> ButlerAgingModelAgeBandStabilityCli.parse(args));
    }

    @Test
    void rejectsExtraArgumentsAndOtherCommands() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerAgingModelAgeBandStabilityCli.parse(
                new String[]{"aging-model", "age-band-stability", "35"}));
        assertFalse(ButlerAgingModelAgeBandStabilityCli.isCommand(
            new String[]{"aging-model", "support-thresholds"}));
    }
}
