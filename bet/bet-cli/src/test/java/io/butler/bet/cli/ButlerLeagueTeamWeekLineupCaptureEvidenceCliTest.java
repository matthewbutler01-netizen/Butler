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
import io.butler.bet.intelligence.LeagueTeamWeekLineupCaptureEvidenceAnalyzer;
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

class ButlerLeagueTeamWeekLineupCaptureEvidenceCliTest {
    @TempDir Path tempDir;

    @Test
    void parsesGovernedTeamWeekCaptureCommand() {
        var options = ButlerLeagueTeamWeekLineupCaptureEvidenceCli.parse(new String[] {
            "league", "team-week-lineup-capture-evidence", "l1", "t1", "2026", "3"
        });

        assertEquals("l1", options.leagueId());
        assertEquals("t1", options.teamId());
        assertEquals(2026, options.season());
        assertEquals(3, options.week());
    }

    @Test
    void rejectsIncompleteCommand() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueTeamWeekLineupCaptureEvidenceCli.parse(new String[] {
                "league", "team-week-lineup-capture-evidence", "l1"
            }));

        assertTrue(error.getMessage().contains(
            "team-week-lineup-capture-evidence <league-id> <team-id> <season> <week>"));
    }

    @Test
    void rendersAvailableRateRawEvidenceAndNonAttributionBoundary() throws Exception {
        Database database = fixture(false);
        var report = new LeagueTeamWeekLineupCaptureEvidenceAnalyzer(database)
            .analyze("l1", "t1", 2026, 3);

        String output = capture(() -> ButlerLeagueTeamWeekLineupCaptureEvidenceCli.print(report));

        assertTrue(output.contains("Team-week lineup capture evidence"));
        assertTrue(output.contains("Recalculated started points: 10"));
        assertTrue(output.contains("Retrospective potential points: 16"));
        assertTrue(output.contains("Potential-minus-started points gap: 6"));
        assertTrue(output.contains("Lineup capture rate state: AVAILABLE"));
        assertTrue(output.contains("Lineup capture rate: 0.625000"));
        assertTrue(output.contains("Lineup capture percentage: 62.50%"));
        assertTrue(output.contains("does not reconstruct historical startability"));
        assertTrue(output.contains("It is not manager efficiency"));
        assertTrue(output.contains("rank, tier, recommendation, intent, fault, or skill attribution"));
    }

    @Test
    void rendersZeroPotentialAsUnavailableWithoutFabricatedPercentage() throws Exception {
        Database database = fixture(true);
        var report = new LeagueTeamWeekLineupCaptureEvidenceAnalyzer(database)
            .analyze("l1", "t1", 2026, 3);

        String output = capture(() -> ButlerLeagueTeamWeekLineupCaptureEvidenceCli.print(report));

        assertTrue(output.contains("Lineup capture rate state: UNAVAILABLE_ZERO_POTENTIAL"));
        assertTrue(output.contains("Lineup capture rate: unavailable (retrospective potential points are zero)"));
        assertTrue(!output.contains("Lineup capture percentage:"));
    }

    private Database fixture(boolean zeroProduction) throws Exception {
        Database database = new Database(tempDir.resolve(zeroProduction ? "capture-zero.db" : "capture.db"));
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
        new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
            "l1", "t1", 2026, 3, List.of("s1", "s2", "s3"), List.of("s1", "s2"), "sleeper", AS_OF));

        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        eligibility.replace(new PlayerFantasyPositionObservation("p1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("p2", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("p3", "sleeper", AS_OF, List.of("WR")));

        new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
            2026, 3, "nflverse", URI.create("https://example.test/week.csv"), AS_OF,
            50, 3, 0, List.of("p1", "p2", "p3")));
        PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
        production.save(PlayerWeekProduction.create(
            "p1", 2026, 3,
            0, zeroProduction ? 0 : 1, 0,
            0, 0,
            0, 0, 0,
            0, "nflverse", AS_OF));
        production.save(PlayerWeekProduction.create(
            "p2", 2026, 3,
            0, 0, 0,
            0, 0,
            0, 0, zeroProduction ? 0 : 1,
            0, "nflverse", AS_OF));
        production.save(PlayerWeekProduction.create(
            "p3", 2026, 3,
            0, 0, 0,
            0, 0,
            0, 0, zeroProduction ? 0 : 2,
            0, "nflverse", AS_OF));
        return database;
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
