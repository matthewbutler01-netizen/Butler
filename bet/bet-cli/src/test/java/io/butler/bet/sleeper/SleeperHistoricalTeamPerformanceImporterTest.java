package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamSeasonPerformanceRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperHistoricalTeamPerformanceImporterTest {
    @TempDir Path tempDir;

    @Test
    void followsPreviousLeagueChainAndPersistsHistoricalPerformanceOnExistingTeamIds() throws Exception {
        Database database = seededDatabase();
        var importer = new SleeperHistoricalTeamPerformanceImporter(new LinkedHistorySource(), database);

        var result = importer.syncSeason("l1", 2025);

        assertEquals("l1", result.butlerLeagueId());
        assertEquals(2025, result.season());
        assertEquals("L2025", result.sleeperLeagueId());
        assertEquals(2, result.teamsImported());
        assertEquals(1, result.historyHops());
        assertEquals("sleeper", result.source());

        var rows = new TeamSeasonPerformanceRepository(database).findLatestByLeague("l1", 2025, "sleeper");
        assertEquals(2, rows.size());
        var t1 = rows.stream().filter(row -> row.teamId().equals("t1")).findFirst().orElseThrow();
        var t2 = rows.stream().filter(row -> row.teamId().equals("t2")).findFirst().orElseThrow();
        assertEquals(10, t1.wins());
        assertEquals(4, t1.losses());
        assertEquals(1500.25, t1.pointsFor(), 0.000001);
        assertEquals(1400.10, t1.pointsAgainst(), 0.000001);
        assertEquals(4, t2.wins());
        assertEquals(10, t2.losses());
        assertEquals(1200.75, t2.pointsFor(), 0.000001);
    }

    @Test
    void repeatedSameDaySyncUpsertsInsteadOfDuplicatingHistoricalSnapshot() throws Exception {
        Database database = seededDatabase();
        var importer = new SleeperHistoricalTeamPerformanceImporter(new LinkedHistorySource(), database);

        importer.syncSeason("l1", 2025);
        importer.syncSeason("l1", 2025);

        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM team_season_performance WHERE league_id=? AND season=? AND source=?")) {
            statement.setString(1, "l1");
            statement.setInt(2, 2025);
            statement.setString(3, "sleeper");
            try (var rs = statement.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    void rosterIdentityMismatchFailsBeforeAnyWrites() throws Exception {
        Database database = seededDatabase();
        var source = new LinkedHistorySource() {
            @Override
            public List<SleeperJsonParser.SleeperRoster> fetchRosters(String sleeperLeagueId) {
                return List.of(roster(1, 10, 4, 1500.25, 1400.10), roster(3, 4, 10, 1200.75, 1450.50));
            }
        };

        var importer = new SleeperHistoricalTeamPerformanceImporter(source, database);
        var error = assertThrows(IllegalStateException.class, () -> importer.syncSeason("l1", 2025));
        assertTrue(error.getMessage().contains("roster identities do not match"));
        assertTrue(new TeamSeasonPerformanceRepository(database)
            .findLatestByLeague("l1", 2025, "sleeper").isEmpty());
    }

    @Test
    void brokenPreviousLeagueChainFailsClosedWithoutWrites() throws Exception {
        Database database = seededDatabase();
        var source = new LinkedHistorySource() {
            @Override
            public SleeperHistoricalTeamPerformanceImporter.LeagueLink fetchLeague(String sleeperLeagueId) {
                return new SleeperHistoricalTeamPerformanceImporter.LeagueLink("L2026", 2026, null);
            }
        };

        var importer = new SleeperHistoricalTeamPerformanceImporter(source, database);
        var error = assertThrows(IllegalStateException.class, () -> importer.syncSeason("l1", 2025));
        assertTrue(error.getMessage().contains("history ended"));
        assertTrue(new TeamSeasonPerformanceRepository(database)
            .findLatestByLeague("l1", 2025, "sleeper").isEmpty());
    }

    @Test
    void previousLeagueCycleFailsClosedWithoutWrites() throws Exception {
        Database database = seededDatabase();
        var source = new LinkedHistorySource() {
            @Override
            public SleeperHistoricalTeamPerformanceImporter.LeagueLink fetchLeague(String sleeperLeagueId) {
                return switch (sleeperLeagueId) {
                    case "L2026" -> new SleeperHistoricalTeamPerformanceImporter.LeagueLink("L2026", 2026, "L2025X");
                    case "L2025X" -> new SleeperHistoricalTeamPerformanceImporter.LeagueLink("L2025X", 2026, "L2026");
                    default -> throw new IllegalArgumentException("unexpected league " + sleeperLeagueId);
                };
            }
        };

        var importer = new SleeperHistoricalTeamPerformanceImporter(source, database);
        var error = assertThrows(IllegalStateException.class, () -> importer.syncSeason("l1", 2025));
        assertTrue(error.getMessage().contains("cycle"));
        assertTrue(new TeamSeasonPerformanceRepository(database)
            .findLatestByLeague("l1", 2025, "sleeper").isEmpty());
    }

    private Database seededDatabase() throws Exception {
        Database database = new Database(tempDir.resolve("historical-performance.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L2026", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "Team One"));
        new TeamRepository(database).save(new Team("t2", "2", "l1", "Team Two"));
        return database;
    }

    private static SleeperJsonParser.SleeperRoster roster(
        int rosterId, int wins, int losses, double pointsFor, double pointsAgainst) {
        return new SleeperJsonParser.SleeperRoster(
            rosterId, "u" + rosterId, List.of(), List.of(), List.of(), List.of(),
            wins, losses, 0, pointsFor, pointsAgainst);
    }

    private static class LinkedHistorySource implements SleeperHistoricalTeamPerformanceImporter.HistoricalSource {
        @Override
        public SleeperHistoricalTeamPerformanceImporter.LeagueLink fetchLeague(String sleeperLeagueId) {
            return switch (sleeperLeagueId) {
                case "L2026" -> new SleeperHistoricalTeamPerformanceImporter.LeagueLink("L2026", 2026, "L2025");
                case "L2025" -> new SleeperHistoricalTeamPerformanceImporter.LeagueLink("L2025", 2025, "L2024");
                default -> throw new IllegalArgumentException("unexpected league " + sleeperLeagueId);
            };
        }

        @Override
        public List<SleeperJsonParser.SleeperRoster> fetchRosters(String sleeperLeagueId) {
            if (!"L2025".equals(sleeperLeagueId)) throw new IllegalArgumentException("unexpected rosters " + sleeperLeagueId);
            return List.of(
                roster(1, 10, 4, 1500.25, 1400.10),
                roster(2, 4, 10, 1200.75, 1450.50));
        }
    }
}
