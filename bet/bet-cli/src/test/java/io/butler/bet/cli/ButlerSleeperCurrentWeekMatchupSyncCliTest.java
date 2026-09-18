package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerSleeperCurrentWeekMatchupSyncCliTest {

    @Test
    void acceptsExactlyOneButlerLeagueId() {
        assertEquals("league-id",
            ButlerSleeperCurrentWeekMatchupSyncCli.parse(new String[]{" league-id "}));
    }

    @Test
    void resolvesGovernedRuntimeDatabaseWhenConfigured() {
        Path absolute = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("butler-data");
        assertEquals(absolute.resolve("butler.db"),
            ButlerSleeperCurrentWeekMatchupSyncCli.databasePath(absolute.toString()));
        assertEquals(Path.of("butler.db"),
            ButlerSleeperCurrentWeekMatchupSyncCli.databasePath(" "));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentWeekMatchupSyncCli.databasePath("relative-data"));
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
