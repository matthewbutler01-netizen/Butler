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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueSeasonLineupCaptureHistoricalScoringLaneTest {
    @TempDir Path tempDir;

    @Test
    void readyProviderNativeLeagueSeasonPreservesExactTeamCaptureAndProvenance() throws Exception {
        Fixture fixture = fixture("ready.db");
        fixture.saveProviderPoints(Map.of(
            "s1", new BigDecimal("4.0"),
            "s2", new BigDecimal("6.0"),
            "s3", new BigDecimal("12.0")));

        var report = new LeagueSeasonLineupCaptureEvidenceAnalyzer(fixture.database()).analyze("l1", 2026);

        assertEquals(HistoricalScoringLaneSelector.POLICY_ID, report.scoringLaneSelectionPolicyId());
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, report.scoringLane());
        assertEquals(HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID, report.scoringPolicyId());
        assertEquals(1, report.teams().size());
        var capture = report.teams().get(0).seasonEvidence();
        var source = capture.sourceSeasonPointsGap();
        assertEquals(LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.CaptureRateState.AVAILABLE, capture.rateState());
        assertEquals(new BigDecimal("0.625000"), capture.lineupCaptureRate().orElseThrow());
        assertEquals(new BigDecimal("10.0"), source.aggregate().comparableTotalStartedPoints().orElseThrow());
        assertEquals(new BigDecimal("16.0"), source.aggregate().comparableTotalPotentialPoints().orElseThrow());
        assertEquals(new BigDecimal("6.0"), source.aggregate().comparableTotalPointsGap().orElseThrow());
        var gap = source.weeks().get(0).pointsGap();
        assertEquals(PROVIDER_AS_OF, gap.providerPointsAsOf());
        assertEquals(SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE,
            gap.providerPointsSourceSurface());
        assertEquals("provider-l1", gap.providerLeagueId());
    }

    @Test
    void blockedProviderNativeLeagueSeasonNeverFallsBackToReadyNflverse() throws Exception {
        Fixture fixture = fixture("blocked.db");
        fixture.saveProviderPoints(Map.of(
            "s1", new BigDecimal("4.0"),
            "s2", new BigDecimal("6.0")));
        fixture.saveReadyNflverse();

        var report = new LeagueSeasonLineupCaptureEvidenceAnalyzer(fixture.database()).analyze("l1", 2026);

        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, report.scoringLane());
        assertEquals(HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID, report.scoringPolicyId());
        var capture = report.teams().get(0).seasonEvidence();
        var source = capture.sourceSeasonPointsGap();
        assertEquals(1, source.aggregate().blockedWeeks());
        assertEquals(0, source.aggregate().comparableCompleteWeeks());
        assertTrue(source.aggregate().comparableTotalPointsGap().isEmpty());
        assertEquals(
            LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.CaptureRateState.UNAVAILABLE_NO_COMPARABLE_WEEKS,
            capture.rateState());
        assertFalse(capture.lineupCaptureRate().isPresent());
        assertTrue(source.weeks().get(0).blockers().stream().anyMatch(
            blocker -> blocker.contains("Missing provider-points identities") && blocker.contains("s3")));
    }

    @Test
    void providerNativeCommonUniverseBuildsGovernedInsufficientTeamRankingReport() throws Exception {
        Fixture fixture = fixture("report-provider-native.db");
        fixture.saveProviderPoints(Map.of(
            "s1", new BigDecimal("4.0"),
            "s2", new BigDecimal("6.0"),
            "s3", new BigDecimal("12.0")));

        var common = new LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer(fixture.database())
            .analyze("l1", 2026);
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, common.scoringLane());
        assertEquals(
            LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState.UNAVAILABLE_INSUFFICIENT_TEAMS,
            common.commonUniverseState());

        var report = new LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.LeagueRankingReport(
            LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.POLICY_ID,
            LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.METRIC_SCOPE,
            LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.MINIMUM_COMMON_WEEKS,
            LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RANKING_POLICY,
            common,
            LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankingState.UNAVAILABLE_INSUFFICIENT_TEAMS,
            List.of());

        assertEquals(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankingState.UNAVAILABLE_INSUFFICIENT_TEAMS,
            report.rankingState());
        assertTrue(report.rankedTeams().isEmpty());
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE,
            report.sourceCommonUniverse().scoringLane());
    }

    private Fixture fixture(String fileName) throws Exception {
        Database database = new Database(tempDir.resolve(fileName));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "Team One"));
        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "s1", "Quarterback", "QB", "CHI"));
        players.save(new Player("p2", "s2", "Receiver Two", "WR", "DET"));
        players.save(new Player("p3", "s3", "Receiver Three", "WR", "MIN"));
        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            "l1", "sleeper", AS_OF, 2026,
            List.of("QB", "WR", "BN"), Map.of("pass_td", 4.0, "rec_td", 6.0)));
        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        eligibility.replace(new PlayerFantasyPositionObservation("p1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("p2", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("p3", "sleeper", AS_OF, List.of("WR")));
        new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
            "l1", "t1", 2026, 1, List.of("s1", "s2", "s3"), List.of("s1", "s2"),
            "sleeper", AS_OF));
        return new Fixture(database);
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private static final LocalDate PROVIDER_AS_OF = LocalDate.of(2026, 9, 6);

    private record Fixture(Database database) {
        void saveProviderPoints(Map<String, BigDecimal> points) throws Exception {
            List<ProviderPlayerWeekPointsEvidence> rows = points.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> ProviderPlayerWeekPointsEvidence.create(
                    "l1", "t1", "1", "provider-l1", 2026, 1, entry.getKey(), entry.getValue(),
                    "sleeper", SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, PROVIDER_AS_OF))
                .toList();
            new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
                "l1", 2026, "sleeper", PROVIDER_AS_OF, rows);
        }

        void saveReadyNflverse() throws Exception {
            new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
                2026, 1, "nflverse", URI.create("https://example.test/stats_player_week_2026.csv"),
                AS_OF, 50, 3, 0, List.of("p1", "p2", "p3")));
            PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
            production.save(PlayerWeekProduction.create(
                "p1", 2026, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, "nflverse", AS_OF));
            production.save(PlayerWeekProduction.create(
                "p2", 2026, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, "nflverse", AS_OF));
            production.save(PlayerWeekProduction.create(
                "p3", 2026, 1, 0, 0, 0, 0, 0, 0, 0, 2, 0, "nflverse", AS_OF));
        }
    }
}
