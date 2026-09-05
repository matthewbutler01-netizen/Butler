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

class LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void auditsTemporalDisjointCorpusWithoutFittingThresholdsOrConfidence() throws Exception {
        Fixture fixture = initializedFixture("calibration-audit.db");
        fixture.saveConfiguration();
        fixture.saveEligibility();
        for (int week = 1; week <= 9; week++) {
            fixture.saveCompleteWeek(week);
            fixture.saveCoverage(week);
            if (week <= 5) fixture.saveBaselineProduction(week);
            else fixture.saveFutureHoldoutProduction(week);
        }

        var report = fixture.analyzer().analyze(2026, 2026);

        assertEquals(LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.POLICY_ID,
            report.policyId());
        assertEquals(5, report.minimumBaselineCommonWeeks());
        assertEquals(4, report.minimumFutureHoldoutCommonWeeks());
        assertEquals(1, report.leaguesWithoutSeason());
        assertEquals(1, report.leagueSeasons().size());
        assertTrue(report.sourceFailures().isEmpty());

        var leagueSeason = report.leagueSeasons().get(0);
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.LeagueSeasonAuditState
                .AVAILABLE_CALIBRATION_CUTOFFS,
            leagueSeason.state());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9),
            leagueSeason.sourceCommonUniverse().commonComparableWeeks());
        assertEquals(8, leagueSeason.cutoffs().size());

        assertEquals(4, leagueSeason.cutoffs().stream().filter(cutoff ->
            cutoff.state()
                == LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffState
                    .EXCLUDED_BASELINE_BELOW_STABILITY_FLOOR).count());
        assertEquals(3, leagueSeason.cutoffs().stream().filter(cutoff ->
            cutoff.state()
                == LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffState
                    .EXCLUDED_HOLDOUT_BELOW_RANKING_FLOOR).count());

        var cutoff = leagueSeason.cutoffs().stream()
            .filter(candidate -> candidate.state()
                == LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffState.AVAILABLE)
            .findFirst().orElseThrow();
        assertEquals(5, cutoff.cutoffAfterWeek());
        assertEquals(List.of(1, 2, 3, 4, 5), cutoff.baselineCommonWeeks());
        assertEquals(List.of(6, 7, 8, 9), cutoff.futureHoldoutCommonWeeks());
        assertEquals(2, cutoff.teams().size());

        var alpha = cutoff.teams().stream().filter(team -> team.teamId().equals("ta")).findFirst().orElseThrow();
        assertEquals(2, alpha.baselineRank());
        assertEquals(new BigDecimal("0.625000"), alpha.baselineLineupCaptureRate());
        assertEquals(0, alpha.baselineMaximumAbsoluteRankMovement());
        assertEquals(5, alpha.baselineRankUnchangedScenarios());
        assertEquals(0, alpha.baselineRankChangedScenarios());
        assertEquals(new BigDecimal("0.000000"), alpha.baselineRankChangeFrequency());
        assertEquals(
            LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass.LOW_SENSITIVITY,
            alpha.baselineSensitivityClass());
        assertEquals(1, alpha.futureHoldoutRank());
        assertEquals(new BigDecimal("1.000000"), alpha.futureHoldoutLineupCaptureRate());
        assertEquals(-1, alpha.signedTemporalRankDisplacement());
        assertEquals(1, alpha.absoluteTemporalRankDisplacement());
        assertFalse(alpha.exactNumericRankRetained());

        var beta = cutoff.teams().stream().filter(team -> team.teamId().equals("tb")).findFirst().orElseThrow();
        assertEquals(1, beta.baselineRank());
        assertEquals(2, beta.futureHoldoutRank());
        assertEquals(1, beta.signedTemporalRankDisplacement());
        assertEquals(1, beta.absoluteTemporalRankDisplacement());

        var summary = report.summary();
        assertEquals(1, summary.requestedLeagueSeasons());
        assertEquals(1, summary.auditedLeagueSeasons());
        assertEquals(0, summary.sourceFailureLeagueSeasons());
        assertEquals(1, summary.availableCutoffs());
        assertEquals(7, summary.excludedCutoffs());
        assertEquals(2, summary.availableTeamCutoffRows());
        assertEquals(1, summary.teamCountDistribution().get(2));
        assertEquals(1, summary.perturbationDenominatorDistribution().get(5));
        assertEquals(2, summary.baselineSensitivityClassCounts().get(
            LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass.LOW_SENSITIVITY));
        assertEquals(1, summary.cutoffStateCounts().get(
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffState.AVAILABLE));

        assertEquals(
            List.of("teamId", "teamName", "baselineRank", "baselineLineupCaptureRate",
                "baselineCommonWeekCount", "baselineMaximumAbsoluteRankMovement",
                "baselineRankUnchangedScenarios", "baselineRankChangedScenarios",
                "baselineRankChangeFrequency", "baselineSensitivityClass", "futureHoldoutRank",
                "futureHoldoutLineupCaptureRate", "futureHoldoutCommonWeekCount",
                "signedTemporalRankDisplacement", "absoluteTemporalRankDisplacement",
                "exactNumericRankRetained"),
            Arrays.stream(LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CalibrationTeamRow.class
                    .getRecordComponents())
                .map(component -> component.getName()).toList());
    }

    @Test
    void rejectsInvalidHistoricalSeasonRange() throws Exception {
        Database database = new Database(tempDir.resolve("bad-range.db"));
        database.initialize();
        var analyzer = new LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer(database);

        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(2027, 2026));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(1998, 2026));
    }

    @Test
    void reportRejectsFabricatedCorpusSummary() throws Exception {
        Fixture fixture = initializedFixture("calibration-invariant.db");
        fixture.saveConfiguration();
        fixture.saveEligibility();
        for (int week = 1; week <= 9; week++) {
            fixture.saveCompleteWeek(week);
            fixture.saveCoverage(week);
            if (week <= 5) fixture.saveBaselineProduction(week);
            else fixture.saveFutureHoldoutProduction(week);
        }
        var report = fixture.analyzer().analyze(2026, 2026);
        var s = report.summary();
        var fabricated = new LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CorpusSummary(
            s.requestedLeagueSeasons(),
            s.auditedLeagueSeasons(),
            s.sourceFailureLeagueSeasons(),
            s.availableCutoffs() + 1,
            s.excludedCutoffs(),
            s.availableTeamCutoffRows(),
            s.teamCountDistribution(),
            s.baselineCommonWeekCountDistribution(),
            s.futureHoldoutCommonWeekCountDistribution(),
            s.perturbationDenominatorDistribution(),
            s.baselineSensitivityClassCounts(),
            s.cutoffStateCounts());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CorpusAuditReport(
                report.policyId(), report.metricScope(), report.auditPolicy(),
                report.minimumBaselineCommonWeeks(), report.minimumFutureHoldoutCommonWeeks(),
                report.requestedStartSeason(), report.requestedEndSeason(), report.leaguesWithoutSeason(),
                report.leagueSeasons(), report.sourceFailures(), fabricated));

        assertEquals("corpus summary must match nested league-season audit evidence", error.getMessage());
    }

    private Fixture initializedFixture(String fileName) throws Exception {
        Database database = new Database(tempDir.resolve(fileName));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        new LeagueRepository(database).save(new League("no-season", "NS", "No Season"));

        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("ta", "1", "l1", "Alpha Team"));
        teams.save(new Team("tb", "2", "l1", "Beta Team"));

        PlayerRepository players = new PlayerRepository(database);
        for (String prefix : List.of("a", "b")) {
            players.save(new Player("p" + prefix + "1", prefix + "1", prefix + " QB", "QB", "CHI"));
            players.save(new Player("p" + prefix + "2", prefix + "2", prefix + " WR2", "WR", "DET"));
            players.save(new Player("p" + prefix + "3", prefix + "3", prefix + " WR3", "WR", "MIN"));
        }
        return new Fixture(database);
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private static final List<String> COVERED_PLAYERS = List.of("pa1", "pa2", "pa3", "pb1", "pb2", "pb3");

    private record Fixture(Database database) {
        LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer analyzer() {
            return new LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer(database);
        }

        void saveConfiguration() throws Exception {
            new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
                "l1", "sleeper", AS_OF, 2026, List.of("QB", "WR", "BN"),
                Map.of("pass_td", 4.0, "rec_td", 6.0)));
        }

        void saveEligibility() throws Exception {
            PlayerFantasyPositionObservationRepository repository =
                new PlayerFantasyPositionObservationRepository(database);
            for (String prefix : List.of("a", "b")) {
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
        }

        void saveCoverage(int week) throws Exception {
            new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
                2026, week, "nflverse", URI.create("https://example.test/stats_player_week_2026.csv"),
                AS_OF, 100, COVERED_PLAYERS.size(), 0, COVERED_PLAYERS));
        }

        void saveBaselineProduction(int week) throws Exception {
            PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
            save(production, week, "pa1", 1, 0);
            save(production, week, "pa2", 0, 1);
            save(production, week, "pa3", 0, 2);
            save(production, week, "pb1", 1, 0);
            save(production, week, "pb2", 0, 2);
            save(production, week, "pb3", 0, 1);
        }

        void saveFutureHoldoutProduction(int week) throws Exception {
            PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
            save(production, week, "pa1", 1, 0);
            save(production, week, "pa2", 0, 2);
            save(production, week, "pa3", 0, 1);
            save(production, week, "pb1", 1, 0);
            save(production, week, "pb2", 0, 1);
            save(production, week, "pb3", 0, 2);
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
