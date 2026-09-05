package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerWeekProductionCoverageRepository;
import io.butler.bet.data.PlayerWeekProductionRepository;
import io.butler.bet.domain.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NflversePlayerWeekProductionCoverageTest {
    @TempDir Path tempDir;

    @Test
    void persistedWeekCoversCrosswalkResolvedPlayerEvenWhenThatPlayerHasNoStatsRow() throws Exception {
        Database database = initialized();
        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "1001", "Producer", "WR", "CHI"));
        players.save(new Player("p2", "1002", "Bye Player", "RB", "DET"));
        var importer = new NflversePlayerWeekProductionImporter(database);
        LocalDate asOf = LocalDate.of(2026, 1, 20);

        importer.importCsv(2025,
            header() + row("00-0000001", 2025, 3, "REG", 4, 40),
            "gsis_id,sleeper_id\n00-0000001,1001\n00-0000002,1002\n",
            asOf);

        var coverage = new PlayerWeekProductionCoverageRepository(database)
            .findLatest(2025, 3, "nflverse").orElseThrow();
        assertEquals(asOf, coverage.asOfDate());
        assertEquals(1, coverage.providerRows());
        assertEquals(1, coverage.matchedPlayerWeeks());
        assertEquals(0, coverage.unmatchedProviderRows());
        assertTrue(coverage.coversIdentity("p1"));
        assertTrue(coverage.coversIdentity("p2"));
        assertTrue(new PlayerWeekProductionRepository(database)
            .findLatest("p2", 2025, 3, "nflverse").isEmpty());

        // A player introduced after the import must not inherit historical identity coverage.
        players.save(new Player("p3", "1003", "Later Player", "TE", "MIN"));
        assertFalse(new PlayerWeekProductionCoverageRepository(database)
            .findLatest(2025, 3, "nflverse").orElseThrow().coversIdentity("p3"));
    }

    @Test
    void previewNeverAuthorizesMissingRowsAsZero() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "Producer", "WR", "CHI"));
        var importer = new NflversePlayerWeekProductionImporter(database);

        importer.previewCsv(2025,
            header() + row("00-0000001", 2025, 3, "REG", 4, 40),
            "gsis_id,sleeper_id\n00-0000001,1001\n",
            LocalDate.of(2026, 1, 20));

        assertTrue(new PlayerWeekProductionCoverageRepository(database)
            .findLatest(2025, 3, "nflverse").isEmpty());
    }

    @Test
    void sameDayRefreshReplacesIdentityCoverageInsteadOfKeepingStaleMapping() throws Exception {
        Database database = initialized();
        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "1001", "One", "WR", "CHI"));
        players.save(new Player("p2", "1002", "Two", "RB", "DET"));
        var importer = new NflversePlayerWeekProductionImporter(database);
        LocalDate asOf = LocalDate.of(2026, 1, 20);
        String stats = header() + row("00-0000001", 2025, 3, "REG", 4, 40);

        importer.importCsv(2025, stats,
            "gsis_id,sleeper_id\n00-0000001,1001\n00-0000002,1002\n", asOf);
        assertTrue(new PlayerWeekProductionCoverageRepository(database)
            .findLatest(2025, 3, "nflverse").orElseThrow().coversIdentity("p2"));

        importer.importCsv(2025, stats,
            "gsis_id,sleeper_id\n00-0000001,1001\n", asOf);
        var refreshed = new PlayerWeekProductionCoverageRepository(database)
            .findLatest(2025, 3, "nflverse").orElseThrow();
        assertTrue(refreshed.coversIdentity("p1"));
        assertFalse(refreshed.coversIdentity("p2"));
    }

    @Test
    void coverageKeepsWeekSpecificUnmatchedRowCountVisible() throws Exception {
        Database database = initialized();
        new PlayerRepository(database).save(new Player("p1", "1001", "One", "WR", "CHI"));
        var importer = new NflversePlayerWeekProductionImporter(database);

        importer.importCsv(2025,
            header()
                + row("00-0000001", 2025, 3, "REG", 4, 40)
                + row("00-9999999", 2025, 3, "REG", 2, 12),
            "gsis_id,sleeper_id\n00-0000001,1001\n",
            LocalDate.of(2026, 1, 20));

        var coverage = new PlayerWeekProductionCoverageRepository(database)
            .findLatest(2025, 3, "nflverse").orElseThrow();
        assertEquals(2, coverage.providerRows());
        assertEquals(1, coverage.matchedPlayerWeeks());
        assertEquals(1, coverage.unmatchedProviderRows());
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        return database;
    }

    private static String header() {
        return "player_id,season,week,season_type,passing_yards,passing_tds,passing_interceptions,"
            + "rushing_yards,rushing_tds,receptions,receiving_yards,receiving_tds,"
            + "sack_fumbles_lost,rushing_fumbles_lost,receiving_fumbles_lost\n";
    }

    private static String row(String playerId, int season, int week, String seasonType,
                              int receptions, int receivingYards) {
        return playerId + "," + season + "," + week + "," + seasonType
            + ",0,0,0,0,0," + receptions + "," + receivingYards + ",0,0,0,0\n";
    }
}
