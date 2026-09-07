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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueTeamWeekLineupPointsGapEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void reportsPotentialMinusStartedPointsForCompleteGovernedLineups() throws Exception {
        Fixture fixture = fixture(List.of("s1", "s2"));

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertEquals(LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.METRIC_SCOPE, report.metricScope());
        assertEquals(LeagueTeamWeekPotentialLineupAnalyzer.POLICY_ID, report.potentialLineupPolicyId());
        assertEquals(LeagueTeamWeekStartedLineupEvidenceAnalyzer.POLICY_ID, report.startedLineupPolicyId());
        assertEquals(HistoricalScoringLaneSelector.POLICY_ID, report.scoringLaneSelectionPolicyId());
        assertEquals(HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT, report.scoringLane());
        assertEquals(CoveredProductionScoringPolicy.POLICY_ID, report.scoringPolicyId());
        assertEquals(OptimalLegalLineupSolver.POLICY_ID, report.solverPolicyId());
        assertEquals(LineupSlotEligibilityPolicy.POLICY_ID, report.eligibilityPolicyId());
        assertEquals(2, report.startingSlots());
        assertEquals(new BigDecimal("10.0"), report.startedPoints());
        assertEquals(new BigDecimal("16.0"), report.potentialPoints());
        assertEquals(new BigDecimal("6.0"), report.pointsGap());
        assertEquals(AS_OF, report.leagueConfigurationAsOf());
        assertEquals(AS_OF, report.rosterEvidenceAsOf());
        assertEquals(AS_OF, report.productionCoverageAsOf());
        assertEquals(URI.create("https://example.test/week.csv"), report.productionSourceUri());
        assertNull(report.providerPointsAsOf());
        assertNull(report.providerPointsSourceSurface());
        assertNull(report.providerLeagueId());
    }

    @Test
    void reportsZeroGapWhenObservedStartedLineupMatchesGovernedPotential() throws Exception {
        Fixture fixture = fixture(List.of("s1", "s3"));

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertEquals(new BigDecimal("16.0"), report.startedPoints());
        assertEquals(new BigDecimal("16.0"), report.potentialPoints());
        assertEquals(0, report.pointsGap().compareTo(BigDecimal.ZERO));
    }

    @Test
    void refusesGapWhenObservedStartedLineupIsIncomplete() throws Exception {
        Fixture fixture = fixture(List.of("s1", "0"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> fixture.analyzer().analyze("l1", "t1", 2026, 3));

        assertTrue(error.getMessage().contains("observed started lineup is incomplete (1/2)"));
    }

    @Test
    void providerNativeLaneFlowsPersistedPointsThroughGapExactly() throws Exception {
        Fixture fixture = fixture(List.of("s1", "s2"));
        fixture.saveProviderPoints();

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertEquals(HistoricalScoringLaneSelector.POLICY_ID, report.scoringLaneSelectionPolicyId());
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, report.scoringLane());
        assertEquals(HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID, report.scoringPolicyId());
        assertEquals(new BigDecimal("10.0"), report.startedPoints());
        assertEquals(new BigDecimal("16.0"), report.potentialPoints());
        assertEquals(new BigDecimal("6.0"), report.pointsGap());
        assertEquals(PROVIDER_AS_OF, report.providerPointsAsOf());
        assertEquals(SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE,
            report.providerPointsSourceSurface());
        assertEquals("provider-l1", report.providerLeagueId());
        assertNull(report.productionCoverageAsOf());
        assertNull(report.productionSourceUri());
    }

    @Test
    void incompleteProviderNativeLaneNeverFallsBackToReadyNflverse() throws Exception {
        Fixture fixture = fixture(List.of("s1", "s2"));
        fixture.saveIncompleteProviderPoints();

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> fixture.analyzer().analyze("l1", "t1", 2026, 3));

        assertTrue(error.getMessage().contains("Missing provider-points identities"));
        assertTrue(error.getMessage().contains("s3"));
    }

    private Fixture fixture(List<String> starters) throws Exception {
        Database database = new Database(tempDir.resolve("lineup-gap.db"));
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
        new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
            "l1", "t1", 2026, 3, List.of("s1", "s2", "s3"), starters, "sleeper", AS_OF));

        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        eligibility.replace(new PlayerFantasyPositionObservation("p1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("p2", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("p3", "sleeper", AS_OF, List.of("WR")));

        new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
            2026, 3, "nflverse", URI.create("https://example.test/week.csv"), AS_OF,
            50, 3, 0, List.of("p1", "p2", "p3")));
        PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
        production.save(PlayerWeekProduction.create(
            "p1", 2026, 3,
            0, 1, 0,
            0, 0,
            0, 0, 0,
            0, "nflverse", AS_OF));
        production.save(PlayerWeekProduction.create(
            "p2", 2026, 3,
            0, 0, 0,
            0, 0,
            0, 0, 1,
            0, "nflverse", AS_OF));
        production.save(PlayerWeekProduction.create(
            "p3", 2026, 3,
            0, 0, 0,
            0, 0,
            0, 0, 2,
            0, "nflverse", AS_OF));
        return new Fixture(database);
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private static final LocalDate PROVIDER_AS_OF = LocalDate.of(2026, 9, 6);

    private record Fixture(Database database) {
        LeagueTeamWeekLineupPointsGapEvidenceAnalyzer analyzer() {
            return new LeagueTeamWeekLineupPointsGapEvidenceAnalyzer(database);
        }

        void saveProviderPoints() throws Exception {
            saveProviderPoints(List.of(
                providerPoints("s1", new BigDecimal("4.0")),
                providerPoints("s2", new BigDecimal("6.0")),
                providerPoints("s3", new BigDecimal("12.0"))));
        }

        void saveIncompleteProviderPoints() throws Exception {
            saveProviderPoints(List.of(
                providerPoints("s1", new BigDecimal("4.0")),
                providerPoints("s2", new BigDecimal("6.0"))));
        }

        private void saveProviderPoints(List<ProviderPlayerWeekPointsEvidence> rows) throws Exception {
            new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
                "l1", 2026, "sleeper", PROVIDER_AS_OF, rows);
        }

        private ProviderPlayerWeekPointsEvidence providerPoints(String providerPlayerId, BigDecimal points) {
            return ProviderPlayerWeekPointsEvidence.create(
                "l1", "t1", "1", "provider-l1", 2026, 3, providerPlayerId,
                points, "sleeper", SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE,
                PROVIDER_AS_OF);
        }
    }
}
