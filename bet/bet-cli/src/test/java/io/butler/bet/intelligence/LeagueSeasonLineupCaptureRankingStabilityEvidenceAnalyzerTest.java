package io.butler.bet.intelligence;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void exposesCompleteLeaveOneWeekOutSensitivityWithoutQualitativeStabilityJudgment() throws Exception {
        Fixture fixture = initializedFixture("stability.db");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();
        for (int week = 1; week <= 5; week++) {
            fixture.saveCompleteWeek(week);
            fixture.saveCoverage(week);
            if (week < 5) fixture.saveStandardProduction(week);
            else fixture.saveHighImpactFifthWeek();
        }

        var report = fixture.analyzer().analyze("l1", 2026);

        assertEquals(LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.POLICY_ID, report.policyId());
        assertEquals(LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.METRIC_SCOPE, report.metricScope());
        assertEquals(LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.SENSITIVITY_POLICY,
            report.sensitivityPolicy());
        assertEquals(5, report.minimumCommonWeeksForStability());
        assertEquals(LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.StabilityState.AVAILABLE,
            report.stabilityState());
        assertEquals(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankingState.AVAILABLE,
            report.sourceBaselineRanking().rankingState());
        assertEquals(List.of(1, 2, 3, 4, 5),
            report.sourceBaselineRanking().sourceCommonUniverse().commonComparableWeeks());
        assertEquals(List.of(1, 2, 3, 4, 5), report.scenarios().stream()
            .map(LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.PerturbationScenario::omittedCommonWeek)
            .toList());
        assertTrue(report.scenarios().stream().allMatch(scenario ->
            scenario.state() == LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.ScenarioState.AVAILABLE));
        assertTrue(report.scenarios().stream().allMatch(scenario -> scenario.retainedCommonWeeks().size() == 4));

        var alpha = report.teamSummaries().stream().filter(team -> team.teamId().equals("ta")).findFirst().orElseThrow();
        assertEquals(1, alpha.baselineRank());
        assertEquals(new BigDecimal("0.957447"), alpha.baselineLineupCaptureRate());
        assertEquals(5, alpha.perturbationScenarioCount());
        assertEquals(List.of(1, 3), alpha.distinctPerturbationRanks());
        assertEquals(1, alpha.bestPerturbationRank());
        assertEquals(3, alpha.worstPerturbationRank());
        assertEquals(2, alpha.rankSensitivityRangeWidth());
        assertEquals(2, alpha.maximumAbsoluteRankMovement());
        assertEquals(4, alpha.baselineRankUnchangedScenarios());
        assertEquals(1, alpha.baselineRankChangedScenarios());
        assertEquals(new BigDecimal("0.625000"), alpha.minimumPerturbationRate());
        assertEquals(new BigDecimal("0.967153"), alpha.maximumPerturbationRate());
        assertEquals(new BigDecimal("0.332447"), alpha.maximumAbsoluteRateMovement());
        assertFalse(alpha.rankUnchangedInAllScenarios());

        var omitFive = report.scenarios().get(4);
        var alphaOmitFive = omitFive.teams().stream().filter(team -> team.teamId().equals("ta")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("0.625000"), alphaOmitFive.lineupCaptureRate().orElseThrow());
        assertEquals(3, alphaOmitFive.lineupCaptureRank().orElseThrow());

        assertEquals(
            List.of("policyId", "metricScope", "sensitivityPolicy", "minimumCommonWeeksForStability",
                "sourceBaselineRanking", "stabilityState", "scenarios", "teamSummaries"),
            Arrays.stream(LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.LeagueStabilityReport.class
                    .getRecordComponents())
                .map(component -> component.getName()).toList());
    }

    @Test
    void availableFourWeekBaselineStillWithholdsLeaveOneWeekOutStability() throws Exception {
        Fixture fixture = initializedFixture("stability-four-weeks.db");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();
        for (int week = 1; week <= 4; week++) {
            fixture.saveCompleteWeek(week);
            fixture.saveCoverage(week);
            fixture.saveStandardProduction(week);
        }

        var report = fixture.analyzer().analyze("l1", 2026);

        assertEquals(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankingState.AVAILABLE,
            report.sourceBaselineRanking().rankingState());
        assertEquals(
            LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.StabilityState
                .UNAVAILABLE_INSUFFICIENT_COMMON_WEEKS_FOR_PERTURBATION,
            report.stabilityState());
        assertTrue(report.scenarios().isEmpty());
        assertTrue(report.teamSummaries().isEmpty());
    }

    @Test
    void oneUnavailablePerturbationWithholdsAllTeamStabilitySummariesAndPublishesNoPartialScenarioRanks()
        throws Exception {
        Fixture fixture = initializedFixture("stability-zero-retained-potential.db");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();
        for (int week = 1; week <= 5; week++) {
            fixture.saveCompleteWeek(week);
            fixture.saveCoverage(week);
            if (week < 5) fixture.saveZeroAlphaStandardOthers(week);
            else fixture.saveStandardProduction(week);
        }

        var report = fixture.analyzer().analyze("l1", 2026);

        assertEquals(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankingState.AVAILABLE,
            report.sourceBaselineRanking().rankingState());
        assertEquals(
            LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.StabilityState.UNAVAILABLE_PERTURBATION_TEAM_RATE,
            report.stabilityState());
        assertEquals(5, report.scenarios().size());
        assertTrue(report.teamSummaries().isEmpty());

        var omitFive = report.scenarios().get(4);
        assertEquals(LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.ScenarioState.UNAVAILABLE_TEAM_RATE,
            omitFive.state());
        var alpha = omitFive.teams().stream().filter(team -> team.teamId().equals("ta")).findFirst().orElseThrow();
        assertEquals(
            LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.ScenarioRateState
                .UNAVAILABLE_ZERO_TOTAL_POTENTIAL,
            alpha.rateState());
        assertTrue(alpha.lineupCaptureRate().isEmpty());
        assertTrue(omitFive.teams().stream().allMatch(team -> team.lineupCaptureRank().isEmpty()));
    }

    @Test
    void unavailableBaselineRankingDoesNotAttemptPerturbations() throws Exception {
        Fixture fixture = initializedFixture("stability-three-weeks.db");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();
        for (int week = 1; week <= 3; week++) {
            fixture.saveCompleteWeek(week);
            fixture.saveCoverage(week);
            fixture.saveStandardProduction(week);
        }

        var report = fixture.analyzer().analyze("l1", 2026);

        assertEquals(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankingState.UNAVAILABLE_BELOW_MINIMUM_COMMON_WEEKS,
            report.sourceBaselineRanking().rankingState());
        assertEquals(
            LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.StabilityState.UNAVAILABLE_BASELINE_RANKING,
            report.stabilityState());
        assertTrue(report.scenarios().isEmpty());
        assertTrue(report.teamSummaries().isEmpty());
    }

    @Test
    void reportRejectsFabricatedSensitivitySummary() throws Exception {
        Fixture fixture = initializedFixture("stability-invariant.db");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();
        for (int week = 1; week <= 5; week++) {
            fixture.saveCompleteWeek(week);
            fixture.saveCoverage(week);
            fixture.saveStandardProduction(week);
        }
        var report = fixture.analyzer().analyze("l1", 2026);
        var fabricated = List.of(report.teamSummaries().get(2), report.teamSummaries().get(0), report.teamSummaries().get(1));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.LeagueStabilityReport(
                report.policyId(), report.metricScope(), report.sensitivityPolicy(),
                report.minimumCommonWeeksForStability(), report.sourceBaselineRanking(), report.stabilityState(),
                report.scenarios(), fabricated));

        assertEquals("ranking stability fields must match governed baseline ranking source evidence", error.getMessage());
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
        LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer analyzer() {
            return new LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer(database);
        }

        void saveConfiguration(Map<String, Double> scoring) throws Exception {
            new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
                "l1", "sleeper", AS_OF, 2026, List.of("QB", "WR", "BN"), scoring));
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

        void saveZeroAlphaStandardOthers(int week) throws Exception {
            PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
            save(production, week, "pa1", 0, 0);
            save(production, week, "pa2", 0, 0);
            save(production, week, "pa3", 0, 0);
            for (String prefix : List.of("b", "g")) {
                save(production, week, "p" + prefix + "1", 1, 0);
                save(production, week, "p" + prefix + "2", 0, 2);
                save(production, week, "p" + prefix + "3", 0, 1);
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
