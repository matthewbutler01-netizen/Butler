package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAgingModelTemporalHoldoutCliTest {
    @Test
    void recognizesOnlyArgumentFreeTemporalHoldoutCommand() {
        assertTrue(ButlerAgingModelTemporalHoldoutCli.isCommand(new String[]{"aging-model", "temporal-holdout"}));
        assertDoesNotThrow(() -> ButlerAgingModelTemporalHoldoutCli.parse(new String[]{"aging-model", "temporal-holdout"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerAgingModelTemporalHoldoutCli.parse(new String[]{"aging-model", "temporal-holdout", "2025"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerAgingModelTemporalHoldoutCli.parse(new String[]{"aging-model", "local-smoother"}));
    }
}
