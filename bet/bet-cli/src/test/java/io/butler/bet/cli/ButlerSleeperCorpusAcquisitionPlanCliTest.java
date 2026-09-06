package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerSleeperCorpusAcquisitionPlanCliTest {
    @Test
    void parsesAnchorLeagueTeamAndSeason() {
        var options = ButlerSleeperCorpusAcquisitionPlanCli.parse(
            new String[]{"league-1", "team-1", "2025"});

        assertEquals("league-1", options.anchorLeagueId());
        assertEquals("team-1", options.anchorTeamId());
        assertEquals(2025, options.targetSeason());
    }

    @Test
    void rejectsMissingOrInvalidArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCorpusAcquisitionPlanCli.parse(new String[]{"league-1", "team-1"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCorpusAcquisitionPlanCli.parse(
                new String[]{"league-1", "team-1", "not-a-season"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCorpusAcquisitionPlanCli.parse(
                new String[]{"league-1", "team-1", "1998"}));
    }
}
