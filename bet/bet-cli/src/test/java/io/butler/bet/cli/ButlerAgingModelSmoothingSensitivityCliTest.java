package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAgingModelSmoothingSensitivityCliTest {
    @Test
    void acceptsOnlyArgumentFreeSmoothingSensitivityCommand() {
        assertTrue(ButlerAgingModelSmoothingSensitivityCli.isCommand(
            new String[]{"aging-model", "smoothing-sensitivity"}));
        assertDoesNotThrow(() -> ButlerAgingModelSmoothingSensitivityCli.parse(
            new String[]{"aging-model", "smoothing-sensitivity"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelSmoothingSensitivityCli.parse(
            new String[]{"aging-model", "smoothing-sensitivity", "extra"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelSmoothingSensitivityCli.parse(
            new String[]{"aging-model", "temporal-holdout"}));
    }
}
