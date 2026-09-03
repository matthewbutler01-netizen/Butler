package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAgingModelPublishedSmootherCliTest {
    @Test
    void acceptsExactCommand() {
        String[] args = {"aging-model", "published-smoother"};
        assertTrue(ButlerAgingModelPublishedSmootherCli.isCommand(args));
        assertDoesNotThrow(() -> ButlerAgingModelPublishedSmootherCli.parse(args));
    }

    @Test
    void rejectsExtraArgumentsAndOtherCommands() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerAgingModelPublishedSmootherCli.parse(
                new String[]{"aging-model", "published-smoother", "5"}));
        assertFalse(ButlerAgingModelPublishedSmootherCli.isCommand(
            new String[]{"aging-model", "local-smoother"}));
    }
}
