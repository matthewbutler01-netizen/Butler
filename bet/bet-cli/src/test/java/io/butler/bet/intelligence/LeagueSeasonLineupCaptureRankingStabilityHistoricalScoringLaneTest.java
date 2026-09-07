package io.butler.bet.intelligence;

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
import io.butler.bet.sleeper.SleeperProviderNativeSeasonScoringAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueSeasonLineupCaptureRankingStabilityHistoricalScoringLaneTest {
    @TempDir Path tempDir;

    @Test
    void computesProviderNativeLeaveOneWeekOutSensitivityWithoutChangingRankingSemantics() throws Exception {
        Database database = new Database(tempDir.resolve("provider-native-ranking-stability.db"));
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
        for (int week = 1; week <= 5; week++) {
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

        var report = new LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer(database).analyze("l1", 2026);
        var source = report.sourceBaselineRanking().sourceCommonUniverse();

        assertEquals(HistoricalScoringLaneSelector.POLICY_ID, source.scoringLaneSelectionPolicyId());
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, source.scoringLane());
        assertEquals(HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID, source.scoringPolicyId());
        assertEquals(List.of(1, 2, 3, 4, 5), source.commonComparableWeeks());
        assertEquals(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankingState.AVAILABLE,
            report.sourceBaselineRanking().rankingState());
        assertEquals(LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.StabilityState.AVAILABLE,
            report.stabilityState());
        assertEquals(5, report.scenarios().size());
        assertTrue(report.scenarios().stream().allMatch(scenario ->
            scenario.state() == LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.ScenarioState.AVAILABLE));
        assertTrue(report.scenarios().stream().allMatch(scenario -> scenario.retainedCommonWeeks().size() == 4));

        var beta = report.teamSummaries().stream()
            .filter(team -> team.teamId().equals("tb")).findFirst().orElseThrow();
        var alpha = report.teamSummaries().stream()
            .filter(team -> team.teamId().equals("ta")).findFirst().orElseThrow();

        assertEquals(1, beta.baselineRank());
        assertEquals(new BigDecimal("0.700000"), beta.baselineLineupCaptureRate());
        assertEquals(List.of(1), beta.distinctPerturbationRanks());
        assertEquals(0, beta.maximumAbsoluteRankMovement());
        assertEquals(5, beta.baselineRankUnchangedScenarios());
        assertEquals(0, beta.baselineRankChangedScenarios());
        assertEquals(new BigDecimal("0.700000"), beta.minimumPerturbationRate());
        assertEquals(new BigDecimal("0.700000"), beta.maximumPerturbationRate());
        assertEquals(new BigDecimal("0.000000"), beta.maximumAbsoluteRateMovement());
        assertTrue(beta.rankUnchangedInAllScenarios());

        assertEquals(2, alpha.baselineRank());
        assertEquals(new BigDecimal("0.625000"), alpha.baselineLineupCaptureRate());
        assertEquals(List.of(2), alpha.distinctPerturbationRanks());
        assertEquals(0, alpha.maximumAbsoluteRankMovement());
        assertEquals(5, alpha.baselineRankUnchangedScenarios());
        assertEquals(0, alpha.baselineRankChangedScenarios());
        assertEquals(new BigDecimal("0.625000"), alpha.minimumPerturbationRate());
        assertEquals(new BigDecimal("0.625000"), alpha.maximumPerturbationRate());
        assertEquals(new BigDecimal("0.000000"), alpha.maximumAbsoluteRateMovement());
        assertTrue(alpha.rankUnchangedInAllScenarios());
    }

    private static void savePlayer(
        PlayerRepository repository, String id, String providerId, String name, String position, String team)
        throws Exception {
        repository.save(new Player(id, providerId, name, position, team));
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private static final LocalDate PROVIDER_AS_OF = LocalDate.of(2026, 9, 6);
}
