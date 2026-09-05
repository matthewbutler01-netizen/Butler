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
import io.butler.bet.intelligence.LeagueSeasonLineupCaptureRankingEvidenceAnalyzer;
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

class ButlerLeagueSeasonLineupCaptureRankingEvidenceCliTest {
    @TempDir Path tempDir;

    @Test
    void parsesExactLeagueSeasonRankingCommand() {
        var options = ButlerLeagueSeasonLineupCaptureRankingEvidenceCli.parse(
            new String[]{"league", "season-lineup-capture-ranking-evidence", "l1", "2026"});

        assertEquals("l1", options.leagueId());
        assertEquals(2026, options.season());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueSeasonLineupCaptureRankingEvidenceCli.parse(
                new String[]{"league", "season-lineup-capture-ranking-evidence", "l1"}));
        assertEquals(
            "Usage: butler league season-lineup-capture-ranking-evidence <league-id> <season>",
            error.getMessage());
    }

    @Test
    void rendersGovernedMetricRanksWithRawCommonEvidenceAndNonManagerBoundary() throws Exception {
        Fixture fixture = fixture("ranking-cli.db", 4);
        var report = new LeagueSeasonLineupCaptureRankingEvidenceAnalyzer(fixture.database()).analyze("l1", 2026);

        String output = capture(() -> ButlerLeagueSeasonLineupCaptureRankingEvidenceCli.print(report));

        assertTrue(output.contains("League season lineup-capture ranking evidence"));
        assertTrue(output.contains("Minimum common-week governance floor: 4"));
        assertTrue(output.contains("Common comparable weeks: [1, 2, 3, 4]"));
        assertTrue(output.contains("Ranking state: AVAILABLE"));
        int beta = output.indexOf("lineup-capture rank 1 | Beta Team [tb]");
        int alpha = output.indexOf("lineup-capture rank 2 | Alpha Team [ta]");
        assertTrue(beta >= 0 && alpha > beta);
        assertTrue(output.contains("common lineup-capture rate: 1.000000"));
        assertTrue(output.contains("common lineup-capture percentage: 100.00%"));
        assertTrue(output.contains("common lineup-capture rate: 0.625000"));
        assertTrue(output.contains("common lineup-capture percentage: 62.50%"));
        assertTrue(output.contains("common total started points: 40"));
        assertTrue(output.contains("common total potential points: 64"));
        assertTrue(output.contains("common total potential-minus-started gap: 24"));
        assertTrue(output.contains("not a manager rank, manager-efficiency score"));
        assertTrue(output.contains("four-week floor is a governance threshold, not statistical confidence"));
        assertTrue(output.contains("raw points gap and coverage never break ties"));
        assertTrue(output.contains("no tier, percentile, league average/median benchmark"));
    }

    @Test
    void rendersNoPartialRankingBelowGovernanceFloor() throws Exception {
        Fixture fixture = fixture("ranking-cli-three.db", 3);
        var report = new LeagueSeasonLineupCaptureRankingEvidenceAnalyzer(fixture.database()).analyze("l1", 2026);

        String output = capture(() -> ButlerLeagueSeasonLineupCaptureRankingEvidenceCli.print(report));

        assertTrue(output.contains("Ranking state: UNAVAILABLE_BELOW_MINIMUM_COMMON_WEEKS"));
        assertTrue(output.contains("Governed lineup-capture ranks: unavailable"));
        assertTrue(output.contains("below the v1 minimum of 4"));
        assertTrue(output.contains("No partial ranking is published"));
        assertTrue(output.contains("Alpha Team [ta] | observed=3 | individually-comparable=3 | common=3"));
        assertTrue(output.contains("Beta Team [tb] | observed=3 | individually-comparable=3 | common=3"));
        assertTrue(!output.contains("lineup-capture rank 1 |"));
    }

    private Fixture fixture(String fileName, int weeks) throws Exception {
        Database database = new Database(tempDir.resolve(fileName));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("tb", "2", "l1", "Beta Team"));
        teams.save(new Team("ta", "1", "l1", "Alpha Team"));

        PlayerRepository players = new PlayerRepository(database);
        for (String prefix : List.of("a", "b")) {
            players.save(new Player("p" + prefix + "1", prefix + "1", prefix + " QB", "QB", "CHI"));
            players.save(new Player("p" + prefix + "2", prefix + "2", prefix + " WR2", "WR", "DET"));
            players.save(new Player("p" + prefix + "3", prefix + "3", prefix + " WR3", "WR", "MIN"));
        }
        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            "l1", "sleeper", AS_OF, 2026, List.of("QB", "WR", "BN"),
            Map.of("pass_td", 4.0, "rec_td", 6.0)));

        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        for (String prefix : List.of("a", "b")) {
            eligibility.replace(new PlayerFantasyPositionObservation("p" + prefix + "1", "sleeper", AS_OF, List.of("QB")));
            eligibility.replace(new PlayerFantasyPositionObservation("p" + prefix + "2", "sleeper", AS_OF, List.of("WR")));
            eligibility.replace(new PlayerFantasyPositionObservation("p" + prefix + "3", "sleeper", AS_OF, List.of("WR")));
        }

        TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
        PlayerWeekProductionCoverageRepository coverage = new PlayerWeekProductionCoverageRepository(database);
        PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
        List<String> covered = List.of("pa1", "pa2", "pa3", "pb1", "pb2", "pb3");
        for (int week = 1; week <= weeks; week++) {
            rosters.save(TeamWeekRosterEvidence.create(
                "l1", "ta", 2026, week, List.of("a1", "a2", "a3"), List.of("a1", "a2"), "sleeper", AS_OF));
            rosters.save(TeamWeekRosterEvidence.create(
                "l1", "tb", 2026, week, List.of("b1", "b2", "b3"), List.of("b1", "b2"), "sleeper", AS_OF));
            coverage.replace(new PlayerWeekProductionCoverage(
                2026, week, "nflverse", URI.create("https://example.test/week.csv"), AS_OF,
                100, covered.size(), 0, covered));
            save(production, week, "pa1", 1, 0);
            save(production, week, "pa2", 0, 1);
            save(production, week, "pa3", 0, 2);
            save(production, week, "pb1", 1, 0);
            save(production, week, "pb2", 0, 2);
            save(production, week, "pb3", 0, 1);
        }
        return new Fixture(database);
    }

    private static void save(
        PlayerWeekProductionRepository repository,
        int week,
        String playerId,
        int passingTouchdowns,
        int receivingTouchdowns) throws Exception {
        repository.save(PlayerWeekProduction.create(
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
    private record Fixture(Database database) {}
}
