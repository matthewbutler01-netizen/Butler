package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerAgingModelPositionAgeCoverageCliTest {
    @Test
    void acceptsOnlyExactArgumentFreeCommand() {
        assertDoesNotThrow(() -> ButlerAgingModelPositionAgeCoverageCli.parse(
            new String[]{"aging-model", "position-age-coverage"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelPositionAgeCoverageCli.parse(
            new String[]{"aging-model", "position-age-coverage", "QB"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelPositionAgeCoverageCli.parse(
            new String[]{"aging-model"}));
    }
}
