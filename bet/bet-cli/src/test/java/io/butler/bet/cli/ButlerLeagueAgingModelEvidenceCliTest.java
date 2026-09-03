package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerLeagueAgingModelEvidenceCliTest {
    @Test
    void parsesLeagueAndSeasonOnly() {
        var options = ButlerLeagueAgingModelEvidenceCli.parse(
            new String[]{"league", "aging-model-evidence", " league-1 ", "2026"});
        assertEquals("league-1", options.leagueId());
        assertEquals(2026, options.season());
    }

    @Test
    void rejectsMissingInvalidOrExtraInput() {
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueAgingModelEvidenceCli.parse(
            new String[]{"league", "aging-model-evidence", "league-1"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueAgingModelEvidenceCli.parse(
            new String[]{"league", "aging-model-evidence", "league-1", "nope"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLeagueAgingModelEvidenceCli.parse(
            new String[]{"league", "aging-model-evidence", "league-1", "2026", "extra"}));
    }
}
