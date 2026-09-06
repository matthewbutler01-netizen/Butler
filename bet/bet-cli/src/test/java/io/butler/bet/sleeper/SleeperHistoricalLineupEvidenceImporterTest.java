package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperHistoricalLineupEvidenceImporterTest {
    @TempDir Path tempDir;

    @Test
    void resolvesHistoryPersistsSeasonConfigurationAndWeekRosterEvidence() throws Exception {
        Database database = initializedLeague();
        var source = new FakeSource();
        var importer = new SleeperHistoricalLineupEvidenceImporter(source, database);

        var result = importer.syncWeek("l1", 2025, 1);

        assertEquals("old", result.sleeperLeagueId());
        assertEquals(1, result.historyHops());
        assertEquals(2, result.teamsImported());
        assertEquals(2025, result.season());
        assertEquals(1, result.week());

        var configuration = new LeagueConfigurationObservationRepository(database)
            .findLatestForSeason("l1", 2025, "sleeper").orElseThrow();
        assertEquals(List.of("QB", "RB", "WR", "FLEX"), configuration.lineupSlots());
        assertEquals(4.0, configuration.scoringSettings().get("pass_td"));

        var team1 = new TeamWeekRosterEvidenceRepository(database)
            .findLatest("t1", 2025, 1, "sleeper").orElseThrow();
        assertEquals(List.of("p1", "p2", "p3"), team1.providerPlayerIds());
        assertEquals(List.of("p1", "p2"), team1.providerStarterIds());
        assertEquals(result.asOfDate(), team1.asOfDate());

        var team2 = new TeamWeekRosterEvidenceRepository(database)
            .findLatest("t2", 2025, 1, "sleeper").orElseThrow();
        assertEquals(List.of("p4", "p5"), team2.providerPlayerIds());
        assertEquals(List.of("p4"), team2.providerStarterIds());
    }

    @Test
    void historicalAndCurrentConfigurationCanShareObservationDate() throws Exception {
        Database database = initializedLeague();
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        var configurations = new LeagueConfigurationObservationRepository(database);
        configurations.replace(new io.butler.bet.domain.LeagueConfigurationObservation(
            "l1", "sleeper", today, 2026, List.of("QB", "SUPER_FLEX"), Map.of("pass_td", 6.0)));

        new SleeperHistoricalLineupEvidenceImporter(new FakeSource(), database).syncWeek("l1", 2025, 1);

        assertEquals(List.of("QB", "SUPER_FLEX"),
            configurations.findLatestForSeason("l1", 2026, "sleeper").orElseThrow().lineupSlots());
        assertEquals(List.of("QB", "RB", "WR", "FLEX"),
            configurations.findLatestForSeason("l1", 2025, "sleeper").orElseThrow().lineupSlots());
    }

    @Test
    void mismatchedHistoricalRosterIdentityFailsBeforePersistence() throws Exception {
        Database database = initializedLeague();
        FakeSource source = new FakeSource() {
            @Override
            public List<SleeperJsonParser.SleeperRoster> fetchRosters(String sleeperLeagueId) {
                return List.of(roster(1), roster(3));
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new SleeperHistoricalLineupEvidenceImporter(source, database).syncWeek("l1", 2025, 1));

        assertTrue(error.getMessage().contains("identities do not match"));
        assertTrue(new LeagueConfigurationObservationRepository(database)
            .findLatestForSeason("l1", 2025, "sleeper").isEmpty());
        assertTrue(new TeamWeekRosterEvidenceRepository(database)
            .findLatest("t1", 2025, 1, "sleeper").isEmpty());
    }

    @Test
    void starterOutsidePlayerListFailsClosed() throws Exception {
        Database database = initializedLeague();
        FakeSource source = new FakeSource() {
            @Override
            public List<SleeperMatchupParser.SleeperMatchup> fetchMatchups(String sleeperLeagueId, int week) {
                return List.of(
                    new SleeperMatchupParser.SleeperMatchup(1, List.of("p1"), List.of("not-rostered")),
                    new SleeperMatchupParser.SleeperMatchup(2, List.of("p4"), List.of("p4")));
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new SleeperHistoricalLineupEvidenceImporter(source, database).syncWeek("l1", 2025, 1));
        assertTrue(error.getMessage().contains("starter is not in roster player list"));
    }

    private Database initializedLeague() throws Exception {
        Database database = new Database(tempDir.resolve("historical-lineup.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "current", "Dynasty", 2026));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("t1", "1", "l1", "One"));
        teams.save(new Team("t2", "2", "l1", "Two"));
        return database;
    }

    private static SleeperJsonParser.SleeperRoster roster(int rosterId) {
        return new SleeperJsonParser.SleeperRoster(
            rosterId, "owner-" + rosterId, List.of(), List.of(), List.of(), List.of());
    }

    private static class FakeSource implements SleeperHistoricalLineupEvidenceImporter.HistoricalSource {
        @Override
        public SleeperHistoricalLineupEvidenceImporter.LeagueLink fetchLeagueLink(String sleeperLeagueId) {
            return switch (sleeperLeagueId) {
                case "current" -> new SleeperHistoricalLineupEvidenceImporter.LeagueLink("current", 2026, "old");
                case "old" -> new SleeperHistoricalLineupEvidenceImporter.LeagueLink("old", 2025, null);
                default -> throw new IllegalArgumentException("unexpected league: " + sleeperLeagueId);
            };
        }

        @Override
        public SleeperJsonParser.SleeperLeague fetchLeague(String sleeperLeagueId) {
            return new SleeperJsonParser.SleeperLeague(
                "old", "Historical", List.of("QB", "RB", "WR", "FLEX"),
                2025, 2, 4, Map.of("pass_td", 4.0, "rec", 1.0));
        }

        @Override
        public List<SleeperJsonParser.SleeperRoster> fetchRosters(String sleeperLeagueId) {
            return List.of(roster(1), roster(2));
        }

        @Override
        public List<SleeperMatchupParser.SleeperMatchup> fetchMatchups(String sleeperLeagueId, int week) {
            return List.of(
                new SleeperMatchupParser.SleeperMatchup(
                    1, List.of("p1", "p2", "p3"), List.of("p1", "p2")),
                new SleeperMatchupParser.SleeperMatchup(
                    2, List.of("p4", "p5"), List.of("p4")));
        }
    }
}
