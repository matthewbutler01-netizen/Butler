package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerSleeperCohortCorpusAcquisitionPlanCliTest {
    @Test
    void parsesAnchorLeagueAndSeason() {
        var options = ButlerSleeperCohortCorpusAcquisitionPlanCli.parse(
            new String[]{"league-1", "2025"});

        assertEquals("league-1", options.anchorButlerLeagueId());
        assertEquals(2025, options.targetSeason());
    }

    @Test
    void rejectsMissingOrInvalidArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCohortCorpusAcquisitionPlanCli.parse(new String[]{"league-1"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCohortCorpusAcquisitionPlanCli.parse(new String[]{"league-1", "not-a-season"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCohortCorpusAcquisitionPlanCli.parse(new String[]{"league-1", "1998"}));
    }
}
