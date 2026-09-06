package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerSleeperProviderPointsCalibrationCorpusAuditCliTest {
    @Test
    void acceptsNoArgumentsAndRejectsOutcomeSelectionArguments() {
        assertDoesNotThrow(() -> ButlerSleeperProviderPointsCalibrationCorpusAuditCli.parse(new String[0]));
        assertDoesNotThrow(() -> ButlerSleeperProviderPointsCalibrationCorpusAuditCli.parse(null));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperProviderPointsCalibrationCorpusAuditCli.parse(new String[] {"league-1"}));
    }
}
