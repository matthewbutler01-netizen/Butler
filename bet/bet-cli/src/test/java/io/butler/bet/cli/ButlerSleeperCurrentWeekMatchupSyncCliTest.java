package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerSleeperCurrentWeekMatchupSyncCliTest {

    @Test
    void acceptsExactlyOneButlerLeagueId() {
        assertEquals("league-id",
            ButlerSleeperCurrentWeekMatchupSyncCli.parse(new String[]{" league-id "}));
    }

    @Test
    void rejectsMissingOrExtraArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentWeekMatchupSyncCli.parse(null));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentWeekMatchupSyncCli.parse(new String[]{}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentWeekMatchupSyncCli.parse(new String[]{"league", "extra"}));
    }
}
