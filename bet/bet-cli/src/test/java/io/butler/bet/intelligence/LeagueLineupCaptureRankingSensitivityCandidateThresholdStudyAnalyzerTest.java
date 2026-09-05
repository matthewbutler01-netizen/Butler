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

import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void structurallyReadyCorpusProducesLeakageSafeObservedCandidateFoldsWithoutSelectingWinner() throws Exception {
        Fixture mixed = fixture("mixed.db", "l1", "League One", 2025, List.of("a", "b"));
        mixed.saveConfiguration();
        mixed.saveEligibility();
        for (int week = 1; week <= 9; week++) {
            mixed.saveCompleteWeek(week);
            mixed.saveCoverage(week);
            if (week <= 5) {
                mixed.saveProduction(week, week % 2 == 1 ? ProductionMode.BASELINE_ORDER : ProductionMode.REVERSED_ORDER);
            } else {
                mixed.saveProduction(week, ProductionMode.REVERSED_ORDER);
            }
        }

        Fixture retained = fixture("retained.db", "l2", "League Two", 2026, List.of("c", "d", "e"));
        retained.saveConfiguration();
        retained.saveEligibility();
        for (int week = 1; week <= 10; week++) {
            retained.saveCompleteWeek(week);
            retained.saveCoverage(week);
            retained.saveProduction(week, ProductionMode.BASELINE_ORDER);
        }

        var combined = combine(
            mixed.corpusAnalyzer().analyze(2025, 2025),
            retained.corpusAnalyzer().analyze(2026, 2026),
            2025,
            2026);
        var readiness = LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.fromSource(combined);
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.ReadinessState
                .READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN,
            readiness.readinessState());

        var report = LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.fromSource(readiness);

        assertEquals(LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.POLICY_ID, report.policyId());
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.StudyState.AVAILABLE,
            report.studyState());
        assertEquals(2, report.folds().size());

        var mixedHeldOut = report.folds().stream()
            .filter(fold -> fold.heldOutLeagueSeason().equals("l1:2025"))
            .findFirst().orElseThrow();
        assertEquals(1, mixedHeldOut.developmentClusterCount());
        assertEquals(1, mixedHeldOut.frequencyEvaluations().size());
        assertEquals(new LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FrequencyCandidate(0, 1),
            mixedHeldOut.frequencyEvaluations().get(0).candidate());
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateFoldState.EVALUABLE,
            mixedHeldOut.frequencyEvaluations().get(0).state());
        assertEquals(1, mixedHeldOut.frequencyEvaluations().get(0).meetsRule().rows());
        assertEquals(1, mixedHeldOut.frequencyEvaluations().get(0).doesNotMeetRule().rows());
        assertEquals(1, mixedHeldOut.magnitudeEvaluations().size());
        assertEquals(0, mixedHeldOut.magnitudeEvaluations().get(0).candidate().maximumMovementCutoff());
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateFoldState.EVALUABLE,
            mixedHeldOut.magnitudeEvaluations().get(0).state());

        var retainedHeldOut = report.folds().stream()
            .filter(fold -> fold.heldOutLeagueSeason().equals("l2:2026"))
            .findFirst().orElseThrow();
        assertTrue(retainedHeldOut.frequencyEvaluations().stream().anyMatch(evaluation ->
            evaluation.candidate().equals(
                new LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FrequencyCandidate(3, 5))));
        assertTrue(retainedHeldOut.magnitudeEvaluations().stream().anyMatch(evaluation ->
            evaluation.candidate().maximumMovementCutoff() == 1));

        var threeFifths = report.frequencyCandidates().stream()
            .filter(summary -> summary.candidate().equals(
                new LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FrequencyCandidate(3, 5)))
            .findFirst().orElseThrow();
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateFoldState
                .NOT_GENERATED_IN_DEVELOPMENT_FOLD,
            threeFifths.folds().stream()
                .filter(outcome -> outcome.heldOutLeagueSeason().equals("l1:2025"))
                .findFirst().orElseThrow().state());
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateFoldState
                .UNEVALUABLE_NO_HELD_OUT_SPLIT,
            threeFifths.folds().stream()
                .filter(outcome -> outcome.heldOutLeagueSeason().equals("l2:2026"))
                .findFirst().orElseThrow().state());

        assertTrue(report.frequencyCandidates().stream().allMatch(summary -> summary.folds().size() == 2));
        assertTrue(report.magnitudeCandidates().stream().allMatch(summary -> summary.folds().size() == 2));
    }

    @Test
    void nonReadyCorpusFailsClosedWithNoCandidateRows() throws Exception {
        Fixture single = fixture("single.db", "l1", "League", 2026, List.of("a", "b"));
        single.saveConfiguration();
        single.saveEligibility();
        for (int week = 1; week <= 9; week++) {
            single.saveCompleteWeek(week);
            single.saveCoverage(week);
            single.saveProduction(week, ProductionMode.BASELINE_ORDER);
        }

        var readiness = single.readinessAnalyzer().analyze(2026, 2026);
        var report = LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.fromSource(readiness);

        assertEquals(
            LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.StudyState
                .UNAVAILABLE_CORPUS_NOT_STRUCTURALLY_READY,
            report.studyState());
        assertTrue(report.folds().isEmpty());
        assertTrue(report.frequencyCandidates().isEmpty());
        assertTrue(report.magnitudeCandidates().isEmpty());
    }

    @Test
    void canonicalFrequencyCandidatesReduceSourceRatiosAndRejectNonCanonicalConstruction() {
        assertEquals(
            new LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FrequencyCandidate(1, 2),
            LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FrequencyCandidate.of(2, 4));
        assertThrows(IllegalArgumentException.class,
            () -> new LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FrequencyCandidate(2, 4));
    }

    @Test
    void reportRejectsFabricatedAvailableState() throws Exception {
        Fixture single = fixture("fabricated.db", "l1", "League", 2026, List.of("a", "b"));
        single.saveConfiguration();
        single.saveEligibility();
        for (int week = 1; week <= 9; week++) {
            single.saveCompleteWeek(week);
            single.saveCoverage(week);
            single.saveProduction(week, ProductionMode.BASELINE_ORDER);
        }
        var readiness = single.readinessAnalyzer().analyze(2026, 2026);
        var report = LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.fromSource(readiness);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateThresholdStudyReport(
                report.policyId(), report.metricScope(), report.studyPolicy(), report.sourceReadiness(),
                LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.StudyState.AVAILABLE,
                report.folds(), report.frequencyCandidates(), report.magnitudeCandidates()));
        assertEquals(
            "candidate threshold study fields must match governed BF-522/BF-518 source evidence",
            error.getMessage());
    }

    private Fixture fixture(
        String fileName,
        String leagueId,
        String leagueName,
        int season,
        List<String> teamPrefixes) throws Exception {
        Database database = new Database(tempDir.resolve(fileName));
        database.initialize();
        new LeagueRepository(database).save(new League(leagueId, leagueId.toUpperCase(), leagueName, season));

        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        for (int i = 0; i < teamPrefixes.size(); i++) {
            String prefix = teamPrefixes.get(i);
            teams.save(new Team("t" + prefix, String.valueOf(i + 1), leagueId, "Team " + prefix.toUpperCase()));
            players.save(new Player("p" + prefix + "1", prefix + "1", prefix + " QB", "QB", "CHI"));
            players.save(new Player("p" + prefix + "2", prefix + "2", prefix + " WR2", "WR", "DET"));
            players.save(new Player("p" + prefix + "3", prefix + "3", prefix + " WR3", "WR", "MIN"));
        }
        return new Fixture(database, leagueId, season, teamPrefixes);
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);

    private enum ProductionMode { BASELINE_ORDER, REVERSED_ORDER }

    private record Fixture(Database database, String leagueId, int season, List<String> teamPrefixes) {
        LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer corpusAnalyzer() {
            return new LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer(database);
        }

        LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer readinessAnalyzer() {
            return new LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer(database);
        }

        void saveConfiguration() throws Exception {
            new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
                leagueId, "sleeper", AS_OF, season, List.of("QB", "WR", "BN"),
                Map.of("pass_td", 4.0, "rec_td", 6.0)));
        }

        void saveEligibility() throws Exception {
            PlayerFantasyPositionObservationRepository repository =
                new PlayerFantasyPositionObservationRepository(database);
            for (String prefix : teamPrefixes) {
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
            for (String prefix : teamPrefixes) {
                rosters.save(TeamWeekRosterEvidence.create(
                    leagueId, "t" + prefix, season, week,
                    List.of(prefix + "1", prefix + "2", prefix + "3"),
                    List.of(prefix + "1", prefix + "2"),
                    "sleeper", AS_OF));
            }
        }

        void saveCoverage(int week) throws Exception {
            List<String> covered = teamPrefixes.stream()
                .flatMap(prefix -> List.of("p" + prefix + "1", "p" + prefix + "2", "p" + prefix + "3").stream())
                .toList();
            new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
                season, week, "nflverse",
                URI.create("https://example.test/stats_player_week_" + season + ".csv"),
                AS_OF, 100, covered.size(), 0, covered));
        }

        void saveProduction(int week, ProductionMode mode) throws Exception {
            PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
            for (int i = 0; i < teamPrefixes.size(); i++) {
                String prefix = teamPrefixes.get(i);
                boolean weakStarter = mode == ProductionMode.BASELINE_ORDER ? i == 0 : i != 0;
                save(production, week, "p" + prefix + "1", 1, 0);
                if (weakStarter) {
                    save(production, week, "p" + prefix + "2", 0, 1);
                    save(production, week, "p" + prefix + "3", 0, 2);
                } else {
                    save(production, week, "p" + prefix + "2", 0, 2);
                    save(production, week, "p" + prefix + "3", 0, 1);
                }
            }
        }

        private void save(
            PlayerWeekProductionRepository repository,
            int week,
            String playerId,
            int passingTouchdowns,
            int receivingTouchdowns) throws Exception {
            repository.save(PlayerWeekProduction.create(
                playerId, season, week,
                0, passingTouchdowns, 0,
                0, 0,
                0, 0, receivingTouchdowns,
                0, "nflverse", AS_OF));
        }
    }

    private static LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CorpusAuditReport combine(
        LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CorpusAuditReport first,
        LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CorpusAuditReport second,
        int startSeason,
        int endSeason) {
        List<LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.LeagueSeasonAudit> leagueSeasons =
            new ArrayList<>();
        leagueSeasons.addAll(first.leagueSeasons());
        leagueSeasons.addAll(second.leagueSeasons());
        List<LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.LeagueSeasonSourceFailure> failures =
            new ArrayList<>();
        failures.addAll(first.sourceFailures());
        failures.addAll(second.sourceFailures());

        return new LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CorpusAuditReport(
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.POLICY_ID,
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.METRIC_SCOPE,
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.AUDIT_POLICY,
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.MINIMUM_BASELINE_COMMON_WEEKS,
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.MINIMUM_HOLDOUT_COMMON_WEEKS,
            startSeason,
            endSeason,
            first.leaguesWithoutSeason() + second.leaguesWithoutSeason(),
            leagueSeasons,
            failures,
            summarize(leagueSeasons, failures));
    }

    private static LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CorpusSummary summarize(
        List<LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.LeagueSeasonAudit> leagueSeasons,
        List<LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.LeagueSeasonSourceFailure> failures) {
        int availableCutoffs = 0;
        int excludedCutoffs = 0;
        int teamRows = 0;
        Map<Integer, Integer> teamCounts = new LinkedHashMap<>();
        Map<Integer, Integer> baselineWeeks = new LinkedHashMap<>();
        Map<Integer, Integer> holdoutWeeks = new LinkedHashMap<>();
        Map<Integer, Integer> denominators = new LinkedHashMap<>();
        Map<LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass, Integer> classes =
            new EnumMap<>(LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass.class);
        Map<LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffState, Integer> states =
            new EnumMap<>(LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffState.class);

        for (var leagueSeason : leagueSeasons) {
            teamCounts.merge(leagueSeason.sourceCommonUniverse().teams().size(), 1, Integer::sum);
            for (var cutoff : leagueSeason.cutoffs()) {
                states.merge(cutoff.state(), 1, Integer::sum);
                baselineWeeks.merge(cutoff.baselineCommonWeeks().size(), 1, Integer::sum);
                holdoutWeeks.merge(cutoff.futureHoldoutCommonWeeks().size(), 1, Integer::sum);
                if (cutoff.state()
                    == LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffState.AVAILABLE) {
                    availableCutoffs++;
                    denominators.merge(cutoff.baselineCommonWeeks().size(), 1, Integer::sum);
                    teamRows += cutoff.teams().size();
                    for (var team : cutoff.teams()) classes.merge(team.baselineSensitivityClass(), 1, Integer::sum);
                } else {
                    excludedCutoffs++;
                }
            }
        }

        return new LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CorpusSummary(
            leagueSeasons.size() + failures.size(), leagueSeasons.size(), failures.size(),
            availableCutoffs, excludedCutoffs, teamRows,
            teamCounts, baselineWeeks, holdoutWeeks, denominators, classes, states);
    }
}
