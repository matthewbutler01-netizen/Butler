package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAgingModelAgeBandThresholdFrontierCliTest {
    @Test
    void acceptsExactCommand() {
        String[] args = {"aging-model", "age-band-threshold-frontier"};
        assertTrue(ButlerAgingModelAgeBandThresholdFrontierCli.isCommand(args));
        assertDoesNotThrow(() -> ButlerAgingModelAgeBandThresholdFrontierCli.parse(args));
    }

    @Test
    void rejectsExtraArgumentsAndOtherCommands() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerAgingModelAgeBandThresholdFrontierCli.parse(
                new String[]{"aging-model", "age-band-threshold-frontier", "10"}));
        assertFalse(ButlerAgingModelAgeBandThresholdFrontierCli.isCommand(
            new String[]{"aging-model", "age-band-stability"}));
    }
}
