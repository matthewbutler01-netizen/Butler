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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void narrowSingleClusterCorpusFailsStructuralReadinessWithoutCallingItStatisticallyInadequate() throws Exception {
        Fixture fixture = fixture("narrow.db", "l1", "League One", 2026, List.of("a", "b"));
        fixture.saveConfiguration();
        fixture.saveEligibility();
        for (int week = 1; week <= 9; week++) {
            fixture.saveCompleteWeek(week);
            fixture.saveCoverage(week);
            fixture.saveProduction(week, week <= 5 ? ProductionMode.BASELINE_ORDER : ProductionMode.REVERSED_ORDER);
        }

        var report = fixture.readinessAnalyzer().analyze(2026, 2026);

        assertEquals(LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.POLICY_ID,
            report.policyId());
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.ReadinessState
                .NOT_READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN,
            report.readinessState());
        assertEquals(6, report.gates().size());
        assertTrue(report.gates().stream().noneMatch(
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.GateEvidence::passed));

        var diagnostics = report.diagnostics();
        assertEquals(List.of("l1"), diagnostics.availableLeagueIds());
        assertEquals(List.of(2026), diagnostics.availableSeasons());
        assertEquals(List.of("l1:2026"), diagnostics.availableLeagueSeasonIdentities());
        assertEquals(List.of(2), diagnostics.repositoryTeamCountStrata());
        assertEquals(List.of(5), diagnostics.perturbationDenominators());
        assertEquals(1, diagnostics.availableCutoffs());
        assertEquals(2, diagnostics.availableTeamCutoffRows());
        assertEquals(0, diagnostics.exactNumericRankRetainedRows());
        assertEquals(2, diagnostics.temporalRankMovedRows());
        assertEquals(Map.of(5, 1), diagnostics.availableCutoffsByPerturbationDenominator());
        assertEquals(Map.of(new BigDecimal("0.000000"), 2), diagnostics.rankChangeFrequencyDistribution());
    }

    @Test
    void diverseCorpusPassesSixStructuralVariationGatesButStillDoesNotCreateThresholds() throws Exception {
        Fixture moved = fixture("moved.db", "l1", "League One", 2025, List.of("a", "b"));
        moved.saveConfiguration();
        moved.saveEligibility();
        for (int week = 1; week <= 9; week++) {
            moved.saveCompleteWeek(week);
            moved.saveCoverage(week);
            moved.saveProduction(week, week <= 5 ? ProductionMode.BASELINE_ORDER : ProductionMode.REVERSED_ORDER);
        }

        Fixture retained = fixture("retained.db", "l2", "League Two", 2026, List.of("c", "d", "e"));
        retained.saveConfiguration();
        retained.saveEligibility();
        for (int week = 1; week <= 10; week++) {
            retained.saveCompleteWeek(week);
            retained.saveCoverage(week);
            retained.saveProduction(week, ProductionMode.BASELINE_ORDER);
        }

        var movedSource = moved.corpusAnalyzer().analyze(2025, 2025);
        var retainedSource = retained.corpusAnalyzer().analyze(2026, 2026);
        var combined = combine(movedSource, retainedSource, 2025, 2026);
        var report = LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.fromSource(combined);

        assertEquals(
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.ReadinessState
                .READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN,
            report.readinessState());
        assertEquals(6, report.gates().size());
        assertTrue(report.gates().stream().allMatch(
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.GateEvidence::passed));

        var diagnostics = report.diagnostics();
        assertEquals(List.of("l1", "l2"), diagnostics.availableLeagueIds());
        assertEquals(List.of(2025, 2026), diagnostics.availableSeasons());
        assertEquals(List.of("l1:2025", "l2:2026"), diagnostics.availableLeagueSeasonIdentities());
        assertEquals(List.of(2, 3), diagnostics.repositoryTeamCountStrata());
        assertEquals(List.of(5, 6), diagnostics.perturbationDenominators());
        assertEquals(3, diagnostics.availableCutoffs());
        assertEquals(8, diagnostics.availableTeamCutoffRows());
        assertEquals(6, diagnostics.exactNumericRankRetainedRows());
        assertEquals(2, diagnostics.temporalRankMovedRows());
        assertEquals(Map.of("l1", 1, "l2", 2), diagnostics.availableCutoffsByLeagueId());
        assertEquals(Map.of(2025, 1, 2026, 2), diagnostics.availableCutoffsBySeason());
        assertEquals(Map.of(2, 1, 3, 2), diagnostics.availableCutoffsByTeamCount());
        assertEquals(Map.of(5, 2, 6, 1), diagnostics.availableCutoffsByPerturbationDenominator());
        assertEquals(8, diagnostics.sensitivityClassCounts().get(
            LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass.LOW_SENSITIVITY));
        assertFalse(report.readinessPolicy().toLowerCase().contains("confidence"));
    }

    @Test
    void reportRejectsFabricatedReadinessState() throws Exception {
        Fixture fixture = fixture("invariant.db", "l1", "League", 2026, List.of("a", "b"));
        fixture.saveConfiguration();
        fixture.saveEligibility();
        for (int week = 1; week <= 9; week++) {
            fixture.saveCompleteWeek(week);
            fixture.saveCoverage(week);
            fixture.saveProduction(week, ProductionMode.BASELINE_ORDER);
        }
        var source = fixture.corpusAnalyzer().analyze(2026, 2026);
        var report = LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.fromSource(source);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.CorpusReadinessReport(
                report.policyId(), report.metricScope(), report.readinessPolicy(), report.sourceCorpusAudit(),
                LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.ReadinessState
                    .READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN,
                report.gates(), report.diagnostics()));

        assertEquals("corpus structural readiness fields must match governed BF-518 source evidence", error.getMessage());
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
            String teamId = "t" + prefix;
            teams.save(new Team(teamId, String.valueOf(i + 1), leagueId, "Team " + prefix.toUpperCase()));
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
