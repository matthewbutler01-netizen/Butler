package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAgingModelUniverseCliTest {
    @Test
    void recognizesPlayerAndProductionModelUniverseCommands() {
        assertTrue(ButlerAgingModelUniverseCli.isPlayersCommand(
            new String[]{"nflverse", "aging-model-players-preview"}));
        assertTrue(ButlerAgingModelUniverseCli.isPlayersCommand(
            new String[]{"NFLVERSE", "AGING-MODEL-PLAYERS-REFRESH"}));
        assertTrue(ButlerAgingModelUniverseCli.isProductionCommand(
            new String[]{"nflverse", "aging-model-production-preview", "2018", "2025"}));
        assertTrue(ButlerAgingModelUniverseCli.isProductionCommand(
            new String[]{"nflverse", "aging-model-production-refresh", "2018", "2025"}));
        assertFalse(ButlerAgingModelUniverseCli.isProductionCommand(
            new String[]{"nflverse", "production-history-refresh", "2018", "2025"}));
    }
}
