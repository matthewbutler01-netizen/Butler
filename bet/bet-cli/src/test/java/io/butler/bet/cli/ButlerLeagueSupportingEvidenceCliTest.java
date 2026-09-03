package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerLeagueSupportingEvidenceCliTest {
    @Test
    void parsesStrictLeagueAndSeasonArguments() {
        var options = ButlerLeagueSupportingEvidenceCli.parse(
            new String[]{"league", "supporting-evidence", " league-1 ", "2026"});

        assertEquals("league-1", options.leagueId());
        assertEquals(2026, options.season());
    }

    @Test
    void rejectsMissingOrOutOfRangeArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueSupportingEvidenceCli.parse(new String[]{"league", "supporting-evidence", "league-1"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueSupportingEvidenceCli.parse(
                new String[]{"league", "supporting-evidence", "league-1", "2101"}));
    }

    @Test
    void routesThroughSingleCommandRouter() {
        assertEquals(ButlerCommandRouter.Route.LEAGUE_SUPPORTING_EVIDENCE,
            ButlerCommandRouter.route(new String[]{"league", "supporting-evidence", "league-1", "2026"}));
    }
}
