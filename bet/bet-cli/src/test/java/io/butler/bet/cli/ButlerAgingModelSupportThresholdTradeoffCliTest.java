package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAgingModelSupportThresholdTradeoffCliTest {
    @Test
    void acceptsExactCommand() {
        String[] args = {"aging-model", "support-thresholds"};
        assertTrue(ButlerAgingModelSupportThresholdTradeoffCli.isCommand(args));
        assertDoesNotThrow(() -> ButlerAgingModelSupportThresholdTradeoffCli.parse(args));
    }

    @Test
    void rejectsExtraArgumentsAndOtherCommands() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerAgingModelSupportThresholdTradeoffCli.parse(new String[]{"aging-model", "support-thresholds", "10"}));
        assertFalse(ButlerAgingModelSupportThresholdTradeoffCli.isCommand(new String[]{"aging-model", "normalized-stability"}));
    }
}
