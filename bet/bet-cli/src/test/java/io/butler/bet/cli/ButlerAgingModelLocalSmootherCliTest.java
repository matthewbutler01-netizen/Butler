package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAgingModelLocalSmootherCliTest {
    @Test
    void recognizesOnlyArgumentFreeLocalSmootherCommand() {
        assertTrue(ButlerAgingModelLocalSmootherCli.isCommand(new String[]{"aging-model", "local-smoother"}));
        assertDoesNotThrow(() -> ButlerAgingModelLocalSmootherCli.parse(new String[]{"aging-model", "local-smoother"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerAgingModelLocalSmootherCli.parse(new String[]{"aging-model", "local-smoother", "25"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerAgingModelLocalSmootherCli.parse(new String[]{"aging-model", "sample-breadth"}));
    }
}
