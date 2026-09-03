package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAgingModelAgeOutlookCliTest {
    @Test
    void recognizesArgumentFreeAgeOutlookCommand() {
        String[] args = {"aging-model", "age-outlook"};
        assertTrue(ButlerAgingModelAgeOutlookCli.isCommand(args));
        assertDoesNotThrow(() -> ButlerAgingModelAgeOutlookCli.parse(args));
    }

    @Test
    void rejectsAdditionalArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerAgingModelAgeOutlookCli.parse(new String[]{"aging-model", "age-outlook", "WR"}));
    }

    @Test
    void doesNotClaimOtherAgingCommands() {
        assertFalse(ButlerAgingModelAgeOutlookCli.isCommand(new String[]{"aging-model", "publication-validation"}));
    }
}
