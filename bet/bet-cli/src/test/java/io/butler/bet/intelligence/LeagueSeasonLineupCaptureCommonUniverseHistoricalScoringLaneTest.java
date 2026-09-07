package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerFantasyPositionObservationRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerWeekProductionCoverageRepository;
import io.butler.bet.data.PlayerWeekProductionRepository;
import io.butler.bet.data.ProviderPlayerWeekPointsEvidenceRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueConfigurationObservation;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerFantasyPositionObservation;
import io.butler.bet.domain.PlayerWeekProduction;
import io.butler.bet.domain.PlayerWeekProductionCoverage;
import io.butler.bet.domain.ProviderPlayerWeekPointsEvidence;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamWeekRosterEvidence;
import io.butler.bet.sleeper.SleeperProviderNativeSeasonScoringAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueSeasonLineupCaptureCommonUniverseHistoricalScoringLaneTest {
    @TempDir Path tempDir;

    @Test
    void readyProviderNativeUsesExactAllTeamCommonWeekAndPreservesProviderProvenance() throws Exception {
        Fixture fixture = fixture("ready.db");
        fixture.saveProviderPoints(Map.of(
            "a1", new BigDecimal("4.0"), "a2", new BigDecimal("6.0"), "a3", new BigDecimal("12.0"),
            "b1", new BigDecimal("8.0"), "b2", new BigDecimal("6.0"), "b3", new BigDecimal("12.0")));

        var report = fixture.analyzer().analyze("l1", 2026);

        assertEquals(HistoricalScoringLaneSelector.POLICY_ID, report.scoringLaneSelectionPolicyId());
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, report.scoringLane());
        assertEquals(HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID, report.scoringPolicyId());
        assertEquals(
            LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState.AVAILABLE,
            report.commonUniverseState());
        assertEquals(List.of(1), report.commonComparableWeeks());
        assertEquals(List.of("Alpha Team", "Beta Team"), report.teams().stream()
            .map(LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.TeamCommonEvidence::teamName).toList());

        var alpha = report.teams().get(0);
        assertEquals(new BigDecimal("10.0"), alpha.commonTotalStartedPoints().orElseThrow());
        assertEquals(new BigDecimal("16.0"), alpha.commonTotalPotentialPoints().orElseThrow());
        assertEquals(new BigDecimal("6.0"), alpha.commonTotalPointsGap().orElseThrow());
        assertEquals(new BigDecimal("0.625000"), alpha.lineupCaptureRate().orElseThrow());

        var beta = report.teams().get(1);
        assertEquals(new BigDecimal("14.0"), beta.commonTotalStartedPoints().orElseThrow());
        assertEquals(new BigDecimal("20.0"), beta.commonTotalPotentialPoints().orElseThrow());
        assertEquals(new BigDecimal("6.0"), beta.commonTotalPointsGap().orElseThrow());
        assertEquals(new BigDecimal("0.700000"), beta.lineupCaptureRate().orElseThrow());

        for (var team : report.teams()) {
            var gap = team.sourceSeasonPointsGap().weeks().get(0).pointsGap();
            assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, gap.scoringLane());
            assertEquals(PROVIDER_AS_OF, gap.providerPointsAsOf());
            assertEquals(SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE,
                gap.providerPointsSourceSurface());
            assertEquals("provider-l1", gap.providerLeagueId());
        }
    }

    @Test
    void incompleteProviderSnapshotNeverFallsBackToReadyNflverseOrWidensCommonUniverse() throws Exception {
        Fixture fixture = fixture("blocked.db");
        fixture.saveProviderPoints(Map.of(
            "a1", new BigDecimal("4.0"), "a2", new BigDecimal("6.0"), "a3", new BigDecimal("12.0"),
            "b1", new BigDecimal("8.0"), "b2", new BigDecimal("6.0")));
        fixture.saveReadyNflverse();

        var report = fixture.analyzer().analyze("l1", 2026);

        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, report.scoringLane());
        assertEquals(HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID, report.scoringPolicyId());
        assertEquals(
            LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState.UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS,
            report.commonUniverseState());
        assertTrue(report.commonComparableWeeks().isEmpty());
        assertTrue(report.teams().stream().allMatch(team -> team.lineupCaptureRate().isEmpty()));
        assertTrue(report.teams().stream()
            .flatMap(team -> team.sourceSeasonPointsGap().weeks().stream())
            .flatMap(week -> week.blockers().stream())
            .anyMatch(blocker -> blocker.contains("Missing provider-points identities") && blocker.contains("b3")));
    }

    @Test
    void rankingAcceptsProviderNativeSourceButPreservesFourWeekGovernanceFloor() throws Exception {
        Fixture fixture = fixture("ranking-provider-native.db");
        fixture.saveProviderPoints(Map.of(
            "a1", new BigDecimal("4.0"), "a2", new BigDecimal("6.0"), "a3", new BigDecimal("12.0"),
            "b1", new BigDecimal("8.0"), "b2", new BigDecimal("6.0"), "b3", new BigDecimal("12.0")));
        var common = fixture.analyzer().analyze("l1", 2026);

        var runtime = new LeagueSeasonLineupCaptureRankingEvidenceAnalyzer(fixture.database()).analyze("l1", 2026);
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE,
            runtime.sourceCommonUniverse().scoringLane());
        assertEquals(
            LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankingState.UNAVAILABLE_BELOW_MINIMUM_COMMON_WEEKS,
            runtime.rankingState());
        assertTrue(runtime.rankedTeams().isEmpty());

        var direct = LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.fromSource(common);
        assertEquals(runtime.rankingState(), direct.rankingState());
        assertEquals(runtime.rankedTeams(), direct.rankedTeams());

        var reconstructed = new LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.LeagueRankingReport(
            runtime.policyId(), runtime.metricScope(), runtime.minimumCommonWeeks(), runtime.rankingPolicy(),
            common, runtime.rankingState(), runtime.rankedTeams());
        assertEquals(runtime.rankingState(), reconstructed.rankingState());
        assertEquals(runtime.rankedTeams(), reconstructed.rankedTeams());
    }

    private Fixture fixture(String fileName) throws Exception {
        Database database = new Database(tempDir.resolve(fileName));
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
        rosters.save(TeamWeekRosterEvidence.create(
            "l1", "ta", 2026, 1, List.of("a1", "a2", "a3"), List.of("a1", "a2"), "sleeper", AS_OF));
        rosters.save(TeamWeekRosterEvidence.create(
            "l1", "tb", 2026, 1, List.of("b1", "b2", "b3"), List.of("b1", "b2"), "sleeper", AS_OF));
        return new Fixture(database);
    }

    private static void savePlayer(
        PlayerRepository repository, String id, String providerId, String name, String position, String team)
        throws Exception {
        repository.save(new Player(id, providerId, name, position, team));
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private static final LocalDate PROVIDER_AS_OF = LocalDate.of(2026, 9, 6);

    private record Fixture(Database database) {
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer analyzer() {
            return new LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer(database);
        }

        void saveProviderPoints(Map<String, BigDecimal> points) throws Exception {
            List<ProviderPlayerWeekPointsEvidence> rows = new ArrayList<>();
            for (var entry : points.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                String playerId = entry.getKey();
                boolean alpha = playerId.startsWith("a");
                rows.add(ProviderPlayerWeekPointsEvidence.create(
                    "l1", alpha ? "ta" : "tb", alpha ? "1" : "2", "provider-l1", 2026, 1,
                    playerId, entry.getValue(), "sleeper",
                    SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, PROVIDER_AS_OF));
            }
            new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
                "l1", 2026, "sleeper", PROVIDER_AS_OF, rows);
        }

        void saveReadyNflverse() throws Exception {
            List<String> ids = List.of("pa1", "pa2", "pa3", "pb1", "pb2", "pb3");
            new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
                2026, 1, "nflverse", URI.create("https://example.test/stats_player_week_2026.csv"),
                AS_OF, 50, ids.size(), 0, ids));
            PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
            production.save(PlayerWeekProduction.create(
                "pa1", 2026, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, "nflverse", AS_OF));
            production.save(PlayerWeekProduction.create(
                "pa2", 2026, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, "nflverse", AS_OF));
            production.save(PlayerWeekProduction.create(
                "pa3", 2026, 1, 0, 0, 0, 0, 0, 0, 0, 2, 0, "nflverse", AS_OF));
            production.save(PlayerWeekProduction.create(
                "pb1", 2026, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, "nflverse", AS_OF));
            production.save(PlayerWeekProduction.create(
                "pb2", 2026, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, "nflverse", AS_OF));
            production.save(PlayerWeekProduction.create(
                "pb3", 2026, 1, 0, 0, 0, 0, 0, 0, 0, 2, 0, "nflverse", AS_OF));
        }
    }
}
