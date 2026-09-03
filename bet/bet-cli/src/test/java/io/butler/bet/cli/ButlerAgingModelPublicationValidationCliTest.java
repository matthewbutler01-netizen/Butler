package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerAgingModelPublicationValidationCliTest {
    @Test
    void acceptsOnlyExactArgumentFreeCommand() {
        assertDoesNotThrow(() -> ButlerAgingModelPublicationValidationCli.parse(
            new String[]{"aging-model", "publication-validation"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelPublicationValidationCli.parse(
            new String[]{"aging-model", "publication-validation", "extra"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelPublicationValidationCli.parse(
            new String[]{"aging-model"}));
    }
}
