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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueTeamWeekPotentialLineupAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void scoresObservedProductionUsesAuthorizedZeroAndSolvesCompletePotentialLineup() throws Exception {
        Fixture fixture = readyFixture();
        fixture.saveProduction("p1", COVERAGE_DATE, 1);

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertEquals(HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT, report.scoringLane());
        assertEquals(LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE, report.metricScope());
        assertEquals(COVERAGE_DATE, report.productionCoverageAsOf());
        assertEquals(URI.create("https://example.test/week.csv"), report.productionSourceUri());
        assertNull(report.providerPointsAsOf());
        assertEquals(2, report.playerScores().size());
        assertEquals(new BigDecimal("4.0"), report.playerScores().get(0).fantasyPoints());
        assertEquals(LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.OBSERVED,
            report.playerScores().get(0).productionState());
        assertEquals(BigDecimal.ZERO, report.playerScores().get(1).fantasyPoints());
        assertEquals(LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.IDENTITY_COVERED_ZERO,
            report.playerScores().get(1).productionState());
        assertTrue(report.lineup().complete());
        assertEquals(2, report.lineup().filledSlots());
        assertEquals(new BigDecimal("4.0"), report.lineup().totalPoints());
        assertEquals("p1", report.lineup().assignments().get(0).playerId());
        assertEquals("p2", report.lineup().assignments().get(1).playerId());
    }

    @Test
    void providerNativeLanePreservesExactDecimalsAndEvidenceProvenance() throws Exception {
        Fixture fixture = providerReadyFixture(Map.of(
            "s1", new BigDecimal("12.3400"),
            "s2", new BigDecimal("7.50")));

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, report.scoringLane());
        assertEquals(HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID, report.scoringPolicyId());
        assertEquals(PROVIDER_DATE, report.providerPointsAsOf());
        assertEquals(SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE,
            report.providerPointsSourceSurface());
        assertEquals(PROVIDER_LEAGUE_ID, report.providerLeagueId());
        assertNull(report.productionCoverageAsOf());
        assertNull(report.productionSourceUri());
        assertEquals("12.3400", report.playerScores().get(0).fantasyPoints().toPlainString());
        assertEquals("7.50", report.playerScores().get(1).fantasyPoints().toPlainString());
        assertTrue(report.playerScores().stream().allMatch(score ->
            score.scoringLane() == HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE
                && score.providerPointsEvidenceId() != null
                && score.productionId() == null
                && score.productionCoverageAsOf() == null
                && HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID.equals(score.scoringPolicyId())));
        assertTrue(report.lineup().complete());
        assertEquals("19.8400", report.lineup().totalPoints().toPlainString());
    }

    @Test
    void completeProviderNativeLaneWinsWithoutConsultingAvailableNflverseScores() throws Exception {
        Fixture fixture = providerReadyFixture(Map.of(
            "s1", new BigDecimal("1.0"),
            "s2", new BigDecimal("2.0")));
        fixture.saveCoverage(List.of("p1", "p2"));
        fixture.saveProduction("p1", COVERAGE_DATE, 5);

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, report.scoringLane());
        assertEquals("3.0", report.lineup().totalPoints().toPlainString());
        assertNull(report.productionCoverageAsOf());
        assertTrue(report.playerScores().stream().allMatch(score -> score.productionId() == null));
    }

    @Test
    void incompleteProviderNativeLaneNeverFallsBackToCompleteNflverseEvidence() throws Exception {
        Fixture fixture = baseFixture();
        fixture.saveConfiguration(Map.of("pass_td", 4.0));
        fixture.saveRoster();
        fixture.saveEligibility("p1", List.of("QB"));
        fixture.saveEligibility("p2", List.of("WR"));
        fixture.saveProviderPoints(Map.of("s1", new BigDecimal("1.0")), PROVIDER_DATE);
        fixture.saveCoverage(List.of("p1", "p2"));
        fixture.saveProduction("p1", COVERAGE_DATE, 5);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> fixture.analyzer().analyze("l1", "t1", 2026, 3));

        assertTrue(error.getMessage().contains("Missing provider-points identities"));
        assertTrue(error.getMessage().contains("s2"));
    }

    @Test
    void providerSnapshotMovementAfterCoverageInvalidatesPotentialLineup() throws Exception {
        Fixture fixture = providerReadyFixture(Map.of(
            "s1", new BigDecimal("1.0"),
            "s2", new BigDecimal("2.0")));
        var coverage = new LeagueTeamWeekPotentialLineupCoverageAnalyzer(fixture.database())
            .analyze("l1", "t1", 2026, 3);
        assertTrue(coverage.ready());

        fixture.saveProviderPoints(Map.of(
            "s1", new BigDecimal("11.0"),
            "s2", new BigDecimal("12.0")), PROVIDER_DATE.plusDays(1));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> fixture.analyzer().analyze(coverage));

        assertTrue(error.getMessage().contains("Provider-points snapshot moved after readiness check"));
    }

    @Test
    void scoresProductionFromCoverageDateInsteadOfNewerSnapshot() throws Exception {
        Fixture fixture = readyFixture();
        fixture.saveProduction("p1", COVERAGE_DATE, 1);
        fixture.saveProduction("p1", COVERAGE_DATE.plusDays(1), 3);

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertEquals(new BigDecimal("4.0"), report.playerScores().get(0).fantasyPoints());
        assertEquals(COVERAGE_DATE, report.playerScores().get(0).productionCoverageAsOf());
        assertEquals(URI.create("https://example.test/week.csv"), report.productionSourceUri());
        assertEquals(new BigDecimal("4.0"), report.lineup().totalPoints());
    }

    @Test
    void refusesToCalculateWhenCoverageIsBlocked() throws Exception {
        Fixture fixture = baseFixture();
        fixture.saveConfiguration(Map.of("pass_td", 4.0));
        fixture.saveRoster();
        fixture.saveEligibility("p2", List.of("WR"));
        fixture.saveCoverage(List.of("p1", "p2"));
        fixture.saveProduction("p1", COVERAGE_DATE, 1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> fixture.analyzer().analyze("l1", "t1", 2026, 3));

        assertTrue(error.getMessage().contains("No Sleeper fantasy-position observation for player p1"));
    }

    private Fixture readyFixture() throws Exception {
        Fixture fixture = baseFixture();
        fixture.saveConfiguration(Map.of("pass_td", 4.0));
        fixture.saveRoster();
        fixture.saveEligibility("p1", List.of("QB"));
        fixture.saveEligibility("p2", List.of("WR"));
        fixture.saveCoverage(List.of("p1", "p2"));
        return fixture;
    }

    private Fixture providerReadyFixture(Map<String, BigDecimal> points) throws Exception {
        Fixture fixture = baseFixture();
        fixture.saveConfiguration(Map.of(
            "pass_td", 4.0,
            "pass_int_td", -2.0,
            "pass_td_40p", 2.0));
        fixture.saveRoster();
        fixture.saveEligibility("p1", List.of("QB"));
        fixture.saveEligibility("p2", List.of("WR"));
        fixture.saveProviderPoints(points, PROVIDER_DATE);
        return fixture;
    }

    private Fixture baseFixture() throws Exception {
        Database database = new Database(tempDir.resolve("potential-lineup.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "Team One"));
        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "s1", "Quarterback", "QB", "CHI"));
        players.save(new Player("p2", "s2", "Receiver", "WR", "DET"));
        return new Fixture(database);
    }

    private static final LocalDate OBSERVATION_DATE = LocalDate.of(2026, 9, 5);
    private static final LocalDate ROSTER_DATE = LocalDate.of(2026, 9, 5);
    private static final LocalDate COVERAGE_DATE = LocalDate.of(2026, 9, 5);
    private static final LocalDate PROVIDER_DATE = LocalDate.of(2026, 9, 6);
    private static final String PROVIDER_LEAGUE_ID = "provider-l1";

    private record Fixture(Database database) {
        LeagueTeamWeekPotentialLineupAnalyzer analyzer() {
            return new LeagueTeamWeekPotentialLineupAnalyzer(database);
        }

        void saveConfiguration(Map<String, Double> scoring) throws Exception {
            new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
                "l1", "sleeper", OBSERVATION_DATE, 2026,
                List.of("QB", "FLEX", "BN"), scoring));
        }

        void saveRoster() throws Exception {
            new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
                "l1", "t1", 2026, 3, List.of("s1", "s2"), List.of("s1"),
                "sleeper", ROSTER_DATE));
        }

        void saveEligibility(String playerId, List<String> positions) throws Exception {
            new PlayerFantasyPositionObservationRepository(database).replace(
                new PlayerFantasyPositionObservation(playerId, "sleeper", OBSERVATION_DATE, positions));
        }

        void saveProviderPoints(Map<String, BigDecimal> pointsByProviderId, LocalDate asOfDate) throws Exception {
            List<ProviderPlayerWeekPointsEvidence> rows = new ArrayList<>();
            pointsByProviderId.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> rows.add(ProviderPlayerWeekPointsEvidence.create(
                    "l1",
                    "t1",
                    "1",
                    PROVIDER_LEAGUE_ID,
                    2026,
                    3,
                    entry.getKey(),
                    entry.getValue(),
                    "sleeper",
                    SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE,
                    asOfDate)));
            new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
                "l1", 2026, "sleeper", asOfDate, rows);
        }

        void saveCoverage(List<String> identityCoveredPlayerIds) throws Exception {
            new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
                2026, 3, "nflverse", URI.create("https://example.test/week.csv"), COVERAGE_DATE,
                50, 1, 0, identityCoveredPlayerIds));
        }

        void saveProduction(String playerId, LocalDate asOfDate, int passingTouchdowns) throws Exception {
            new PlayerWeekProductionRepository(database).save(PlayerWeekProduction.create(
                playerId, 2026, 3,
                0, passingTouchdowns, 0,
                0, 0, 0,
                0, 0, 0,
                "nflverse", asOfDate));
        }
    }
}
