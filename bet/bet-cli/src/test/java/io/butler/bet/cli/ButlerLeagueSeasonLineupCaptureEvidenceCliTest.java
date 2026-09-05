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
import io.butler.bet.intelligence.LeagueSeasonLineupCaptureEvidenceAnalyzer;
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

class ButlerLeagueSeasonLineupCaptureEvidenceCliTest {
    @TempDir Path tempDir;

    @Test
    void parsesExactCommandShape() {
        var options = ButlerLeagueSeasonLineupCaptureEvidenceCli.parse(new String[]{
            "league", "season-lineup-capture-evidence", "l1", "2026"});

        assertEquals("l1", options.leagueId());
        assertEquals(2026, options.season());
    }

    @Test
    void rejectsMalformedArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueSeasonLineupCaptureEvidenceCli.parse(new String[]{
                "league", "season-lineup-capture-evidence", "l1", "bad"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueSeasonLineupCaptureEvidenceCli.parse(new String[]{
                "league", "season-lineup-capture-evidence", "l1"}));
    }

    @Test
    void rendersNeutralTeamOrderSeparateRatesDenominatorsAndNoCrossTeamComparison() throws Exception {
        Database database = new Database(tempDir.resolve("league-season-capture-cli.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("t-beta", "2", "l1", "Beta Team"));
        teams.save(new Team("t-alpha", "1", "l1", "Alpha Team"));

        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "s1", "Quarterback", "QB", "CHI"));
        players.save(new Player("p2", "s2", "Receiver Two", "WR", "DET"));
        players.save(new Player("p3", "s3", "Receiver Three", "WR", "MIN"));
        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            "l1", "sleeper", AS_OF, 2026,
            List.of("QB", "WR", "BN"), Map.of("pass_td", 4.0, "rec_td", 6.0)));
        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        eligibility.replace(new PlayerFantasyPositionObservation("p1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("p2", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("p3", "sleeper", AS_OF, List.of("WR")));

        TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
        rosters.save(TeamWeekRosterEvidence.create(
            "l1", "t-beta", 2026, 1,
            List.of("s1", "s2", "s3"), List.of("s1", "s2"), "sleeper", AS_OF));
        new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
            2026, 1, "nflverse", URI.create("https://example.test/stats_player_week_2026.csv"),
            AS_OF, 50, 3, 0, List.of("p1", "p2", "p3")));
        saveProduction(database, "p1", 1, 1, 0);
        saveProduction(database, "p2", 1, 0, 1);
        saveProduction(database, "p3", 1, 0, 2);

        rosters.save(TeamWeekRosterEvidence.create(
            "l1", "t-beta", 2026, 2,
            List.of("s1", "s2"), List.of("s1", "s2"), "sleeper", AS_OF));

        var report = new LeagueSeasonLineupCaptureEvidenceAnalyzer(database).analyze("l1", 2026);
        String output = capture(() -> ButlerLeagueSeasonLineupCaptureEvidenceCli.print(report));

        int alphaIndex = output.indexOf("Alpha Team [t-alpha]");
        int betaIndex = output.indexOf("Beta Team [t-beta]");
        assertTrue(alphaIndex >= 0 && betaIndex > alphaIndex);
        assertTrue(output.contains("Team order: repository team-name order; never capture-rate-ranked."));

        String alphaOutput = output.substring(alphaIndex, betaIndex);
        assertTrue(alphaOutput.contains("coverage denominator: 0 comparable complete observed week(s) out of 0 observed week(s)"));
        assertTrue(alphaOutput.contains("lineup capture rate state: UNAVAILABLE_NO_COMPARABLE_WEEKS"));
        assertTrue(alphaOutput.contains("lineup capture rate: unavailable"));

        String betaOutput = output.substring(betaIndex);
        assertTrue(betaOutput.contains("observed weeks: 2"));
        assertTrue(betaOutput.contains("comparable complete weeks: 1"));
        assertTrue(betaOutput.contains("blocked weeks: 1"));
        assertTrue(betaOutput.contains("comparable total started points: 10"));
        assertTrue(betaOutput.contains("comparable total potential points: 16"));
        assertTrue(betaOutput.contains("comparable total potential-minus-started gap: 6"));
        assertTrue(betaOutput.contains("coverage denominator: 1 comparable complete observed week(s) out of 2 observed week(s)"));
        assertTrue(betaOutput.contains("lineup capture rate state: AVAILABLE"));
        assertTrue(betaOutput.contains("lineup capture rate: 0.625000"));
        assertTrue(betaOutput.contains("lineup capture percentage: 62.50%"));
        assertTrue(betaOutput.contains("week 2 BLOCKED"));
        assertTrue(betaOutput.contains("No persisted nflverse week production coverage"));

        assertTrue(output.contains("are not ranked by lineup capture"));
        assertTrue(output.contains("does not average team capture rates"));
        assertTrue(output.contains("Each team's coverage denominator remains separate"));
        assertTrue(output.contains("not reconstructed historical startability"));
        assertTrue(output.contains("not manager efficiency"));
        assertTrue(output.contains("rank, tier, recommendation, intent, fault, or skill attribution"));
    }

    private static void saveProduction(
        Database database, String playerId, int week, int passingTouchdowns, int receivingTouchdowns) throws Exception {
        new PlayerWeekProductionRepository(database).save(PlayerWeekProduction.create(
            playerId, 2026, week,
            0, passingTouchdowns, 0,
            0, 0,
            0, 0, receivingTouchdowns,
            0, "nflverse", AS_OF));
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
}
