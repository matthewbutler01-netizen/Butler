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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueTeamWeekPotentialLineupCoverageAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void readyWhenRosterPlayersHaveObservedProductionOrImportTimeIdentityCoveredZero() throws Exception {
        Fixture fixture = fixture();
        fixture.saveConfiguration(2026, List.of("QB", "FLEX", "BN"), Map.of("pass_td", 4.0, "rec", 1.0));
        fixture.saveRoster(List.of("s1", "s2"));
        fixture.saveEligibility("p1", List.of("QB"));
        fixture.saveEligibility("p2", List.of("WR"));
        fixture.saveCoverage(List.of("p1", "p2"));
        fixture.saveProduction("p1", COVERAGE_DATE, 1, 0);

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertTrue(report.ready());
        assertEquals(HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT, report.scoringLane());
        assertEquals(LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE, report.metricScope());
        assertEquals(COVERAGE_DATE, report.productionCoverageAsOf());
        assertNull(report.providerPointsAsOf());
        assertEquals(2, report.players().size());
        assertEquals(LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.OBSERVED,
            report.players().get(0).productionState());
        assertEquals(LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.IDENTITY_COVERED_ZERO,
            report.players().get(1).productionState());
        assertTrue(report.blockers().isEmpty());
    }

    @Test
    void completeProviderNativeLaneBypassesOnlyNflverseScoringRepresentability() throws Exception {
        Fixture fixture = fixture();
        fixture.saveConfiguration(2026, List.of("QB", "FLEX", "BN"),
            Map.of("pass_td", 4.0, "pass_int_td", -2.0, "pass_td_40p", 2.0));
        fixture.saveRoster(List.of("s1", "s2"));
        fixture.saveEligibility("p1", List.of("QB"));
        fixture.saveEligibility("p2", List.of("WR"));
        fixture.saveProviderPoints(Map.of(
            "s1", new BigDecimal("12.3400"),
            "s2", new BigDecimal("7.50")));

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertTrue(report.ready());
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, report.scoringLane());
        assertEquals(PROVIDER_DATE, report.providerPointsAsOf());
        assertEquals(SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE,
            report.providerPointsSourceSurface());
        assertEquals(PROVIDER_LEAGUE_ID, report.providerLeagueId());
        assertNull(report.productionCoverageAsOf());
        assertNull(report.productionSourceUri());
        assertEquals("12.3400", report.players().get(0).providerFantasyPoints().toPlainString());
        assertTrue(report.players().stream().allMatch(player ->
            player.providerPointsEvidenceId() != null
                && player.productionState() == LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.NOT_EVALUATED));
        assertFalse(report.blockers().stream().anyMatch(value -> value.contains("Unsupported nonzero observed scoring rule")));
    }

    @Test
    void incompleteProviderNativeLaneBlocksAtomicallyInsteadOfFallingBackToNflverse() throws Exception {
        Fixture fixture = fixture();
        fixture.saveConfiguration(2026, List.of("QB", "FLEX"), Map.of("pass_td", 4.0));
        fixture.saveRoster(List.of("s1", "s2"));
        fixture.saveEligibility("p1", List.of("QB"));
        fixture.saveEligibility("p2", List.of("WR"));
        fixture.saveProviderPoints(Map.of("s1", new BigDecimal("12.0")));
        fixture.saveCoverage(List.of("p1", "p2"));
        fixture.saveProduction("p1", COVERAGE_DATE, 3, 0);
        fixture.saveProduction("p2", COVERAGE_DATE, 0, 1);

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertFalse(report.ready());
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, report.scoringLane());
        assertNull(report.productionCoverageAsOf());
        assertTrue(report.blockers().stream().anyMatch(value ->
            value.contains("Missing provider-points identities") && value.contains("s2")));
        assertFalse(report.players().stream().anyMatch(player ->
            player.productionState() == LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.OBSERVED
                || player.productionState()
                    == LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.IDENTITY_COVERED_ZERO));
    }

    @Test
    void providerNativeLaneStillBlocksUnsupportedLineupSlots() throws Exception {
        Fixture fixture = fixture();
        fixture.saveConfiguration(2026, List.of("QB", "REC_FLEX"), Map.of("pass_int_td", -2.0));
        fixture.saveRoster(List.of("s1", "s2"));
        fixture.saveEligibility("p1", List.of("QB"));
        fixture.saveEligibility("p2", List.of("WR"));
        fixture.saveProviderPoints(Map.of(
            "s1", new BigDecimal("10.0"),
            "s2", new BigDecimal("5.0")));

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertFalse(report.ready());
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, report.scoringLane());
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("REC_FLEX")));
        assertFalse(report.blockers().stream().anyMatch(value -> value.contains("pass_int_td")));
    }

    @Test
    void providerNativeLaneStillRequiresProviderDeclaredEligibility() throws Exception {
        Fixture fixture = fixture();
        fixture.saveConfiguration(2026, List.of("QB", "FLEX"), Map.of("pass_int_td", -2.0));
        fixture.saveRoster(List.of("s1", "s2"));
        fixture.saveEligibility("p2", List.of("WR"));
        fixture.saveProviderPoints(Map.of(
            "s1", new BigDecimal("10.0"),
            "s2", new BigDecimal("5.0")));

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertFalse(report.ready());
        assertTrue(report.blockers().stream().anyMatch(value ->
            value.contains("No Sleeper fantasy-position observation for player p1")));
    }

    @Test
    void newerProductionSnapshotCannotSatisfyOlderCoverageSnapshot() throws Exception {
        Fixture fixture = fixture();
        fixture.saveConfiguration(2026, List.of("QB"), Map.of("pass_td", 4.0));
        fixture.saveRoster(List.of("s1"));
        fixture.saveEligibility("p1", List.of("QB"));
        fixture.saveCoverage(List.of());
        fixture.saveProduction("p1", COVERAGE_DATE.plusDays(1), 2, 0);

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertFalse(report.ready());
        assertEquals(LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.IDENTITY_NOT_COVERED,
            report.players().getFirst().productionState());
        assertTrue(report.blockers().stream().anyMatch(value ->
            value.contains("No exact production row or import-time identity coverage")));
    }

    @Test
    void missingEligibilityObservationBlocksInsteadOfFallingBackToPrimaryPosition() throws Exception {
        Fixture fixture = fixture();
        fixture.saveConfiguration(2026, List.of("QB"), Map.of("pass_td", 4.0));
        fixture.saveRoster(List.of("s1"));
        fixture.saveCoverage(List.of("p1"));
        fixture.saveProduction("p1", COVERAGE_DATE, 1, 0);

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertFalse(report.ready());
        assertTrue(report.players().getFirst().providerFantasyPositions().isEmpty());
        assertTrue(report.blockers().stream().anyMatch(value ->
            value.contains("No Sleeper fantasy-position observation")));
    }

    @Test
    void wrongSeasonOrUnsupportedObservedRulesBlockReadiness() throws Exception {
        Fixture fixture = fixture();
        fixture.saveConfiguration(2025, List.of("QB"), Map.of("pass_td", 4.0));
        fixture.saveRoster(List.of("s1"));
        fixture.saveEligibility("p1", List.of("QB"));
        fixture.saveCoverage(List.of("p1"));
        fixture.saveProduction("p1", COVERAGE_DATE, 1, 0);

        var wrongSeason = fixture.analyzer().analyze("l1", "t1", 2026, 3);
        assertFalse(wrongSeason.ready());
        assertTrue(wrongSeason.blockers().stream().anyMatch(value ->
            value.contains("No Sleeper league configuration observation for requested season 2026")));

        fixture.saveConfiguration(2026, List.of("QB", "REC_FLEX"), Map.of("pass_td", 4.0, "bonus", 1.0));
        var unsupported = fixture.analyzer().analyze("l1", "t1", 2026, 3);
        assertFalse(unsupported.ready());
        assertTrue(unsupported.blockers().stream().anyMatch(value -> value.contains("REC_FLEX")));
        assertTrue(unsupported.blockers().stream().anyMatch(value -> value.contains("bonus")));
    }

    private Fixture fixture() throws Exception {
        Database database = new Database(tempDir.resolve("potential-lineup-coverage.db"));
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
        LeagueTeamWeekPotentialLineupCoverageAnalyzer analyzer() {
            return new LeagueTeamWeekPotentialLineupCoverageAnalyzer(database);
        }

        void saveConfiguration(int season, List<String> slots, Map<String, Double> scoring) throws Exception {
            new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
                "l1", "sleeper", OBSERVATION_DATE, season, slots, scoring));
        }

        void saveRoster(List<String> providerPlayerIds) throws Exception {
            new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
                "l1", "t1", 2026, 3, providerPlayerIds, List.of(), "sleeper", ROSTER_DATE));
        }

        void saveEligibility(String playerId, List<String> positions) throws Exception {
            new PlayerFantasyPositionObservationRepository(database).replace(
                new PlayerFantasyPositionObservation(playerId, "sleeper", OBSERVATION_DATE, positions));
        }

        void saveProviderPoints(Map<String, BigDecimal> pointsByProviderId) throws Exception {
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
                    PROVIDER_DATE)));
            new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
                "l1", 2026, "sleeper", PROVIDER_DATE, rows);
        }

        void saveCoverage(List<String> identityCoveredPlayerIds) throws Exception {
            new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
                2026, 3, "nflverse", URI.create("https://example.test/week.csv"), COVERAGE_DATE,
                50, 1, 0, identityCoveredPlayerIds));
        }

        void saveProduction(String playerId, LocalDate asOfDate, int passingTouchdowns, int receptions)
            throws Exception {
            new PlayerWeekProductionRepository(database).save(PlayerWeekProduction.create(
                playerId, 2026, 3,
                0, passingTouchdowns, 0,
                0, 0, receptions,
                0, 0, 0,
                "nflverse", asOfDate));
        }
    }
}
