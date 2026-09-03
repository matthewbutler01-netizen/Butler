package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAgingModelSampleBreadthCliTest {
    @Test
    void acceptsOnlyExactSampleBreadthCommand() {
        assertDoesNotThrow(() -> ButlerAgingModelSampleBreadthCli.parse(
            new String[]{"aging-model", "sample-breadth"}));
        assertTrue(ButlerAgingModelSampleBreadthCli.isCommand(
            new String[]{"AGING-MODEL", "SAMPLE-BREADTH"}));
    }

    @Test
    void rejectsExtraArgumentsOrOtherCommands() {
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelSampleBreadthCli.parse(
            new String[]{"aging-model", "sample-breadth", "--minimum-n", "10"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelSampleBreadthCli.parse(
            new String[]{"aging-model", "sample-audit"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelSampleBreadthCli.parse(
            new String[]{"aging-model"}));
    }
}
