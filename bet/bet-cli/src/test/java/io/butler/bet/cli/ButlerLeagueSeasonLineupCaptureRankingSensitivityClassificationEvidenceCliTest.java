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
import io.butler.bet.intelligence.LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer;
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

class ButlerLeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceCliTest {
    @TempDir Path tempDir;

    @Test
    void parsesExactRankingSensitivityClassificationCommand() {
        var options = ButlerLeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceCli.parse(
            new String[] {"league", "season-lineup-capture-ranking-sensitivity-classification-evidence", "l1", "2026"});

        assertEquals("l1", options.leagueId());
        assertEquals(2026, options.season());
        assertTrue(ButlerLeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceCli.isCommand(
            new String[] {"LEAGUE", "SEASON-LINEUP-CAPTURE-RANKING-SENSITIVITY-CLASSIFICATION-EVIDENCE"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceCli.parse(
                new String[] {"league", "season-lineup-capture-ranking-sensitivity-classification-evidence", "l1"}));
    }

    @Test
    void rendersObservedClassesWithMovementEvidenceAndNoManagerConfidenceLanguage() throws Exception {
        Fixture fixture = initializedFixture("classification-cli.db");
        fixture.saveConfiguration();
        fixture.saveEligibility();
        for (int week = 1; week <= 5; week++) {
            fixture.saveCompleteWeek(week);
            fixture.saveCoverage(week);
            if (week < 5) fixture.saveStandardProduction(week);
            else fixture.saveHighImpactFifthWeek();
        }

        var report = fixture.analyzer().analyze("l1", 2026);
        String output = capture(() ->
            ButlerLeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceCli.print(report));

        assertTrue(output.contains("League season lineup-capture ranking sensitivity classification evidence"));
        assertTrue(output.contains("Classification state: AVAILABLE"));
        assertTrue(output.contains(
            "Rule: max absolute rank movement 0 = LOW_SENSITIVITY; 1 = MODERATE_SENSITIVITY; 2+ = HIGH_SENSITIVITY"));
        assertTrue(output.contains("Alpha Team [ta]"));
        assertTrue(output.contains("baseline lineup-capture rank: 1"));
        assertTrue(output.contains("maximum absolute rank movement: 2"));
        assertTrue(output.contains("observed sensitivity class: HIGH_SENSITIVITY"));
        assertTrue(output.contains("Beta Team [tb]"));
        assertTrue(output.contains("observed sensitivity class: MODERATE_SENSITIVITY"));
        assertTrue(output.contains("baseline-rank changed scenarios: 1"));
        assertTrue(output.contains("not manager stability, manager consistency, manager reliability, manager quality"));
        assertTrue(output.contains("not authorized for cross-league comparison"));
    }

    @Test
    void unavailableSourceStabilityRendersNoPartialClassification() throws Exception {
        Fixture fixture = initializedFixture("classification-cli-unavailable.db");
        fixture.saveConfiguration();
        fixture.saveEligibility();
        for (int week = 1; week <= 4; week++) {
            fixture.saveCompleteWeek(week);
            fixture.saveCoverage(week);
            fixture.saveStandardProduction(week);
        }

        var report = fixture.analyzer().analyze("l1", 2026);
        String output = capture(() ->
            ButlerLeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceCli.print(report));

        assertTrue(output.contains("Classification state: UNAVAILABLE_SOURCE_STABILITY"));
        assertTrue(output.contains("source stability does not meet the five-common-week perturbation floor"));
        assertTrue(output.contains("No partial sensitivity classification is published."));
        assertTrue(!output.contains("observed sensitivity class:"));
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

    private Fixture initializedFixture(String fileName) throws Exception {
        Database database = new Database(tempDir.resolve(fileName));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("tg", "3", "l1", "Gamma Team"));
        teams.save(new Team("ta", "1", "l1", "Alpha Team"));
        teams.save(new Team("tb", "2", "l1", "Beta Team"));

        PlayerRepository players = new PlayerRepository(database);
        for (String prefix : List.of("a", "b", "g")) {
            players.save(new Player("p" + prefix + "1", prefix + "1", prefix + " QB", "QB", "CHI"));
            players.save(new Player("p" + prefix + "2", prefix + "2", prefix + " WR2", "WR", "DET"));
            players.save(new Player("p" + prefix + "3", prefix + "3", prefix + " WR3", "WR", "MIN"));
        }
        return new Fixture(database);
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private static final List<String> COVERED_PLAYERS = List.of(
        "pa1", "pa2", "pa3", "pb1", "pb2", "pb3", "pg1", "pg2", "pg3");

    private record Fixture(Database database) {
        LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer analyzer() {
            return new LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer(database);
        }

        void saveConfiguration() throws Exception {
            new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
                "l1", "sleeper", AS_OF, 2026, List.of("QB", "WR", "BN"),
                Map.of("pass_td", 4.0, "rec_td", 6.0)));
        }

        void saveEligibility() throws Exception {
            PlayerFantasyPositionObservationRepository repository =
                new PlayerFantasyPositionObservationRepository(database);
            for (String prefix : List.of("a", "b", "g")) {
                repository.replace(new PlayerFantasyPositionObservation(
                    "p" + prefix + "1", "sleeper", AS_OF, List.of("QB")));
                repository.replace(new PlayerFantasyPositionObservation(
                    "p" + prefix + "2", "sleeper", AS_OF, List.of("WR")));
                repository.replace(new PlayerFantasyPositionObservation(
                    "p" + prefix + "3", "sleeper", AS_OF, List.of("WR")));
            }
        }

        void saveCompleteWeek(int week) throws Exception {
            TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
            rosters.save(TeamWeekRosterEvidence.create(
                "l1", "ta", 2026, week, List.of("a1", "a2", "a3"), List.of("a1", "a2"),
                "sleeper", AS_OF));
            rosters.save(TeamWeekRosterEvidence.create(
                "l1", "tb", 2026, week, List.of("b1", "b2", "b3"), List.of("b1", "b2"),
                "sleeper", AS_OF));
            rosters.save(TeamWeekRosterEvidence.create(
                "l1", "tg", 2026, week, List.of("g1", "g2", "g3"), List.of("g1", "g2"),
                "sleeper", AS_OF));
        }

        void saveCoverage(int week) throws Exception {
            new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
                2026, week, "nflverse", URI.create("https://example.test/stats_player_week_2026.csv"),
                AS_OF, 100, COVERED_PLAYERS.size(), 0, COVERED_PLAYERS));
        }

        void saveStandardProduction(int week) throws Exception {
            PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
            save(production, week, "pa1", 1, 0);
            save(production, week, "pa2", 0, 1);
            save(production, week, "pa3", 0, 2);
            for (String prefix : List.of("b", "g")) {
                save(production, week, "p" + prefix + "1", 1, 0);
                save(production, week, "p" + prefix + "2", 0, 2);
                save(production, week, "p" + prefix + "3", 0, 1);
            }
        }

        void saveHighImpactFifthWeek() throws Exception {
            PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
            save(production, 5, "pa1", 50, 0);
            save(production, 5, "pa2", 0, 50);
            save(production, 5, "pa3", 0, 49);
            for (String prefix : List.of("b", "g")) {
                save(production, 5, "p" + prefix + "1", 0, 0);
                save(production, 5, "p" + prefix + "2", 0, 0);
                save(production, 5, "p" + prefix + "3", 0, 1);
            }
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
    }
}
