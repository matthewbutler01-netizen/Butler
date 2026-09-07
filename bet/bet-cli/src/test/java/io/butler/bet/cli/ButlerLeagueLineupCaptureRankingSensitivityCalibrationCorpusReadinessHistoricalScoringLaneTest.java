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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessHistoricalScoringLaneTest {
    @TempDir Path tempDir;

    @Test
    void carriesProviderNativeCorpusIntoStructuralReadinessWithoutWeakeningFailedGates() throws Exception {
        Database database = new Database(tempDir.resolve("provider-native-calibration-readiness.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));

        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("tb", "2", "l1", "Beta Team"));
        teams.save(new Team("ta", "1", "l1", "Alpha Team"));

        PlayerRepository players = new PlayerRepository(database);
        savePlayer(players, "pa1", "a1", "A Quarterback", "QB", "CHI");
        savePlayer(players, "pa2", "a2", "A Receiver Two", "WR", "DET");
        savePlayer(players, "pa3", "a3", "A Receiver Three", "WR", "MIN");
        savePlayer(players, "pb1", "b1", "B Quarterback", "QB", "GB");
        savePlayer(players, "pb2", "b2", "B Receiver Two", "WR", "SEA");
        savePlayer(players, "pb3", "b3", "B Receiver Three", "WR", "LAR");

        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            "l1", "sleeper", AS_OF, 2026,
            List.of("QB", "WR", "BN"), Map.of("pass_td", 4.0, "rec_td", 6.0)));
        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        eligibility.replace(new PlayerFantasyPositionObservation("pa1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("pa2", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("pa3", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("pb1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("pb2", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("pb3", "sleeper", AS_OF, List.of("WR")));

        TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
        List<ProviderPlayerWeekPointsEvidence> providerRows = new ArrayList<>();
        Map<String, BigDecimal> points = Map.of(
            "a1", new BigDecimal("4.0"), "a2", new BigDecimal("6.0"), "a3", new BigDecimal("12.0"),
            "b1", new BigDecimal("8.0"), "b2", new BigDecimal("6.0"), "b3", new BigDecimal("12.0"));
        for (int week = 1; week <= 9; week++) {
            rosters.save(TeamWeekRosterEvidence.create(
                "l1", "ta", 2026, week, List.of("a1", "a2", "a3"), List.of("a1", "a2"),
                "sleeper", AS_OF));
            rosters.save(TeamWeekRosterEvidence.create(
                "l1", "tb", 2026, week, List.of("b1", "b2", "b3"), List.of("b1", "b2"),
                "sleeper", AS_OF));
            for (var entry : points.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                String playerId = entry.getKey();
                boolean alpha = playerId.startsWith("a");
                providerRows.add(ProviderPlayerWeekPointsEvidence.create(
                    "l1", alpha ? "ta" : "tb", alpha ? "1" : "2", "provider-l1", 2026, week,
                    playerId, entry.getValue(), "sleeper",
                    SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, PROVIDER_AS_OF));
            }
        }
        new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
            "l1", 2026, "sleeper", PROVIDER_AS_OF, providerRows);

        var report = new LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer(database)
            .analyze(2026, 2026);
        var sourceAudit = report.sourceCorpusAudit();
        var commonUniverse = sourceAudit.leagueSeasons().get(0).sourceCommonUniverse();

        assertEquals(HistoricalScoringLaneSelector.POLICY_ID, commonUniverse.scoringLaneSelectionPolicyId());
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, commonUniverse.scoringLane());
        assertEquals(HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID, commonUniverse.scoringPolicyId());
        assertEquals(1, sourceAudit.summary().availableCutoffs());
        assertEquals(7, sourceAudit.summary().excludedCutoffs());

        assertEquals(
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.ReadinessState
                .NOT_READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN,
            report.readinessState());
        assertEquals(6, report.gates().size());
        assertTrue(report.gates().stream().noneMatch(
            LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.GateEvidence::passed));
        assertEquals(1, report.diagnostics().availableCutoffs());
        assertEquals(2, report.diagnostics().availableTeamCutoffRows());
        assertEquals(2, report.diagnostics().exactNumericRankRetainedRows());
        assertEquals(0, report.diagnostics().temporalRankMovedRows());
        assertFalse(report.gates().stream().anyMatch(gate -> gate.gateId()
            == LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.GateId.TEMPORAL_OUTCOME_VARIATION
            && gate.passed()));

        String output = capture(() ->
            ButlerLeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessCli.print(report));
        assertTrue(output.contains("Source historical scoring lanes:"));
        assertTrue(output.contains("source scoring lane selector: " + HistoricalScoringLaneSelector.POLICY_ID));
        assertTrue(output.contains("source scoring lane: "
            + HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE));
        assertTrue(output.contains("source scoring policy: "
            + HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID));
        assertTrue(output.contains("does not pass every minimum structural variation gate"));
        assertTrue(output.contains("Failed gates must remain visible; Butler does not weaken them or synthesize evidence."));
        assertTrue(output.contains("not statistical sample-size adequacy"));
    }

    private static void savePlayer(
        PlayerRepository repository, String id, String providerId, String name, String position, String team)
        throws Exception {
        repository.save(new Player(id, providerId, name, position, team));
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
