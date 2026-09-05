package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerFantasyPositionObservationRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerWeekProductionCoverageRepository;
import io.butler.bet.data.PlayerWeekProductionRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueConfigurationObservation;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerFantasyPositionObservation;
import io.butler.bet.domain.PlayerWeekProduction;
import io.butler.bet.domain.PlayerWeekProductionCoverage;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamWeekRosterEvidence;
import io.butler.bet.intelligence.LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueSeasonLineupCaptureCommonUniverseEvidenceCliTest {
    @TempDir Path tempDir;

    @Test
    void parsesExactCommonUniverseCommand() {
        var options = ButlerLeagueSeasonLineupCaptureCommonUniverseEvidenceCli.parse(
            new String[] {"league", "season-lineup-capture-common-universe-evidence", "l1", "2026"});

        assertEquals("l1", options.leagueId());
        assertEquals(2026, options.season());
    }

    @Test
    void rejectsWrongShapeWithExactUsage() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueSeasonLineupCaptureCommonUniverseEvidenceCli.parse(
                new String[] {"league", "season-lineup-capture-common-universe-evidence", "l1"}));

        assertEquals(
            "Usage: butler league season-lineup-capture-common-universe-evidence <league-id> <season>",
            error.getMessage());
    }

    @Test
    void rendersNeutralSameWeekTableWithExcludedIndividualCoverageAndNoRankingArithmetic() throws Exception {
        Database database = fixture("cli-common.db");
        saveWeek(database, 1, List.of("a1", "a2"), List.of("b1", "b2"));
        saveCoverage(database, 1);
        saveProduction(database, 1,
            new Production("pa1", 1, 0), new Production("pa2", 0, 1), new Production("pa3", 0, 2),
            new Production("pb1", 2, 0), new Production("pb2", 0, 1), new Production("pb3", 0, 2));

        saveWeek(database, 2, List.of("a1", "a2"), List.of("b1", "0"));
        saveCoverage(database, 2);
        saveProduction(database, 2,
            new Production("pa1", 1, 0), new Production("pa2", 0, 1), new Production("pa3", 0, 2),
            new Production("pb1", 2, 0), new Production("pb2", 0, 1), new Production("pb3", 0, 2));

        var report = new LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer(database).analyze("l1", 2026);
        String output = capture(() -> ButlerLeagueSeasonLineupCaptureCommonUniverseEvidenceCli.print(report));

        assertTrue(output.contains("League common-universe lineup capture evidence"));
        assertTrue(output.contains("Repository team count: 2"));
        assertTrue(output.contains("Common-universe state: AVAILABLE"));
        assertTrue(output.contains("Common comparable weeks: [1]"));
        assertTrue(output.contains("Common denominator: 1 common comparable observed week(s) across all 2 repository team(s)"));
        assertTrue(output.contains("Team order: repository team-name order; never capture-rate-ranked."));
        assertTrue(output.contains(
            "Team | observed | individually comparable | excluded comparable | common | started | potential | gap | capture"));

        String alpha = "Alpha Team [ta] | 2 | 2 | [2] | 1 | 10 | 16 | 6 | 0.625000 (62.50%)";
        String beta = "Beta Team [tb] | 2 | 1 | none | 1 | 14 | 20 | 6 | 0.700000 (70.00%)";
        assertTrue(output.contains(alpha));
        assertTrue(output.contains(beta));
        assertTrue(output.indexOf(alpha) < output.indexOf(beta));

        assertTrue(output.contains("every normalized row uses the same all-repository-team common comparable week set"));
        assertTrue(output.contains("does not drop a low-coverage team to widen that universe"));
        assertTrue(output.contains("does not fall back to independently scoped season rates"));
        assertTrue(output.contains("computes no rank, tier, percentile, winner, league average or median"));
        assertTrue(output.contains("pairwise matrix, or manager score"));
        assertTrue(output.contains("not reconstructed historical startability"));
        assertTrue(output.contains("not manager efficiency, manager quality, skill"));
    }

    @Test
    void rendersUnavailableInsteadOfFallingBackWhenNoAllTeamCommonWeekExists() throws Exception {
        Database database = fixture("cli-none.db");
        saveWeek(database, 1, List.of("a1", "a2"), List.of("b1", "0"));
        saveCoverage(database, 1);
        saveProduction(database, 1,
            new Production("pa1", 1, 0), new Production("pa2", 0, 1), new Production("pa3", 0, 2),
            new Production("pb1", 2, 0), new Production("pb2", 0, 1), new Production("pb3", 0, 2));

        var report = new LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer(database).analyze("l1", 2026);
        String output = capture(() -> ButlerLeagueSeasonLineupCaptureCommonUniverseEvidenceCli.print(report));

        assertTrue(output.contains("Common-universe state: UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS"));
        assertTrue(output.contains("Common comparable weeks: none"));
        assertTrue(output.contains(
            "Common-universe availability: unavailable (no comparable complete week is shared by every repository team)"));
        assertTrue(output.contains("Alpha Team [ta] | 1 | 1 | [1] | 0 | unavailable | unavailable | unavailable | unavailable"));
        assertTrue(output.contains("Beta Team [tb] | 1 | 0 | none | 0 | unavailable | unavailable | unavailable | unavailable"));
    }

    private Database fixture(String fileName) throws Exception {
        Database database = new Database(tempDir.resolve(fileName));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("tb", "2", "l1", "Beta Team"));
        teams.save(new Team("ta", "1", "l1", "Alpha Team"));

        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("pa1", "a1", "A Quarterback", "QB", "CHI"));
        players.save(new Player("pa2", "a2", "A Receiver Two", "WR", "DET"));
        players.save(new Player("pa3", "a3", "A Receiver Three", "WR", "MIN"));
        players.save(new Player("pb1", "b1", "B Quarterback", "QB", "GB"));
        players.save(new Player("pb2", "b2", "B Receiver Two", "WR", "SEA"));
        players.save(new Player("pb3", "b3", "B Receiver Three", "WR", "LAR"));

        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            "l1", "sleeper", AS_OF, 2026,
            List.of("QB", "WR", "BN"), Map.of("pass_td", 4.0, "rec_td", 6.0)));
        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        eligibility.replace(new PlayerFantasyPositionObservation("pa1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("pa2", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("pa3", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("pb1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("pb2", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("pb3", "sleeper", AS_OF, List.of("WR")));
        return database;
    }

    private static void saveWeek(
        Database database,
        int week,
        List<String> aStarters,
        List<String> bStarters) throws Exception {
        TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
        rosters.save(TeamWeekRosterEvidence.create(
            "l1", "ta", 2026, week, List.of("a1", "a2", "a3"), aStarters, "sleeper", AS_OF));
        rosters.save(TeamWeekRosterEvidence.create(
            "l1", "tb", 2026, week, List.of("b1", "b2", "b3"), bStarters, "sleeper", AS_OF));
    }

    private static void saveCoverage(Database database, int week) throws Exception {
        new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
            2026, week, "nflverse", URI.create("https://example.test/stats_player_week_2026.csv"),
            AS_OF, 100, COVERED_PLAYERS.size(), 0, COVERED_PLAYERS));
    }

    private static void saveProduction(Database database, int week, Production... productions) throws Exception {
        PlayerWeekProductionRepository repository = new PlayerWeekProductionRepository(database);
        for (Production production : productions) {
            repository.save(PlayerWeekProduction.create(
                production.playerId(), 2026, week,
                0, production.passingTouchdowns(), 0,
                0, 0,
                0, 0, production.receivingTouchdowns(),
                0, "nflverse", AS_OF));
        }
    }

    private static String capture(Runnable runnable) {
        PrintStream previous = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            runnable.run();
            return bytes.toString();
        } finally {
            System.setOut(previous);
        }
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private static final List<String> COVERED_PLAYERS =
        List.of("pa1", "pa2", "pa3", "pb1", "pb2", "pb3");

    private record Production(String playerId, int passingTouchdowns, int receivingTouchdowns) {}
}
