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
import io.butler.bet.intelligence.LeagueTeamSeasonLineupCaptureEvidenceAnalyzer;
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

class ButlerLeagueTeamSeasonLineupCaptureEvidenceCliTest {
    @TempDir Path tempDir;

    @Test
    void parsesGovernedTeamSeasonCaptureCommand() {
        var options = ButlerLeagueTeamSeasonLineupCaptureEvidenceCli.parse(new String[] {
            "league", "team-season-lineup-capture-evidence", "l1", "t1", "2026"
        });

        assertEquals("l1", options.leagueId());
        assertEquals("t1", options.teamId());
        assertEquals(2026, options.season());
    }

    @Test
    void rejectsIncompleteCommand() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueTeamSeasonLineupCaptureEvidenceCli.parse(new String[] {
                "league", "team-season-lineup-capture-evidence", "l1"
            }));

        assertTrue(error.getMessage().contains(
            "team-season-lineup-capture-evidence <league-id> <team-id> <season>"));
    }

    @Test
    void rendersAllWeekStatesCoverageTotalsAndTotalRatioCapture() throws Exception {
        Database database = fixture();
        var report = new LeagueTeamSeasonLineupCaptureEvidenceAnalyzer(database)
            .analyze("l1", "t1", 2026);

        String output = capture(() -> ButlerLeagueTeamSeasonLineupCaptureEvidenceCli.print(report));

        assertTrue(output.contains("Team-season lineup capture evidence"));
        assertTrue(output.contains("Week 1 | COMPARABLE_COMPLETE"));
        assertTrue(output.contains("Week 2 | COMPARABLE_COMPLETE"));
        assertTrue(output.contains("Week 3 | POTENTIAL_INCOMPLETE"));
        assertTrue(output.contains("Week 4 | STARTED_INCOMPLETE"));
        assertTrue(output.contains("Week 5 | BLOCKED"));
        assertTrue(output.contains("observed weeks: 5"));
        assertTrue(output.contains("comparable complete weeks: 2"));
        assertTrue(output.contains("potential-incomplete weeks: 1"));
        assertTrue(output.contains("started-incomplete weeks: 1"));
        assertTrue(output.contains("blocked weeks: 1"));
        assertTrue(output.contains("comparable total started points: 30"));
        assertTrue(output.contains("comparable total potential points: 36"));
        assertTrue(output.contains("comparable total potential-minus-started gap: 6"));
        assertTrue(output.contains("coverage denominator: 2 comparable complete observed week(s) out of 5 observed week(s)"));
        assertTrue(output.contains("Lineup capture rate state: AVAILABLE"));
        assertTrue(output.contains("Lineup capture rate: 0.833333"));
        assertTrue(output.contains("Lineup capture percentage: 83.33%"));
        assertTrue(output.contains("it is not an average of weekly percentages"));
        assertTrue(output.contains("Coverage remains a separate explicit denominator"));
        assertTrue(output.contains("not reconstructed historical startability"));
        assertTrue(output.contains("This is not manager efficiency"));
        assertTrue(output.contains("rank, tier, recommendation, intent, fault, or skill attribution"));
    }

    private Database fixture() throws Exception {
        Database database = new Database(tempDir.resolve("season-capture-cli.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "Team One"));
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

        saveRoster(database, 1, List.of("s1", "s2", "s3"), List.of("s1", "s2"));
        saveCoverage(database, 1, List.of("p1", "p2", "p3"));
        saveProduction(database, "p1", 1, 1, 0);
        saveProduction(database, "p2", 1, 0, 1);
        saveProduction(database, "p3", 1, 0, 2);

        saveRoster(database, 2, List.of("s1", "s2", "s3"), List.of("s1", "s3"));
        saveCoverage(database, 2, List.of("p1", "p2", "p3"));
        saveProduction(database, "p1", 2, 2, 0);
        saveProduction(database, "p2", 2, 0, 1);
        saveProduction(database, "p3", 2, 0, 2);

        saveRoster(database, 3, List.of("s1"), List.of("s1", "0"));
        saveCoverage(database, 3, List.of("p1"));
        saveProduction(database, "p1", 3, 1, 0);

        saveRoster(database, 4, List.of("s1", "s2"), List.of("s1", "0"));
        saveCoverage(database, 4, List.of("p1", "p2"));
        saveProduction(database, "p1", 4, 1, 0);
        saveProduction(database, "p2", 4, 0, 1);

        saveRoster(database, 5, List.of("s1", "s2"), List.of("s1", "s2"));
        return database;
    }

    private static void saveRoster(Database database, int week, List<String> roster, List<String> starters)
        throws Exception {
        new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
            "l1", "t1", 2026, week, roster, starters, "sleeper", AS_OF));
    }

    private static void saveCoverage(Database database, int week, List<String> covered) throws Exception {
        new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
            2026, week, "nflverse", URI.create("https://example.test/stats_player_week_2026.csv"),
            AS_OF, 50, covered.size(), 0, covered));
    }

    private static void saveProduction(
        Database database,
        String playerId,
        int week,
        int passingTouchdowns,
        int receivingTouchdowns) throws Exception {
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
