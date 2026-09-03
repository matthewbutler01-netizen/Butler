package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAgingModelSampleAuditCliTest {
    @Test
    void acceptsOnlyExactSampleAuditCommand() {
        assertDoesNotThrow(() -> ButlerAgingModelSampleAuditCli.parse(
            new String[]{"aging-model", "sample-audit"}));
        assertTrue(ButlerAgingModelSampleAuditCli.isCommand(
            new String[]{"AGING-MODEL", "SAMPLE-AUDIT"}));
    }

    @Test
    void rejectsExtraArguments() {
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelSampleAuditCli.parse(
            new String[]{"aging-model", "sample-audit", "league-1"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelSampleAuditCli.parse(
            new String[]{"aging-model", "sample-audit", "--minimum-n", "10"}));
    }

    @Test
    void rejectsOtherCommands() {
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelSampleAuditCli.parse(
            new String[]{"league", "longitudinal-evidence", "league-1"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelSampleAuditCli.parse(
            new String[]{"aging-model"}));
    }
}
