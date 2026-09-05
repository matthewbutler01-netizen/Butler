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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LeagueTeamSeasonLineupCaptureEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void usesRatioOfComparableTotalsAndPreservesEveryObservedWeekState() throws Exception {
        Fixture fixture = initializedFixture("full");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();

        fixture.saveRoster(1, List.of("s1", "s2", "s3"), List.of("s1", "s2"));
        fixture.saveCoverage(1, List.of("p1", "p2", "p3"));
        fixture.savePositiveProduction("p1", 1, 1, 0);
        fixture.savePositiveProduction("p2", 1, 0, 1);
        fixture.savePositiveProduction("p3", 1, 0, 2);

        fixture.saveRoster(2, List.of("s1", "s2", "s3"), List.of("s1", "s3"));
        fixture.saveCoverage(2, List.of("p1", "p2", "p3"));
        fixture.savePositiveProduction("p1", 2, 2, 0);
        fixture.savePositiveProduction("p2", 2, 0, 1);
        fixture.savePositiveProduction("p3", 2, 0, 2);

        fixture.saveRoster(3, List.of("s1"), List.of("s1", "0"));
        fixture.saveCoverage(3, List.of("p1"));
        fixture.savePositiveProduction("p1", 3, 1, 0);

        fixture.saveRoster(4, List.of("s1", "s2"), List.of("s1", "0"));
        fixture.saveCoverage(4, List.of("p1", "p2"));
        fixture.savePositiveProduction("p1", 4, 1, 0);
        fixture.savePositiveProduction("p2", 4, 0, 1);

        fixture.saveRoster(5, List.of("s1", "s2"), List.of("s1", "s2"));

        var report = fixture.analyzer().analyze("l1", "t1", 2026);
        var source = report.sourceSeasonPointsGap();

        assertEquals(LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.POLICY_ID, report.policyId());
        assertEquals(LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.METRIC_SCOPE, report.metricScope());
        assertEquals(LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.CaptureRateState.AVAILABLE, report.rateState());
        assertEquals(new BigDecimal("0.833333"), report.lineupCaptureRate().orElseThrow());
        assertEquals(5, source.aggregate().observedWeeks());
        assertEquals(1, source.aggregate().blockedWeeks());
        assertEquals(1, source.aggregate().potentialIncompleteWeeks());
        assertEquals(1, source.aggregate().startedIncompleteWeeks());
        assertEquals(2, source.aggregate().comparableCompleteWeeks());
        assertEquals(new BigDecimal("30.0"), source.aggregate().comparableTotalStartedPoints().orElseThrow());
        assertEquals(new BigDecimal("36.0"), source.aggregate().comparableTotalPotentialPoints().orElseThrow());
        assertEquals(new BigDecimal("6.0"), source.aggregate().comparableTotalPointsGap().orElseThrow());
        assertEquals(List.of(
                LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.COMPARABLE_COMPLETE,
                LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.COMPARABLE_COMPLETE,
                LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.POTENTIAL_INCOMPLETE,
                LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.STARTED_INCOMPLETE,
                LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.BLOCKED),
            source.weeks().stream().map(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekEvidence::state).toList());
    }

    @Test
    void zeroPotentialComparableWeekDoesNotBlockPositiveSeasonTotal() throws Exception {
        Fixture fixture = initializedFixture("mixed-zero");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();

        fixture.saveRoster(1, List.of("s1", "s2", "s3"), List.of("s1", "s2"));
        fixture.saveCoverage(1, List.of("p1", "p2", "p3"));
        fixture.savePositiveProduction("p1", 1, 1, 0);
        fixture.savePositiveProduction("p2", 1, 0, 1);
        fixture.savePositiveProduction("p3", 1, 0, 2);

        fixture.saveRoster(2, List.of("s1", "s2", "s3"), List.of("s1", "s2"));
        fixture.saveCoverage(2, List.of("p1", "p2", "p3"));
        fixture.savePositiveProduction("p1", 2, 0, 0);
        fixture.savePositiveProduction("p2", 2, 0, 0);
        fixture.savePositiveProduction("p3", 2, 0, 0);

        var report = fixture.analyzer().analyze("l1", "t1", 2026);

        assertEquals(LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.CaptureRateState.AVAILABLE, report.rateState());
        assertEquals(2, report.sourceSeasonPointsGap().aggregate().comparableCompleteWeeks());
        assertEquals(new BigDecimal("10.0"),
            report.sourceSeasonPointsGap().aggregate().comparableTotalStartedPoints().orElseThrow());
        assertEquals(new BigDecimal("16.0"),
            report.sourceSeasonPointsGap().aggregate().comparableTotalPotentialPoints().orElseThrow());
        assertEquals(new BigDecimal("0.625000"), report.lineupCaptureRate().orElseThrow());
    }

    @Test
    void withholdsRateWhenNoComparableWeeksExist() throws Exception {
        Fixture fixture = initializedFixture("empty");

        var report = fixture.analyzer().analyze("l1", "t1", 2026);

        assertEquals(
            LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.CaptureRateState.UNAVAILABLE_NO_COMPARABLE_WEEKS,
            report.rateState());
        assertFalse(report.lineupCaptureRate().isPresent());
        assertEquals(0, report.sourceSeasonPointsGap().aggregate().observedWeeks());
    }

    @Test
    void withholdsRateWhenComparableTotalPotentialIsZero() throws Exception {
        Fixture fixture = initializedFixture("zero-total");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();
        fixture.saveRoster(1, List.of("s1", "s2", "s3"), List.of("s1", "s2"));
        fixture.saveCoverage(1, List.of("p1", "p2", "p3"));
        fixture.savePositiveProduction("p1", 1, 0, 0);
        fixture.savePositiveProduction("p2", 1, 0, 0);
        fixture.savePositiveProduction("p3", 1, 0, 0);

        var report = fixture.analyzer().analyze("l1", "t1", 2026);

        assertEquals(
            LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.CaptureRateState.UNAVAILABLE_ZERO_TOTAL_POTENTIAL,
            report.rateState());
        assertFalse(report.lineupCaptureRate().isPresent());
        assertEquals(1, report.sourceSeasonPointsGap().aggregate().comparableCompleteWeeks());
    }

    @Test
    void withholdsRateWhenAComparableWeekHasNegativePointTotals() throws Exception {
        Fixture fixture = initializedFixture("negative");
        fixture.saveConfiguration(Map.of("pass_int", -2.0, "fum_lost", -2.0));
        fixture.saveEligibility();
        fixture.saveRoster(1, List.of("s1", "s2", "s3"), List.of("s1", "s2"));
        fixture.saveCoverage(1, List.of("p1", "p2", "p3"));
        fixture.saveNegativeProduction("p1", 1, true);
        fixture.saveNegativeProduction("p2", 1, false);
        fixture.saveNegativeProduction("p3", 1, false);

        var report = fixture.analyzer().analyze("l1", "t1", 2026);

        assertEquals(
            LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.CaptureRateState.UNAVAILABLE_NEGATIVE_COMPARABLE_POINTS,
            report.rateState());
        assertFalse(report.lineupCaptureRate().isPresent());
        assertEquals(new BigDecimal("-4.0"),
            report.sourceSeasonPointsGap().aggregate().comparableTotalStartedPoints().orElseThrow());
        assertEquals(new BigDecimal("-4.0"),
            report.sourceSeasonPointsGap().aggregate().comparableTotalPotentialPoints().orElseThrow());
    }

    @Test
    void reportRejectsRateThatDoesNotMatchComparableGovernedTotals() throws Exception {
        Fixture fixture = initializedFixture("wrong-rate");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();
        fixture.saveRoster(1, List.of("s1", "s2", "s3"), List.of("s1", "s2"));
        fixture.saveCoverage(1, List.of("p1", "p2", "p3"));
        fixture.savePositiveProduction("p1", 1, 1, 0);
        fixture.savePositiveProduction("p2", 1, 0, 1);
        fixture.savePositiveProduction("p3", 1, 0, 2);
        var source = new LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer(fixture.database())
            .analyze("l1", "t1", 2026);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.SeasonLineupCaptureReport(
                LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.POLICY_ID,
                LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.METRIC_SCOPE,
                source,
                LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.CaptureRateState.AVAILABLE,
                Optional.of(new BigDecimal("0.700000"))));

        assertEquals(
            "lineupCaptureRate must equal comparable governed started total divided by potential total at v1 precision",
            error.getMessage());
    }

    private Fixture initializedFixture(String name) throws Exception {
        Database database = new Database(tempDir.resolve("season-capture-" + name + ".db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "Team One"));
        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "s1", "Quarterback", "QB", "CHI"));
        players.save(new Player("p2", "s2", "Receiver Two", "WR", "DET"));
        players.save(new Player("p3", "s3", "Receiver Three", "WR", "MIN"));
        return new Fixture(database);
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);

    private record Fixture(Database database) {
        LeagueTeamSeasonLineupCaptureEvidenceAnalyzer analyzer() {
            return new LeagueTeamSeasonLineupCaptureEvidenceAnalyzer(database);
        }

        void saveConfiguration(Map<String, Double> scoring) throws Exception {
            new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
                "l1", "sleeper", AS_OF, 2026, List.of("QB", "WR", "BN"), scoring));
        }

        void saveEligibility() throws Exception {
            PlayerFantasyPositionObservationRepository repository =
                new PlayerFantasyPositionObservationRepository(database);
            repository.replace(new PlayerFantasyPositionObservation("p1", "sleeper", AS_OF, List.of("QB")));
            repository.replace(new PlayerFantasyPositionObservation("p2", "sleeper", AS_OF, List.of("WR")));
            repository.replace(new PlayerFantasyPositionObservation("p3", "sleeper", AS_OF, List.of("WR")));
        }

        void saveRoster(int week, List<String> providerPlayerIds, List<String> starterIds) throws Exception {
            new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
                "l1", "t1", 2026, week, providerPlayerIds, starterIds, "sleeper", AS_OF));
        }

        void saveCoverage(int week, List<String> identityCoveredPlayerIds) throws Exception {
            new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
                2026, week, "nflverse", URI.create("https://example.test/stats_player_week_2026.csv"),
                AS_OF, 50, identityCoveredPlayerIds.size(), 0, identityCoveredPlayerIds));
        }

        void savePositiveProduction(String playerId, int week, int passingTouchdowns, int receivingTouchdowns)
            throws Exception {
            new PlayerWeekProductionRepository(database).save(PlayerWeekProduction.create(
                playerId, 2026, week,
                0, passingTouchdowns, 0,
                0, 0,
                0, 0, receivingTouchdowns,
                0, "nflverse", AS_OF));
        }

        void saveNegativeProduction(String playerId, int week, boolean quarterback) throws Exception {
            new PlayerWeekProductionRepository(database).save(PlayerWeekProduction.create(
                playerId, 2026, week,
                0, 0, quarterback ? 1 : 0,
                0, 0,
                0, 0, 0,
                quarterback ? 0 : 1, "nflverse", AS_OF));
        }
    }
}
