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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueTeamWeekStartedLineupEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void scoresExactOrderedStartersWithGovernedProductionEvidence() throws Exception {
        Fixture fixture = readyFixture(List.of("s1", "s2"));
        fixture.saveProduction("p1", COVERAGE_DATE, 1);

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertEquals(LeagueTeamWeekStartedLineupEvidenceAnalyzer.METRIC_SCOPE, report.metricScope());
        assertEquals(HistoricalScoringLaneSelector.POLICY_ID, report.scoringLaneSelectionPolicyId());
        assertEquals(HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT, report.scoringLane());
        assertEquals(CoveredProductionScoringPolicy.POLICY_ID, report.scoringPolicyId());
        assertEquals(2, report.requiredSlots());
        assertEquals(2, report.filledSlots());
        assertTrue(report.complete());
        assertEquals(new BigDecimal("4.0"), report.totalStartedPoints());
        assertEquals("QB", report.slots().get(0).slot());
        assertEquals("s1", report.slots().get(0).providerStarterId());
        assertEquals("p1", report.slots().get(0).playerId());
        assertEquals(new BigDecimal("4.0"), report.slots().get(0).fantasyPoints());
        assertEquals(LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.OBSERVED,
            report.slots().get(0).scoreEvidence().productionState());
        assertEquals("WR", report.slots().get(1).slot());
        assertEquals("s2", report.slots().get(1).providerStarterId());
        assertEquals(BigDecimal.ZERO, report.slots().get(1).fantasyPoints());
        assertEquals(LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.IDENTITY_COVERED_ZERO,
            report.slots().get(1).scoreEvidence().productionState());
        assertEquals(COVERAGE_DATE, report.productionCoverageAsOf());
        assertEquals(URI.create("https://example.test/week.csv"), report.productionSourceUri());
        assertEquals(null, report.providerPointsAsOf());
        assertEquals(null, report.providerPointsSourceSurface());
        assertEquals(null, report.providerLeagueId());
    }

    @Test
    void scoresExactOrderedStartersWithReadyProviderNativeEvidence() throws Exception {
        Fixture fixture = readyFixture(List.of("s1", "s2"));
        fixture.saveProviderPoints();

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertEquals(HistoricalScoringLaneSelector.POLICY_ID, report.scoringLaneSelectionPolicyId());
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, report.scoringLane());
        assertEquals(HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID,
            report.scoringPolicyId());
        assertEquals(new BigDecimal("20.75"), report.totalStartedPoints());
        assertEquals(new BigDecimal("12.5"), report.slots().get(0).fantasyPoints());
        assertEquals(new BigDecimal("8.25"), report.slots().get(1).fantasyPoints());
        assertEquals(LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.NOT_EVALUATED,
            report.slots().get(0).scoreEvidence().productionState());
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE,
            report.slots().get(0).scoreEvidence().scoringLane());
        assertEquals(HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID,
            report.slots().get(0).scoreEvidence().scoringPolicyId());
        assertTrue(report.slots().get(0).scoreEvidence().providerPointsEvidenceId() != null);
        assertEquals(null, report.productionCoverageAsOf());
        assertEquals(null, report.productionSourceUri());
        assertEquals(PROVIDER_DATE, report.providerPointsAsOf());
        assertEquals(SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE,
            report.providerPointsSourceSurface());
        assertEquals(PROVIDER_LEAGUE_ID, report.providerLeagueId());
    }

    @Test
    void blockedProviderNativeSeasonNeverFallsBackToReadyNflverseEvidence() throws Exception {
        Fixture fixture = readyFixture(List.of("s1", "s2"));
        fixture.saveProduction("p1", COVERAGE_DATE, 1);
        fixture.saveIncompleteProviderPoints();

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> fixture.analyzer().analyze("l1", "t1", 2026, 3));

        assertTrue(error.getMessage().contains("Potential lineup unavailable"));
        assertTrue(error.getMessage().contains("Provider-native season audit"));
        assertTrue(error.getMessage().contains("Missing provider-points identities: [s2]"));
    }

    @Test
    void preservesSleeperZeroAsExplicitEmptySlotInsteadOfPlayerZeroProduction() throws Exception {
        Fixture fixture = readyFixture(List.of("s1", "0"));
        fixture.saveProduction("p1", COVERAGE_DATE, 1);

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertEquals(1, report.filledSlots());
        assertEquals(2, report.requiredSlots());
        assertFalse(report.complete());
        assertEquals(new BigDecimal("4.0"), report.totalStartedPoints());
        var empty = report.slots().get(1);
        assertEquals(LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedSlotState.EMPTY, empty.state());
        assertEquals("0", empty.providerStarterId());
        assertEquals(null, empty.playerId());
        assertEquals(null, empty.scoreEvidence());
        assertEquals(BigDecimal.ZERO, empty.fantasyPoints());
    }

    @Test
    void refusesToGuessWhenOrderedStarterCountDoesNotMatchStartingSlots() throws Exception {
        Fixture fixture = readyFixture(List.of("s1"));
        fixture.saveProduction("p1", COVERAGE_DATE, 1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> fixture.analyzer().analyze("l1", "t1", 2026, 3));

        assertTrue(error.getMessage().contains("starter count 1"));
        assertTrue(error.getMessage().contains("starting-slot count 2"));
    }

    @Test
    void refusesStarterThatIsNotInExactTeamWeekRosterSnapshot() throws Exception {
        Fixture fixture = readyFixture(List.of("s1", "s3"));
        fixture.saveProduction("p1", COVERAGE_DATE, 1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> fixture.analyzer().analyze("l1", "t1", 2026, 3));

        assertTrue(error.getMessage().contains("starter s3"));
        assertTrue(error.getMessage().contains("not present in the exact team-week roster snapshot"));
    }

    @Test
    void refusesObservedStarterOrderThatViolatesProviderSlotEligibility() throws Exception {
        Fixture fixture = readyFixture(List.of("s2", "s1"));
        fixture.saveProduction("p1", COVERAGE_DATE, 1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> fixture.analyzer().analyze("l1", "t1", 2026, 3));

        assertTrue(error.getMessage().contains("starter s2"));
        assertTrue(error.getMessage().contains("ordered slot QB"));
    }

    private Fixture readyFixture(List<String> starterIds) throws Exception {
        Database database = new Database(tempDir.resolve("started-lineup.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "Team One"));
        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "s1", "Quarterback", "QB", "CHI"));
        players.save(new Player("p2", "s2", "Receiver", "WR", "DET"));

        Fixture fixture = new Fixture(database);
        fixture.saveConfiguration();
        fixture.saveRoster(starterIds);
        fixture.saveEligibility("p1", List.of("QB"));
        fixture.saveEligibility("p2", List.of("WR"));
        fixture.saveCoverage(List.of("p1", "p2"));
        return fixture;
    }

    private static final LocalDate OBSERVATION_DATE = LocalDate.of(2026, 9, 5);
    private static final LocalDate ROSTER_DATE = LocalDate.of(2026, 9, 5);
    private static final LocalDate COVERAGE_DATE = LocalDate.of(2026, 9, 5);
    private static final LocalDate PROVIDER_DATE = LocalDate.of(2026, 9, 6);
    private static final String PROVIDER_LEAGUE_ID = "provider-l1";

    private record Fixture(Database database) {
        LeagueTeamWeekStartedLineupEvidenceAnalyzer analyzer() {
            return new LeagueTeamWeekStartedLineupEvidenceAnalyzer(database);
        }

        void saveConfiguration() throws Exception {
            new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
                "l1", "sleeper", OBSERVATION_DATE, 2026,
                List.of("QB", "WR", "BN"), Map.of("pass_td", 4.0)));
        }

        void saveRoster(List<String> starterIds) throws Exception {
            new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
                "l1", "t1", 2026, 3, List.of("s1", "s2"), starterIds,
                "sleeper", ROSTER_DATE));
        }

        void saveEligibility(String playerId, List<String> positions) throws Exception {
            new PlayerFantasyPositionObservationRepository(database).replace(
                new PlayerFantasyPositionObservation(playerId, "sleeper", OBSERVATION_DATE, positions));
        }

        void saveProviderPoints() throws Exception {
            replaceProviderPoints(List.of(
                providerPoints("s1", new BigDecimal("12.5")),
                providerPoints("s2", new BigDecimal("8.25"))));
        }

        void saveIncompleteProviderPoints() throws Exception {
            replaceProviderPoints(List.of(providerPoints("s1", new BigDecimal("12.5"))));
        }

        private ProviderPlayerWeekPointsEvidence providerPoints(String providerPlayerId, BigDecimal points) {
            return ProviderPlayerWeekPointsEvidence.create(
                "l1", "t1", "1", PROVIDER_LEAGUE_ID, 2026, 3, providerPlayerId,
                points, "sleeper", SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE,
                PROVIDER_DATE);
        }

        private void replaceProviderPoints(List<ProviderPlayerWeekPointsEvidence> rows) throws Exception {
            new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
                "l1", 2026, "sleeper", PROVIDER_DATE, rows);
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
