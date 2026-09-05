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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void recalculatesEveryTeamOverExactAllTeamCommonComparableWeekIntersectionInNameOrder() throws Exception {
        Fixture fixture = initializedFixture("league-common.db");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();

        fixture.saveWeek(1, List.of("a1", "a2"), List.of("b1", "b2"), List.of("g1", "g3"));
        fixture.saveCoverage(1);
        fixture.saveProduction(1,
            new Production("pa1", 1, 0), new Production("pa2", 0, 1), new Production("pa3", 0, 2),
            new Production("pb1", 2, 0), new Production("pb2", 0, 1), new Production("pb3", 0, 2),
            new Production("pg1", 1, 0), new Production("pg2", 0, 1), new Production("pg3", 0, 2));

        fixture.saveWeek(2, List.of("a1", "a2"), List.of("b1", "b2"), List.of("g1", "0"));
        fixture.saveCoverage(2);
        fixture.saveProduction(2,
            new Production("pa1", 1, 0), new Production("pa2", 0, 1), new Production("pa3", 0, 2),
            new Production("pb1", 2, 0), new Production("pb2", 0, 1), new Production("pb3", 0, 2),
            new Production("pg1", 1, 0), new Production("pg2", 0, 1), new Production("pg3", 0, 2));

        fixture.saveWeek(3, List.of("a1", "a2"), List.of("b1", "0"), List.of("g1", "g3"));
        fixture.saveCoverage(3);
        fixture.saveProduction(3,
            new Production("pa1", 1, 0), new Production("pa2", 0, 1), new Production("pa3", 0, 2),
            new Production("pb1", 2, 0), new Production("pb2", 0, 1), new Production("pb3", 0, 2),
            new Production("pg1", 1, 0), new Production("pg2", 0, 1), new Production("pg3", 0, 2));

        var report = fixture.analyzer().analyze("l1", 2026);

        assertEquals(LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.POLICY_ID, report.policyId());
        assertEquals(LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState.AVAILABLE,
            report.commonUniverseState());
        assertEquals(List.of(1), report.commonComparableWeeks());
        assertEquals(List.of("Alpha Team", "Beta Team", "Gamma Team"), report.teams().stream()
            .map(LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.TeamCommonEvidence::teamName).toList());

        var alpha = report.teams().get(0);
        assertEquals(3, alpha.observedWeeks());
        assertEquals(3, alpha.individuallyComparableWeeks());
        assertEquals(List.of(2, 3), alpha.excludedComparableWeeks());
        assertEquals(1, alpha.commonComparableWeeks());
        assertEquals(new BigDecimal("10.0"), alpha.commonTotalStartedPoints().orElseThrow());
        assertEquals(new BigDecimal("16.0"), alpha.commonTotalPotentialPoints().orElseThrow());
        assertEquals(new BigDecimal("6.0"), alpha.commonTotalPointsGap().orElseThrow());
        assertEquals(new BigDecimal("0.625000"), alpha.lineupCaptureRate().orElseThrow());

        var beta = report.teams().get(1);
        assertEquals(3, beta.observedWeeks());
        assertEquals(2, beta.individuallyComparableWeeks());
        assertEquals(List.of(2), beta.excludedComparableWeeks());
        assertEquals(new BigDecimal("14.0"), beta.commonTotalStartedPoints().orElseThrow());
        assertEquals(new BigDecimal("20.0"), beta.commonTotalPotentialPoints().orElseThrow());
        assertEquals(new BigDecimal("6.0"), beta.commonTotalPointsGap().orElseThrow());
        assertEquals(new BigDecimal("0.700000"), beta.lineupCaptureRate().orElseThrow());

        var gamma = report.teams().get(2);
        assertEquals(3, gamma.observedWeeks());
        assertEquals(2, gamma.individuallyComparableWeeks());
        assertEquals(List.of(3), gamma.excludedComparableWeeks());
        assertEquals(new BigDecimal("16.0"), gamma.commonTotalStartedPoints().orElseThrow());
        assertEquals(new BigDecimal("16.0"), gamma.commonTotalPotentialPoints().orElseThrow());
        assertEquals(new BigDecimal("0.0"), gamma.commonTotalPointsGap().orElseThrow());
        assertEquals(new BigDecimal("1.000000"), gamma.lineupCaptureRate().orElseThrow());

        assertEquals(
            List.of("policyId", "metricScope", "weekUniverse", "presentationScope",
                "teamSeasonPointsGapPolicyId", "leagueId", "leagueName", "season",
                "commonUniverseState", "commonComparableWeeks", "teams"),
            Arrays.stream(LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.LeagueCommonUniverseReport.class
                .getRecordComponents()).map(component -> component.getName()).toList());
        assertEquals(
            List.of("teamId", "teamName", "sourceSeasonPointsGap", "observedWeeks",
                "individuallyComparableWeeks", "excludedComparableWeeks", "commonComparableWeeks",
                "commonTotalStartedPoints", "commonTotalPotentialPoints", "commonTotalPointsGap",
                "rateState", "lineupCaptureRate"),
            Arrays.stream(LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.TeamCommonEvidence.class
                .getRecordComponents()).map(component -> component.getName()).toList());
    }

    @Test
    void withholdsNormalizedTableWhenAnyRepositoryTeamEliminatesTheOnlyCommonWeek() throws Exception {
        Fixture fixture = initializedFixture("league-none.db");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();
        fixture.saveWeek(1, List.of("a1", "a2"), List.of("b1", "b2"), List.of("g1", "0"));
        fixture.saveCoverage(1);
        fixture.saveProduction(1,
            new Production("pa1", 1, 0), new Production("pa2", 0, 1), new Production("pa3", 0, 2),
            new Production("pb1", 2, 0), new Production("pb2", 0, 1), new Production("pb3", 0, 2),
            new Production("pg1", 1, 0), new Production("pg2", 0, 1), new Production("pg3", 0, 2));

        var report = fixture.analyzer().analyze("l1", 2026);

        assertEquals(
            LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState.UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS,
            report.commonUniverseState());
        assertTrue(report.commonComparableWeeks().isEmpty());
        assertEquals(List.of(1), report.teams().get(0).excludedComparableWeeks());
        assertEquals(List.of(1), report.teams().get(1).excludedComparableWeeks());
        assertTrue(report.teams().get(2).excludedComparableWeeks().isEmpty());
        for (var team : report.teams()) {
            assertEquals(
                LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonRateState.UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS,
                team.rateState());
            assertTrue(team.commonTotalStartedPoints().isEmpty());
            assertTrue(team.lineupCaptureRate().isEmpty());
        }
    }

    @Test
    void reportsInsufficientLeagueTeamUniverseWithoutFabricatingComparison() throws Exception {
        Database database = new Database(tempDir.resolve("league-single.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        new TeamRepository(database).save(new Team("ta", "1", "l1", "Alpha Team"));

        var report = new LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer(database).analyze("l1", 2026);

        assertEquals(
            LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState.UNAVAILABLE_INSUFFICIENT_TEAMS,
            report.commonUniverseState());
        assertEquals(1, report.teams().size());
        assertTrue(report.commonComparableWeeks().isEmpty());
        assertTrue(report.teams().get(0).lineupCaptureRate().isEmpty());
    }

    @Test
    void retainsCommonRawEvidenceButWithholdsRateForNegativeCommonPoints() throws Exception {
        Fixture fixture = initializedFixture("league-negative.db");
        fixture.saveConfiguration(Map.of("pass_int", -2.0));
        fixture.saveEligibility();
        fixture.saveWeek(1, List.of("a1", "a2"), List.of("b1", "b2"), List.of("g1", "g2"));
        fixture.saveCoverage(1);
        fixture.saveInterceptionProduction(1);

        var report = fixture.analyzer().analyze("l1", 2026);

        assertEquals(LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState.AVAILABLE,
            report.commonUniverseState());
        assertEquals(List.of(1), report.commonComparableWeeks());
        for (var team : report.teams()) {
            assertEquals(
                LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonRateState.UNAVAILABLE_NEGATIVE_COMMON_POINTS,
                team.rateState());
            assertTrue(team.commonTotalPotentialPoints().isPresent());
            assertTrue(team.lineupCaptureRate().isEmpty());
        }
    }

    @Test
    void failsClosedWhenAllTeamsAreComparableButCommonWeekRosterEvidenceDatesDiffer() throws Exception {
        Fixture fixture = initializedFixture("league-provenance.db");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();
        fixture.saveWeekWithRosterDates(
            1,
            List.of("a1", "a2"), AS_OF,
            List.of("b1", "b2"), AS_OF.plusDays(1),
            List.of("g1", "g3"), AS_OF);
        fixture.saveCoverage(1);
        fixture.saveProduction(1,
            new Production("pa1", 1, 0), new Production("pa2", 0, 1), new Production("pa3", 0, 2),
            new Production("pb1", 2, 0), new Production("pb2", 0, 1), new Production("pb3", 0, 2),
            new Production("pg1", 1, 0), new Production("pg2", 0, 1), new Production("pg3", 0, 2));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> fixture.analyzer().analyze("l1", 2026));

        assertEquals(
            "League common-universe lineup capture unavailable: common week governed evidence boundary differs",
            error.getMessage());
    }

    @Test
    void reportRejectsFabricatedCommonUniverseWeekList() throws Exception {
        Fixture fixture = initializedFixture("league-invariant.db");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();
        fixture.saveWeek(1, List.of("a1", "a2"), List.of("b1", "b2"), List.of("g1", "g3"));
        fixture.saveCoverage(1);
        fixture.saveProduction(1,
            new Production("pa1", 1, 0), new Production("pa2", 0, 1), new Production("pa3", 0, 2),
            new Production("pb1", 2, 0), new Production("pb2", 0, 1), new Production("pb3", 0, 2),
            new Production("pg1", 1, 0), new Production("pg2", 0, 1), new Production("pg3", 0, 2));
        var report = fixture.analyzer().analyze("l1", 2026);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.LeagueCommonUniverseReport(
                report.policyId(), report.metricScope(), report.weekUniverse(), report.presentationScope(),
                report.teamSeasonPointsGapPolicyId(), report.leagueId(), report.leagueName(), report.season(),
                report.commonUniverseState(), List.of(1, 2), report.teams()));

        assertEquals("league common-universe fields must match governed all-team source evidence", error.getMessage());
    }

    @Test
    void reportRejectsTeamRowsOutsideRepositoryNameOrder() throws Exception {
        Fixture fixture = initializedFixture("league-order.db");
        var empty = fixture.analyzer().analyze("l1", 2026);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.LeagueCommonUniverseReport(
                empty.policyId(), empty.metricScope(), empty.weekUniverse(), empty.presentationScope(),
                empty.teamSeasonPointsGapPolicyId(), empty.leagueId(), empty.leagueName(), empty.season(),
                empty.commonUniverseState(), empty.commonComparableWeeks(),
                List.of(empty.teams().get(1), empty.teams().get(0), empty.teams().get(2))));

        assertEquals("teams must preserve repository team-name order", error.getMessage());
    }

    private Fixture initializedFixture(String fileName) throws Exception {
        Database database = new Database(tempDir.resolve(fileName));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("tg", "3", "l1", "Gamma Team"));
        teams.save(new Team("ta", "1", "l1", "Alpha Team"));
        teams.save(new Team("tb", "2", "l1", "Beta Team"));

        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("pa1", "a1", "A Quarterback", "QB", "CHI"));
        players.save(new Player("pa2", "a2", "A Receiver Two", "WR", "DET"));
        players.save(new Player("pa3", "a3", "A Receiver Three", "WR", "MIN"));
        players.save(new Player("pb1", "b1", "B Quarterback", "QB", "GB"));
        players.save(new Player("pb2", "b2", "B Receiver Two", "WR", "SEA"));
        players.save(new Player("pb3", "b3", "B Receiver Three", "WR", "LAR"));
        players.save(new Player("pg1", "g1", "G Quarterback", "QB", "BUF"));
        players.save(new Player("pg2", "g2", "G Receiver Two", "WR", "MIA"));
        players.save(new Player("pg3", "g3", "G Receiver Three", "WR", "NYJ"));
        return new Fixture(database);
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private static final List<String> COVERED_PLAYERS = List.of(
        "pa1", "pa2", "pa3", "pb1", "pb2", "pb3", "pg1", "pg2", "pg3");

    private record Production(String playerId, int passingTouchdowns, int receivingTouchdowns) {}

    private record Fixture(Database database) {
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer analyzer() {
            return new LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer(database);
        }

        void saveConfiguration(Map<String, Double> scoring) throws Exception {
            new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
                "l1", "sleeper", AS_OF, 2026, List.of("QB", "WR", "BN"), scoring));
        }

        void saveEligibility() throws Exception {
            PlayerFantasyPositionObservationRepository repository =
                new PlayerFantasyPositionObservationRepository(database);
            repository.replace(new PlayerFantasyPositionObservation("pa1", "sleeper", AS_OF, List.of("QB")));
            repository.replace(new PlayerFantasyPositionObservation("pa2", "sleeper", AS_OF, List.of("WR")));
            repository.replace(new PlayerFantasyPositionObservation("pa3", "sleeper", AS_OF, List.of("WR")));
            repository.replace(new PlayerFantasyPositionObservation("pb1", "sleeper", AS_OF, List.of("QB")));
            repository.replace(new PlayerFantasyPositionObservation("pb2", "sleeper", AS_OF, List.of("WR")));
            repository.replace(new PlayerFantasyPositionObservation("pb3", "sleeper", AS_OF, List.of("WR")));
            repository.replace(new PlayerFantasyPositionObservation("pg1", "sleeper", AS_OF, List.of("QB")));
            repository.replace(new PlayerFantasyPositionObservation("pg2", "sleeper", AS_OF, List.of("WR")));
            repository.replace(new PlayerFantasyPositionObservation("pg3", "sleeper", AS_OF, List.of("WR")));
        }

        void saveWeek(int week, List<String> aStarters, List<String> bStarters, List<String> gStarters)
            throws Exception {
            saveWeekWithRosterDates(week, aStarters, AS_OF, bStarters, AS_OF, gStarters, AS_OF);
        }

        void saveWeekWithRosterDates(
            int week,
            List<String> aStarters,
            LocalDate aAsOf,
            List<String> bStarters,
            LocalDate bAsOf,
            List<String> gStarters,
            LocalDate gAsOf) throws Exception {
            TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
            rosters.save(TeamWeekRosterEvidence.create(
                "l1", "ta", 2026, week, List.of("a1", "a2", "a3"), aStarters, "sleeper", aAsOf));
            rosters.save(TeamWeekRosterEvidence.create(
                "l1", "tb", 2026, week, List.of("b1", "b2", "b3"), bStarters, "sleeper", bAsOf));
            rosters.save(TeamWeekRosterEvidence.create(
                "l1", "tg", 2026, week, List.of("g1", "g2", "g3"), gStarters, "sleeper", gAsOf));
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

        void saveInterceptionProduction(int week) throws Exception {
            PlayerWeekProductionRepository repository = new PlayerWeekProductionRepository(database);
            repository.save(interception("pa1", week, 1));
            repository.save(interception("pa2", week, 0));
            repository.save(interception("pa3", week, 0));
            repository.save(interception("pb1", week, 2));
            repository.save(interception("pb2", week, 0));
            repository.save(interception("pb3", week, 0));
            repository.save(interception("pg1", week, 3));
            repository.save(interception("pg2", week, 0));
            repository.save(interception("pg3", week, 0));
        }

        private static PlayerWeekProduction interception(String playerId, int week, int interceptions) {
            return PlayerWeekProduction.create(
                playerId, 2026, week,
                0, 0, interceptions,
                0, 0,
                0, 0, 0,
                0, "nflverse", AS_OF);
        }
    }
}
