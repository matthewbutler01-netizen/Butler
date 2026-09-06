package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerSleeperCohortCorpusHydrateCliTest {
    @Test
    void parsesAnchorAndInclusiveSeasonRange() {
        var options = ButlerSleeperCohortCorpusHydrateCli.parse(
            new String[]{"league-1", "2022", "2025"});
        assertEquals("league-1", options.anchorLeagueId());
        assertEquals(2022, options.firstSeason());
        assertEquals(2025, options.lastSeason());
    }

    @Test
    void rejectsMissingInvalidOrReversedRange() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCohortCorpusHydrateCli.parse(new String[]{"league-1", "2025"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCohortCorpusHydrateCli.parse(new String[]{"league-1", "bad", "2025"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCohortCorpusHydrateCli.parse(new String[]{"league-1", "2025", "2024"}));
    }
}
