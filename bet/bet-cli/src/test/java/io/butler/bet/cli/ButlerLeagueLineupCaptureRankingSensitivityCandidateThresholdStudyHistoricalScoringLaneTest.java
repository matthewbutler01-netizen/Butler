package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerFantasyPositionObservationRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.ProviderPlayerWeekPointsEvidenceRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueConfigurationObservation;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerFantasyPositionObservation;
import io.butler.bet.domain.ProviderPlayerWeekPointsEvidence;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamWeekRosterEvidence;
import io.butler.bet.intelligence.HistoricalScoringLaneSelector;
import io.butler.bet.intelligence.LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer;
import io.butler.bet.intelligence.LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer;
import io.butler.bet.intelligence.LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer;
import io.butler.bet.sleeper.SleeperProviderNativeSeasonScoringAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueLineupCaptureRankingSensitivityCandidateThresholdStudyHistoricalScoringLaneTest {
    @TempDir Path tempDir;

    @Test
    void studiesAndAuditsStructurallyReadyProviderNativeCorpusWithoutSelectingThreshold() throws Exception {
        Database database = new Database(tempDir.resolve("provider-native-candidate-study.db"));
        database.initialize();

        saveLeague(database, "l1", "League One", 2025, List.of("a", "b"));
        saveLeague(database, "l2", "League Two", 2026, List.of("c", "d", "e"));

        saveSeasonEvidence(database, "l1", 2025, List.of("a", "b"), 9, true);
        saveSeasonEvidence(database, "l2", 2026, List.of("c", "d", "e"), 10, false);

        var supportReport = new LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer(database)
            .analyze(2025, 2026);
        var report = supportReport.sourceCandidateStudy();
        var readiness = report.sourceReadiness();
        var source = readiness.sourceCorpusAudit();

        assertEquals(
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.ReadinessState
                .READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN,
            readiness.readinessState());
        assertTrue(readiness.gates().stream().allMatch(
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.GateEvidence::passed));
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.StudyState.AVAILABLE,
            report.studyState());
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer.ReportState.AVAILABLE,
            supportReport.reportState());
        assertEquals(2, report.folds().size());
        assertEquals(2, source.leagueSeasons().size());

        for (var leagueSeason : source.leagueSeasons()) {
            var commonUniverse = leagueSeason.sourceCommonUniverse();
            assertEquals(HistoricalScoringLaneSelector.POLICY_ID, commonUniverse.scoringLaneSelectionPolicyId());
            assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, commonUniverse.scoringLane());
            assertEquals(HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID,
                commonUniverse.scoringPolicyId());
        }

        assertEquals(List.of(2, 3), readiness.diagnostics().repositoryTeamCountStrata());
        assertEquals(List.of(5, 6), readiness.diagnostics().perturbationDenominators());
        assertTrue(readiness.diagnostics().exactNumericRankRetainedRows() > 0);
        assertTrue(readiness.diagnostics().temporalRankMovedRows() > 0);

        var firstHeldOut = report.folds().stream()
            .filter(fold -> fold.heldOutLeagueSeason().equals("l1:2025"))
            .findFirst().orElseThrow();
        assertEquals(1, firstHeldOut.developmentClusterCount());
        assertEquals(1, firstHeldOut.frequencyEvaluations().size());
        assertEquals(
            new LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FrequencyCandidate(0, 1),
            firstHeldOut.frequencyEvaluations().get(0).candidate());
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateFoldState.EVALUABLE,
            firstHeldOut.frequencyEvaluations().get(0).state());
        assertEquals(1, firstHeldOut.frequencyEvaluations().get(0).meetsRule().rows());
        assertEquals(1, firstHeldOut.frequencyEvaluations().get(0).doesNotMeetRule().rows());
        assertEquals(1, firstHeldOut.magnitudeEvaluations().size());
        assertEquals(0, firstHeldOut.magnitudeEvaluations().get(0).candidate().maximumMovementCutoff());
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateFoldState.EVALUABLE,
            firstHeldOut.magnitudeEvaluations().get(0).state());

        var secondHeldOut = report.folds().stream()
            .filter(fold -> fold.heldOutLeagueSeason().equals("l2:2026"))
            .findFirst().orElseThrow();
        assertTrue(secondHeldOut.frequencyEvaluations().stream().anyMatch(evaluation ->
            evaluation.candidate().equals(
                new LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FrequencyCandidate(3, 5))));
        assertTrue(secondHeldOut.magnitudeEvaluations().stream().anyMatch(evaluation ->
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

        assertEquals(
            report.frequencyCandidates().stream().map(item -> item.candidate()).toList(),
            supportReport.frequencyCandidates().stream().map(item -> item.candidate()).toList());
        assertEquals(
            report.magnitudeCandidates().stream().map(item -> item.candidate()).toList(),
            supportReport.magnitudeCandidates().stream().map(item -> item.candidate()).toList());

        var zeroFrequency = supportReport.frequencyCandidates().stream()
            .filter(candidate -> candidate.candidate().equals(
                new LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FrequencyCandidate(0, 1)))
            .findFirst().orElseThrow();
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer.SupportState
                .SINGLE_EVALUABLE_FOLD,
            zeroFrequency.supportState());
        assertEquals(2, zeroFrequency.counts().totalFolds());
        assertEquals(1, zeroFrequency.counts().evaluableFolds());
        assertEquals(1, zeroFrequency.foldDirections().size());
        assertEquals(
            LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer.DirectionState
                .EQUAL_TOTAL_ABSOLUTE_DISPLACEMENT,
            zeroFrequency.foldDirections().get(0).directionState());
        assertEquals(1L, zeroFrequency.foldDirections().get(0).meetsRuleTotalAbsoluteTemporalRankDisplacement());
        assertEquals(1L, zeroFrequency.foldDirections().get(0).doesNotMeetRuleTotalAbsoluteTemporalRankDisplacement());

        String studyOutput = capture(() ->
            ButlerLeagueLineupCaptureRankingSensitivityCandidateThresholdStudyCli.print(report));
        assertTrue(studyOutput.contains("source scoring lane selector: " + HistoricalScoringLaneSelector.POLICY_ID));
        assertTrue(studyOutput.contains("source scoring lane: " + HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE));
        assertTrue(studyOutput.contains("source scoring policy: "
            + HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID));
        assertTrue(studyOutput.contains("Study state: AVAILABLE"));
        assertTrue(studyOutput.contains("Held out: l1:2025"));
        assertTrue(studyOutput.contains("Held out: l2:2026"));
        assertTrue(studyOutput.contains("ordered by candidate value, never performance"));
        assertTrue(studyOutput.contains("does not select a best/optimal/recommended/production threshold"));

        String supportOutput = capture(() ->
            ButlerLeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditCli.print(supportReport));
        assertTrue(supportOutput.contains("source scoring lane selector: " + HistoricalScoringLaneSelector.POLICY_ID));
        assertTrue(supportOutput.contains("source scoring lane: "
            + HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE));
        assertTrue(supportOutput.contains("source scoring policy: "
            + HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID));
        assertTrue(supportOutput.contains("Audit state: AVAILABLE"));
        assertTrue(supportOutput.contains("support state: SINGLE_EVALUABLE_FOLD"));
        assertTrue(supportOutput.contains("direction=EQUAL_TOTAL_ABSOLUTE_DISPLACEMENT"));
        assertTrue(supportOutput.contains("Support states are evidence-breadth labels, not confidence"));
        assertTrue(supportOutput.contains("does not normalize those totals into a scalar score"));
        assertTrue(supportOutput.contains("select or break ties among candidates"));
    }

    private static void saveLeague(
        Database database,
        String leagueId,
        String leagueName,
        int season,
        List<String> teamPrefixes) throws Exception {
        new LeagueRepository(database).save(new League(leagueId, leagueId.toUpperCase(), leagueName, season));

        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        for (int i = 0; i < teamPrefixes.size(); i++) {
            String prefix = teamPrefixes.get(i);
            String teamId = leagueId + "-t" + prefix;
            teams.save(new Team(teamId, String.valueOf(i + 1), leagueId, "Team " + prefix.toUpperCase()));
            players.save(new Player("p" + prefix + "1", prefix + "1", prefix + " Quarterback", "QB", "CHI"));
            players.save(new Player("p" + prefix + "2", prefix + "2", prefix + " Receiver Two", "WR", "DET"));
            players.save(new Player("p" + prefix + "3", prefix + "3", prefix + " Receiver Three", "WR", "MIN"));
        }
    }

    private static void saveSeasonEvidence(
        Database database,
        String leagueId,
        int season,
        List<String> teamPrefixes,
        int weeks,
        boolean mixedTemporalOutcome) throws Exception {
        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            leagueId, "sleeper", AS_OF, season, List.of("QB", "WR", "BN"),
            Map.of("pass_td", 4.0, "rec_td", 6.0)));

        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        for (String prefix : teamPrefixes) {
            eligibility.replace(new PlayerFantasyPositionObservation(
                "p" + prefix + "1", "sleeper", AS_OF, List.of("QB")));
            eligibility.replace(new PlayerFantasyPositionObservation(
                "p" + prefix + "2", "sleeper", AS_OF, List.of("WR")));
            eligibility.replace(new PlayerFantasyPositionObservation(
                "p" + prefix + "3", "sleeper", AS_OF, List.of("WR")));
        }

        TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
        List<ProviderPlayerWeekPointsEvidence> providerRows = new ArrayList<>();
        for (int week = 1; week <= weeks; week++) {
            boolean reversed = mixedTemporalOutcome && (week > 5 || week % 2 == 0);
            for (int i = 0; i < teamPrefixes.size(); i++) {
                String prefix = teamPrefixes.get(i);
                String teamId = leagueId + "-t" + prefix;
                String providerTeamId = String.valueOf(i + 1);
                rosters.save(TeamWeekRosterEvidence.create(
                    leagueId, teamId, season, week,
                    List.of(prefix + "1", prefix + "2", prefix + "3"),
                    List.of(prefix + "1", prefix + "2"),
                    "sleeper", AS_OF));

                boolean weakStarter = reversed ? i != 0 : i == 0;
                BigDecimal receiverTwo = weakStarter ? new BigDecimal("6.0") : new BigDecimal("12.0");
                BigDecimal receiverThree = weakStarter ? new BigDecimal("12.0") : new BigDecimal("6.0");
                addProviderRow(providerRows, leagueId, teamId, providerTeamId, season, week,
                    prefix + "1", new BigDecimal("4.0"));
                addProviderRow(providerRows, leagueId, teamId, providerTeamId, season, week,
                    prefix + "2", receiverTwo);
                addProviderRow(providerRows, leagueId, teamId, providerTeamId, season, week,
                    prefix + "3", receiverThree);
            }
        }

        new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
            leagueId, season, "sleeper", PROVIDER_AS_OF, providerRows);
    }

    private static void addProviderRow(
        List<ProviderPlayerWeekPointsEvidence> rows,
        String leagueId,
        String teamId,
        String providerTeamId,
        int season,
        int week,
        String providerPlayerId,
        BigDecimal points) {
        rows.add(ProviderPlayerWeekPointsEvidence.create(
            leagueId,
            teamId,
            providerTeamId,
            "provider-" + leagueId,
            season,
            week,
            providerPlayerId,
            points,
            "sleeper",
            SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE,
            PROVIDER_AS_OF));
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
    private static final LocalDate PROVIDER_AS_OF = LocalDate.of(2026, 9, 6);
}
