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

class LeagueTeamWeekLineupCaptureEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void reportsDescriptiveCaptureRateFromGovernedPointsGapEvidence() throws Exception {
        Fixture fixture = fixture(List.of("s1", "s2"), ScoreMode.POSITIVE);

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertEquals(LeagueTeamWeekLineupCaptureEvidenceAnalyzer.POLICY_ID, report.policyId());
        assertEquals(LeagueTeamWeekLineupCaptureEvidenceAnalyzer.METRIC_SCOPE, report.metricScope());
        assertEquals(
            LeagueTeamWeekLineupCaptureEvidenceAnalyzer.CaptureRateState.AVAILABLE,
            report.rateState());
        assertEquals(new BigDecimal("10.0"), report.sourcePointsGap().startedPoints());
        assertEquals(new BigDecimal("16.0"), report.sourcePointsGap().potentialPoints());
        assertEquals(new BigDecimal("6.0"), report.sourcePointsGap().pointsGap());
        assertEquals(new BigDecimal("0.625000"), report.lineupCaptureRate().orElseThrow());
    }

    @Test
    void reportsFullCaptureWhenStartedMatchesGovernedPotential() throws Exception {
        Fixture fixture = fixture(List.of("s1", "s3"), ScoreMode.POSITIVE);

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertEquals(
            LeagueTeamWeekLineupCaptureEvidenceAnalyzer.CaptureRateState.AVAILABLE,
            report.rateState());
        assertEquals(new BigDecimal("1.000000"), report.lineupCaptureRate().orElseThrow());
    }

    @Test
    void withholdsRateInsteadOfFabricatingPercentageWhenPotentialIsZero() throws Exception {
        Fixture fixture = fixture(List.of("s1", "s2"), ScoreMode.ZERO);

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertEquals(
            LeagueTeamWeekLineupCaptureEvidenceAnalyzer.CaptureRateState.UNAVAILABLE_ZERO_POTENTIAL,
            report.rateState());
        assertFalse(report.lineupCaptureRate().isPresent());
        assertEquals(0, report.sourcePointsGap().startedPoints().compareTo(BigDecimal.ZERO));
        assertEquals(0, report.sourcePointsGap().potentialPoints().compareTo(BigDecimal.ZERO));
        assertEquals(0, report.sourcePointsGap().pointsGap().compareTo(BigDecimal.ZERO));
    }

    @Test
    void withholdsRateForNegativePointTotalsWhileRetainingRawGapEvidence() throws Exception {
        Fixture fixture = fixture(List.of("s1", "s2"), ScoreMode.NEGATIVE);

        var report = fixture.analyzer().analyze("l1", "t1", 2026, 3);

        assertEquals(
            LeagueTeamWeekLineupCaptureEvidenceAnalyzer.CaptureRateState.UNAVAILABLE_NEGATIVE_POINTS,
            report.rateState());
        assertFalse(report.lineupCaptureRate().isPresent());
        assertEquals(new BigDecimal("-4.0"), report.sourcePointsGap().startedPoints());
        assertEquals(new BigDecimal("-4.0"), report.sourcePointsGap().potentialPoints());
        assertEquals(0, report.sourcePointsGap().pointsGap().compareTo(BigDecimal.ZERO));
    }

    @Test
    void reportRejectsCaptureRateThatDoesNotMatchGovernedSourceFraction() throws Exception {
        Fixture fixture = fixture(List.of("s1", "s2"), ScoreMode.POSITIVE);
        var source = new LeagueTeamWeekLineupPointsGapEvidenceAnalyzer(fixture.database())
            .analyze("l1", "t1", 2026, 3);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new LeagueTeamWeekLineupCaptureEvidenceAnalyzer.LineupCaptureReport(
                LeagueTeamWeekLineupCaptureEvidenceAnalyzer.POLICY_ID,
                LeagueTeamWeekLineupCaptureEvidenceAnalyzer.METRIC_SCOPE,
                source,
                LeagueTeamWeekLineupCaptureEvidenceAnalyzer.CaptureRateState.AVAILABLE,
                Optional.of(new BigDecimal("0.700000"))));

        assertEquals(
            "lineupCaptureRate must equal governed started points divided by potential points at v1 precision",
            error.getMessage());
    }

    private Fixture fixture(List<String> starters, ScoreMode scoreMode) throws Exception {
        Database database = new Database(tempDir.resolve("lineup-capture-" + scoreMode.name().toLowerCase() + ".db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "Team One"));
        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "s1", "Quarterback", "QB", "CHI"));
        players.save(new Player("p2", "s2", "Receiver Two", "WR", "DET"));
        players.save(new Player("p3", "s3", "Receiver Three", "WR", "MIN"));

        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            "l1", "sleeper", AS_OF, 2026,
            List.of("QB", "WR", "BN"), scoring(scoreMode)));
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
        production.save(production("p1", scoreMode, true, 1));
        production.save(production("p2", scoreMode, false, 1));
        production.save(production("p3", scoreMode, false, 2));
        return new Fixture(database);
    }

    private static Map<String, Double> scoring(ScoreMode scoreMode) {
        return switch (scoreMode) {
            case POSITIVE, ZERO -> Map.of("pass_td", 4.0, "rec_td", 6.0);
            case NEGATIVE -> Map.of("pass_int", -2.0, "fum_lost", -2.0);
        };
    }

    private static PlayerWeekProduction production(
        String playerId,
        ScoreMode scoreMode,
        boolean quarterback,
        int positiveTouchdowns) {
        return switch (scoreMode) {
            case POSITIVE -> PlayerWeekProduction.create(
                playerId, 2026, 3,
                0, quarterback ? positiveTouchdowns : 0, 0,
                0, 0,
                0, 0, quarterback ? 0 : positiveTouchdowns,
                0, "nflverse", AS_OF);
            case ZERO -> PlayerWeekProduction.create(
                playerId, 2026, 3,
                0, 0, 0,
                0, 0,
                0, 0, 0,
                0, "nflverse", AS_OF);
            case NEGATIVE -> PlayerWeekProduction.create(
                playerId, 2026, 3,
                0, 0, quarterback ? 1 : 0,
                0, 0,
                0, 0, 0,
                quarterback ? 0 : 1, "nflverse", AS_OF);
        };
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);

    private enum ScoreMode {
        POSITIVE,
        ZERO,
        NEGATIVE
    }

    private record Fixture(Database database) {
        LeagueTeamWeekLineupCaptureEvidenceAnalyzer analyzer() {
            return new LeagueTeamWeekLineupCaptureEvidenceAnalyzer(database);
        }
    }
}
