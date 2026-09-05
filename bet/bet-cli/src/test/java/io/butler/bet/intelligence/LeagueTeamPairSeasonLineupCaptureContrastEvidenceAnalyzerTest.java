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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void recalculatesBothTeamsOverExactSharedComparableWeekIntersection() throws Exception {
        Fixture fixture = initializedFixture("pair-shared.db");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();

        fixture.saveWeek(1,
            List.of("a1", "a2", "a3"), List.of("a1", "a2"),
            List.of("b1", "b2", "b3"), List.of("b1", "b2"));
        fixture.saveCoverage(1);
        fixture.saveProduction(1,
            new Production("pa1", 1, 0), new Production("pa2", 0, 1), new Production("pa3", 0, 2),
            new Production("pb1", 2, 0), new Production("pb2", 0, 1), new Production("pb3", 0, 2));

        fixture.saveWeek(2,
            List.of("a1", "a2", "a3"), List.of("a1", "a3"),
            List.of("b1", "b2", "b3"), List.of("b1", "b2"));
        fixture.saveCoverage(2);
        fixture.saveProduction(2,
            new Production("pa1", 2, 0), new Production("pa2", 0, 1), new Production("pa3", 0, 2),
            new Production("pb1", 1, 0), new Production("pb2", 0, 1), new Production("pb3", 0, 2));

        fixture.saveWeek(3,
            List.of("a1", "a2", "a3"), List.of("a1", "a2"),
            List.of("b1", "b2", "b3"), List.of("b1", "0"));
        fixture.saveCoverage(3);
        fixture.saveProduction(3,
            new Production("pa1", 1, 0), new Production("pa2", 0, 1), new Production("pa3", 0, 2),
            new Production("pb1", 1, 0), new Production("pb2", 0, 1), new Production("pb3", 0, 2));

        fixture.saveWeek(4,
            List.of("a1", "a2", "a3"), List.of("a1", "0"),
            List.of("b1", "b2", "b3"), List.of("b1", "b2"));
        fixture.saveCoverage(4);
        fixture.saveProduction(4,
            new Production("pa1", 1, 0), new Production("pa2", 0, 1), new Production("pa3", 0, 2),
            new Production("pb1", 1, 0), new Production("pb2", 0, 1), new Production("pb3", 0, 2));

        var report = fixture.analyzer().analyze("l1", "ta", "tb", 2026);

        assertEquals(LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer.POLICY_ID, report.policyId());
        assertEquals(List.of(1, 2), report.sharedComparableWeeks());
        assertEquals(List.of(3), report.teamAOnlyComparableWeeks());
        assertEquals(List.of(4), report.teamBOnlyComparableWeeks());

        assertEquals(4, report.teamA().observedWeeks());
        assertEquals(3, report.teamA().individuallyComparableWeeks());
        assertEquals(2, report.teamA().sharedComparableWeeks());
        assertEquals(new BigDecimal("30.0"), report.teamA().sharedTotalStartedPoints().orElseThrow());
        assertEquals(new BigDecimal("36.0"), report.teamA().sharedTotalPotentialPoints().orElseThrow());
        assertEquals(new BigDecimal("6.0"), report.teamA().sharedTotalPointsGap().orElseThrow());
        assertEquals(new BigDecimal("0.833333"), report.teamA().lineupCaptureRate().orElseThrow());

        assertEquals(4, report.teamB().observedWeeks());
        assertEquals(3, report.teamB().individuallyComparableWeeks());
        assertEquals(2, report.teamB().sharedComparableWeeks());
        assertEquals(new BigDecimal("24.0"), report.teamB().sharedTotalStartedPoints().orElseThrow());
        assertEquals(new BigDecimal("36.0"), report.teamB().sharedTotalPotentialPoints().orElseThrow());
        assertEquals(new BigDecimal("12.0"), report.teamB().sharedTotalPointsGap().orElseThrow());
        assertEquals(new BigDecimal("0.666667"), report.teamB().lineupCaptureRate().orElseThrow());

        assertEquals(LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer.ContrastState.AVAILABLE,
            report.contrastState());
        assertEquals(new BigDecimal("0.166666"), report.lineupCaptureRateContrast().orElseThrow());

        var fullA = new LeagueTeamSeasonLineupCaptureEvidenceAnalyzer(fixture.database()).analyze("l1", "ta", 2026);
        var fullB = new LeagueTeamSeasonLineupCaptureEvidenceAnalyzer(fixture.database()).analyze("l1", "tb", 2026);
        assertTrue(fullA.lineupCaptureRate().isPresent());
        assertTrue(fullB.lineupCaptureRate().isPresent());
        assertTrue(fullA.lineupCaptureRate().orElseThrow().subtract(fullB.lineupCaptureRate().orElseThrow())
            .compareTo(report.lineupCaptureRateContrast().orElseThrow()) != 0,
            "pairwise contrast must not subtract independently scoped full-season rates");
    }

    @Test
    void withholdsContrastWhenTeamsHaveNoSharedComparableCompleteWeek() throws Exception {
        Fixture fixture = initializedFixture("pair-none.db");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();
        fixture.saveWeek(1,
            List.of("a1", "a2", "a3"), List.of("a1", "a2"),
            List.of("b1", "b2", "b3"), List.of("b1", "0"));
        fixture.saveCoverage(1);
        fixture.saveProduction(1,
            new Production("pa1", 1, 0), new Production("pa2", 0, 1), new Production("pa3", 0, 2),
            new Production("pb1", 1, 0), new Production("pb2", 0, 1), new Production("pb3", 0, 2));

        var report = fixture.analyzer().analyze("l1", "ta", "tb", 2026);

        assertTrue(report.sharedComparableWeeks().isEmpty());
        assertEquals(List.of(1), report.teamAOnlyComparableWeeks());
        assertTrue(report.teamBOnlyComparableWeeks().isEmpty());
        assertEquals(LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer.SharedRateState.UNAVAILABLE_NO_SHARED_COMPARABLE_WEEKS,
            report.teamA().rateState());
        assertTrue(report.teamA().sharedTotalStartedPoints().isEmpty());
        assertEquals(LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer.ContrastState.UNAVAILABLE_NO_SHARED_COMPARABLE_WEEKS,
            report.contrastState());
        assertTrue(report.lineupCaptureRateContrast().isEmpty());
    }

    @Test
    void withholdsSharedRatesAndContrastForNegativeComparablePoints() throws Exception {
        Fixture fixture = initializedFixture("pair-negative.db");
        fixture.saveConfiguration(Map.of("pass_int", -2.0));
        fixture.saveEligibility();
        fixture.saveWeek(1,
            List.of("a1", "a2", "a3"), List.of("a1", "a2"),
            List.of("b1", "b2", "b3"), List.of("b1", "b2"));
        fixture.saveCoverage(1);
        fixture.saveInterceptionProduction(1, 2, 1, 3, 2, 1, 3);

        var report = fixture.analyzer().analyze("l1", "ta", "tb", 2026);

        assertEquals(List.of(1), report.sharedComparableWeeks());
        assertEquals(LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer.SharedRateState.UNAVAILABLE_NEGATIVE_COMPARABLE_POINTS,
            report.teamA().rateState());
        assertEquals(LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer.SharedRateState.UNAVAILABLE_NEGATIVE_COMPARABLE_POINTS,
            report.teamB().rateState());
        assertEquals(LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer.ContrastState.UNAVAILABLE_TEAM_SHARED_RATE,
            report.contrastState());
        assertTrue(report.lineupCaptureRateContrast().isEmpty());
    }

    @Test
    void rejectsSameTeamPairBeforeReadingEvidence() throws Exception {
        Fixture fixture = initializedFixture("pair-same.db");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> fixture.analyzer().analyze("l1", "ta", "ta", 2026));

        assertEquals("teamAId and teamBId must identify distinct teams", error.getMessage());
    }

    @Test
    void reportRejectsFabricatedContrast() throws Exception {
        Fixture fixture = initializedFixture("pair-invariant.db");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();
        fixture.saveWeek(1,
            List.of("a1", "a2", "a3"), List.of("a1", "a2"),
            List.of("b1", "b2", "b3"), List.of("b1", "b2"));
        fixture.saveCoverage(1);
        fixture.saveProduction(1,
            new Production("pa1", 1, 0), new Production("pa2", 0, 1), new Production("pa3", 0, 2),
            new Production("pb1", 2, 0), new Production("pb2", 0, 1), new Production("pb3", 0, 2));
        var report = fixture.analyzer().analyze("l1", "ta", "tb", 2026);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer.PairwiseContrastReport(
                report.policyId(), report.metricScope(), report.weekUniverse(),
                report.teamASourceSeason(), report.teamBSourceSeason(),
                report.sharedComparableWeeks(), report.teamAOnlyComparableWeeks(), report.teamBOnlyComparableWeeks(),
                report.teamA(), report.teamB(), report.contrastState(), Optional.of(new BigDecimal("0.999999"))));

        assertEquals("pairwise contrast fields must match governed shared-week source evidence", error.getMessage());
    }

    private Fixture initializedFixture(String fileName) throws Exception {
        Database database = new Database(tempDir.resolve(fileName));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("ta", "1", "l1", "Alpha Team"));
        teams.save(new Team("tb", "2", "l1", "Beta Team"));

        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("pa1", "a1", "A Quarterback", "QB", "CHI"));
        players.save(new Player("pa2", "a2", "A Receiver Two", "WR", "DET"));
        players.save(new Player("pa3", "a3", "A Receiver Three", "WR", "MIN"));
        players.save(new Player("pb1", "b1", "B Quarterback", "QB", "GB"));
        players.save(new Player("pb2", "b2", "B Receiver Two", "WR", "SEA"));
        players.save(new Player("pb3", "b3", "B Receiver Three", "WR", "LAR"));
        return new Fixture(database);
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private static final List<String> COVERED_PLAYERS = List.of("pa1", "pa2", "pa3", "pb1", "pb2", "pb3");

    private record Production(String playerId, int passingTouchdowns, int receivingTouchdowns) {}

    private record Fixture(Database database) {
        LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer analyzer() {
            return new LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer(database);
        }

        void saveConfiguration(Map<String, Double> scoring) throws Exception {
            new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
                "l1", "sleeper", AS_OF, 2026, List.of("QB", "WR", "BN"), scoring));
        }

        void saveEligibility() throws Exception {
            PlayerFantasyPositionObservationRepository repository = new PlayerFantasyPositionObservationRepository(database);
            repository.replace(new PlayerFantasyPositionObservation("pa1", "sleeper", AS_OF, List.of("QB")));
            repository.replace(new PlayerFantasyPositionObservation("pa2", "sleeper", AS_OF, List.of("WR")));
            repository.replace(new PlayerFantasyPositionObservation("pa3", "sleeper", AS_OF, List.of("WR")));
            repository.replace(new PlayerFantasyPositionObservation("pb1", "sleeper", AS_OF, List.of("QB")));
            repository.replace(new PlayerFantasyPositionObservation("pb2", "sleeper", AS_OF, List.of("WR")));
            repository.replace(new PlayerFantasyPositionObservation("pb3", "sleeper", AS_OF, List.of("WR")));
        }

        void saveWeek(
            int week,
            List<String> aPlayers,
            List<String> aStarters,
            List<String> bPlayers,
            List<String> bStarters) throws Exception {
            TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
            rosters.save(TeamWeekRosterEvidence.create("l1", "ta", 2026, week, aPlayers, aStarters, "sleeper", AS_OF));
            rosters.save(TeamWeekRosterEvidence.create("l1", "tb", 2026, week, bPlayers, bStarters, "sleeper", AS_OF));
        }

        void saveCoverage(int week) throws Exception {
            new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
                2026, week, "nflverse", URI.create("https://example.test/stats_player_week_2026.csv"),
                AS_OF, 100, COVERED_PLAYERS.size(), 0, COVERED_PLAYERS));
        }

        void saveProduction(int week, Production... productions) throws Exception {
            PlayerWeekProductionRepository repository = new PlayerWeekProductionRepository(database);
            for (Production production : productions) {
                repository.save(PlayerWeekProduction.create(
                    production.playerId(), 2026, week,
                    0, production.passingTouchdowns(), 0,
                    0, 0,
                    0, 0, production.receivingTouchdowns(),
                    0, "nflverse", AS_OF));
            }
        }

        void saveInterceptionProduction(int week, int... interceptions) throws Exception {
            PlayerWeekProductionRepository repository = new PlayerWeekProductionRepository(database);
            for (int i = 0; i < COVERED_PLAYERS.size(); i++) {
                repository.save(PlayerWeekProduction.create(
                    COVERED_PLAYERS.get(i), 2026, week,
                    0, 0, interceptions[i],
                    0, 0,
                    0, 0, 0,
                    0, "nflverse", AS_OF));
            }
        }
    }
}
