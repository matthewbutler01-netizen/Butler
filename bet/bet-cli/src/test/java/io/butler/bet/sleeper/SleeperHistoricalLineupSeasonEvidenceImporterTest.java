package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperHistoricalLineupSeasonEvidenceImporterTest {
    @TempDir Path tempDir;

    @Test
    void hydratesOnlyObservedWeeksAndFetchesPlayerDatasetOnce() throws Exception {
        Database database = initializedLeague();
        FakeSource source = new FakeSource();
        var importer = new SleeperHistoricalLineupSeasonEvidenceImporter(source, database, 4);

        var result = importer.syncSeason("l1", 2025);

        assertEquals(List.of(1, 3), result.weeksImported());
        assertEquals(4, result.teamWeekSnapshots());
        assertEquals(5, result.newPlayersCreated());
        assertEquals("old", result.sleeperLeagueId());
        assertEquals(1, result.historyHops());
        assertEquals(1, source.playerFetches);

        TeamWeekRosterEvidenceRepository evidence = new TeamWeekRosterEvidenceRepository(database);
        assertTrue(evidence.findLatest("t1", 2025, 1, "sleeper").isPresent());
        assertTrue(evidence.findLatest("t1", 2025, 2, "sleeper").isEmpty());
        assertTrue(evidence.findLatest("t1", 2025, 3, "sleeper").isPresent());
        assertTrue(evidence.findLatest("t1", 2025, 4, "sleeper").isEmpty());
    }

    private Database initializedLeague() throws Exception {
        Database database = new Database(tempDir.resolve("season-hydration.db"));
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

    private static final class FakeSource
        implements SleeperHistoricalLineupEvidenceImporter.HistoricalSource {
        int playerFetches;

        @Override
        public SleeperHistoricalLineupEvidenceImporter.LeagueLink fetchLeagueLink(String sleeperLeagueId) {
            return switch (sleeperLeagueId) {
                case "current" -> new SleeperHistoricalLineupEvidenceImporter.LeagueLink(
                    "current", 2026, "old");
                case "old" -> new SleeperHistoricalLineupEvidenceImporter.LeagueLink(
                    "old", 2025, null);
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
            return switch (week) {
                case 1 -> List.of(
                    new SleeperMatchupParser.SleeperMatchup(
                        1, List.of("p1", "p2", "p3"), List.of("p1", "p2")),
                    new SleeperMatchupParser.SleeperMatchup(
                        2, List.of("p4", "p5"), List.of("p4")));
                case 3 -> List.of(
                    new SleeperMatchupParser.SleeperMatchup(
                        1, List.of("p1", "p2", "p5"), List.of("p1", "p2")),
                    new SleeperMatchupParser.SleeperMatchup(
                        2, List.of("p3", "p4"), List.of("p3")));
                default -> List.of();
            };
        }

        @Override
        public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() {
            playerFetches++;
            Map<String, SleeperJsonParser.SleeperPlayer> players = new LinkedHashMap<>();
            players.put("p1", player("p1", "QB"));
            players.put("p2", player("p2", "RB"));
            players.put("p3", player("p3", "WR"));
            players.put("p4", player("p4", "TE"));
            players.put("p5", player("p5", "WR"));
            return Map.copyOf(players);
        }

        private static SleeperJsonParser.SleeperPlayer player(String id, String position) {
            return new SleeperJsonParser.SleeperPlayer(
                id, "Player " + id, position, "NFL", 25, 3, List.of(position));
        }
    }
}
