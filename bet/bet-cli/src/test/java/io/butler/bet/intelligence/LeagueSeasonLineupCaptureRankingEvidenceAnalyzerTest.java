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

class LeagueSeasonLineupCaptureRankingEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void ranksGovernedCommonUniverseRatesWithCompetitionTiesAndNoSecondaryTiebreaker() throws Exception {
        Fixture fixture = initializedFixture("ranking.db");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();
        for (int week = 1; week <= 4; week++) {
            fixture.saveCompleteWeek(week);
            fixture.saveCoverage(week);
            fixture.saveStandardProduction(week);
        }

        var report = fixture.analyzer().analyze("l1", 2026);

        assertEquals(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.POLICY_ID, report.policyId());
        assertEquals(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.METRIC_SCOPE, report.metricScope());
        assertEquals(4, report.minimumCommonWeeks());
        assertEquals(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RANKING_POLICY, report.rankingPolicy());
        assertEquals(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankingState.AVAILABLE, report.rankingState());
        assertEquals(List.of(1, 2, 3, 4), report.sourceCommonUniverse().commonComparableWeeks());
        assertEquals(List.of("Alpha Team", "Beta Team", "Gamma Team"), report.sourceCommonUniverse().teams().stream()
            .map(LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.TeamCommonEvidence::teamName).toList());

        assertEquals(List.of("Beta Team", "Gamma Team", "Alpha Team"), report.rankedTeams().stream()
            .map(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankedTeamEvidence::teamName).toList());
        assertEquals(List.of(1, 1, 3), report.rankedTeams().stream()
            .map(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankedTeamEvidence::rank).toList());
        assertEquals(new BigDecimal("1.000000"), report.rankedTeams().get(0).lineupCaptureRate());
        assertEquals(new BigDecimal("1.000000"), report.rankedTeams().get(1).lineupCaptureRate());
        assertEquals(new BigDecimal("0.625000"), report.rankedTeams().get(2).lineupCaptureRate());
        assertEquals(new BigDecimal("40.0"), report.rankedTeams().get(2).commonTotalStartedPoints());
        assertEquals(new BigDecimal("64.0"), report.rankedTeams().get(2).commonTotalPotentialPoints());
        assertEquals(new BigDecimal("24.0"), report.rankedTeams().get(2).commonTotalPointsGap());

        assertEquals(
            List.of("policyId", "metricScope", "minimumCommonWeeks", "rankingPolicy", "sourceCommonUniverse",
                "rankingState", "rankedTeams"),
            Arrays.stream(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.LeagueRankingReport.class.getRecordComponents())
                .map(component -> component.getName()).toList());
    }

    @Test
    void withholdsEntireRankingBelowFourCommonWeekGovernanceFloor() throws Exception {
        Fixture fixture = initializedFixture("ranking-three-weeks.db");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();
        for (int week = 1; week <= 3; week++) {
            fixture.saveCompleteWeek(week);
            fixture.saveCoverage(week);
            fixture.saveStandardProduction(week);
        }

        var report = fixture.analyzer().analyze("l1", 2026);

        assertEquals(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankingState.UNAVAILABLE_BELOW_MINIMUM_COMMON_WEEKS,
            report.rankingState());
        assertEquals(List.of(1, 2, 3), report.sourceCommonUniverse().commonComparableWeeks());
        assertTrue(report.rankedTeams().isEmpty());
    }

    @Test
    void withholdsEntireRankingWhenOneTeamCommonRateIsUnavailable() throws Exception {
        Fixture fixture = initializedFixture("ranking-negative.db");
        fixture.saveConfiguration(Map.of("pass_int", -2.0, "rec_td", 6.0));
        fixture.saveEligibility();
        for (int week = 1; week <= 4; week++) {
            fixture.saveCompleteWeek(week);
            fixture.saveCoverage(week);
            fixture.saveNegativeAlphaProduction(week);
        }

        var report = fixture.analyzer().analyze("l1", 2026);

        assertEquals(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankingState.UNAVAILABLE_TEAM_COMMON_RATE,
            report.rankingState());
        assertEquals(LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonRateState.UNAVAILABLE_NEGATIVE_COMMON_POINTS,
            report.sourceCommonUniverse().teams().get(0).rateState());
        assertTrue(report.rankedTeams().isEmpty());
    }

    @Test
    void mapsOneTeamLeagueToUnavailableAndNeverPublishesAOneTeamRank() throws Exception {
        Database database = new Database(tempDir.resolve("ranking-one-team.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        new TeamRepository(database).save(new Team("ta", "1", "l1", "Alpha Team"));

        var report = new LeagueSeasonLineupCaptureRankingEvidenceAnalyzer(database).analyze("l1", 2026);

        assertEquals(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankingState.UNAVAILABLE_INSUFFICIENT_TEAMS,
            report.rankingState());
        assertTrue(report.rankedTeams().isEmpty());
    }

    @Test
    void reportRejectsFabricatedRankOrder() throws Exception {
        Fixture fixture = initializedFixture("ranking-invariant.db");
        fixture.saveConfiguration(Map.of("pass_td", 4.0, "rec_td", 6.0));
        fixture.saveEligibility();
        for (int week = 1; week <= 4; week++) {
            fixture.saveCompleteWeek(week);
            fixture.saveCoverage(week);
            fixture.saveStandardProduction(week);
        }
        var report = fixture.analyzer().analyze("l1", 2026);
        var fabricated = List.of(report.rankedTeams().get(2), report.rankedTeams().get(0), report.rankedTeams().get(1));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.LeagueRankingReport(
                report.policyId(), report.metricScope(), report.minimumCommonWeeks(), report.rankingPolicy(),
                report.sourceCommonUniverse(), report.rankingState(), fabricated));

        assertEquals("ranking fields must match governed common-universe source evidence", error.getMessage());
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
        for (String prefix : List.of("a", "b", "g")) {
            players.save(new Player("p" + prefix + "1", prefix + "1", prefix + " QB", "QB", "CHI"));
            players.save(new Player("p" + prefix + "2", prefix + "2", prefix + " WR2", "WR", "DET"));
            players.save(new Player("p" + prefix + "3", prefix + "3", prefix + " WR3", "WR", "MIN"));
        }
        return new Fixture(database);
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private static final List<String> COVERED_PLAYERS = List.of(
        "pa1", "pa2", "pa3", "pb1", "pb2", "pb3", "pg1", "pg2", "pg3");

    private record Fixture(Database database) {
        LeagueSeasonLineupCaptureRankingEvidenceAnalyzer analyzer() {
            return new LeagueSeasonLineupCaptureRankingEvidenceAnalyzer(database);
        }

        void saveConfiguration(Map<String, Double> scoring) throws Exception {
            new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
                "l1", "sleeper", AS_OF, 2026, List.of("QB", "WR", "BN"), scoring));
        }

        void saveEligibility() throws Exception {
            PlayerFantasyPositionObservationRepository repository =
                new PlayerFantasyPositionObservationRepository(database);
            for (String prefix : List.of("a", "b", "g")) {
                repository.replace(new PlayerFantasyPositionObservation(
                    "p" + prefix + "1", "sleeper", AS_OF, List.of("QB")));
                repository.replace(new PlayerFantasyPositionObservation(
                    "p" + prefix + "2", "sleeper", AS_OF, List.of("WR")));
                repository.replace(new PlayerFantasyPositionObservation(
                    "p" + prefix + "3", "sleeper", AS_OF, List.of("WR")));
            }
        }

        void saveCompleteWeek(int week) throws Exception {
            TeamWeekRosterEvidenceRepository rosters = new TeamWeekRosterEvidenceRepository(database);
            rosters.save(TeamWeekRosterEvidence.create(
                "l1", "ta", 2026, week, List.of("a1", "a2", "a3"), List.of("a1", "a2"),
                "sleeper", AS_OF));
            rosters.save(TeamWeekRosterEvidence.create(
                "l1", "tb", 2026, week, List.of("b1", "b2", "b3"), List.of("b1", "b2"),
                "sleeper", AS_OF));
            rosters.save(TeamWeekRosterEvidence.create(
                "l1", "tg", 2026, week, List.of("g1", "g2", "g3"), List.of("g1", "g2"),
                "sleeper", AS_OF));
        }

        void saveCoverage(int week) throws Exception {
            new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
                2026, week, "nflverse", URI.create("https://example.test/stats_player_week_2026.csv"),
                AS_OF, 100, COVERED_PLAYERS.size(), 0, COVERED_PLAYERS));
        }

        void saveStandardProduction(int week) throws Exception {
            PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
            save(production, week, "pa1", 1, 0, 0);
            save(production, week, "pa2", 0, 0, 1);
            save(production, week, "pa3", 0, 0, 2);
            for (String prefix : List.of("b", "g")) {
                save(production, week, "p" + prefix + "1", 1, 0, 0);
                save(production, week, "p" + prefix + "2", 0, 0, 2);
                save(production, week, "p" + prefix + "3", 0, 0, 1);
            }
        }

        void saveNegativeAlphaProduction(int week) throws Exception {
            PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
            save(production, week, "pa1", 0, 2, 0);
            save(production, week, "pa2", 0, 0, 0);
            save(production, week, "pa3", 0, 0, 1);
            for (String prefix : List.of("b", "g")) {
                save(production, week, "p" + prefix + "1", 0, 0, 0);
                save(production, week, "p" + prefix + "2", 0, 0, 1);
                save(production, week, "p" + prefix + "3", 0, 0, 0);
            }
        }

        private static void save(
            PlayerWeekProductionRepository repository,
            int week,
            String playerId,
            int passingTouchdowns,
            int interceptions,
            int receivingTouchdowns) throws Exception {
            repository.save(PlayerWeekProduction.create(
                playerId, 2026, week,
                0, passingTouchdowns, interceptions,
                0, 0,
                0, 0, receivingTouchdowns,
                0, "nflverse", AS_OF));
        }
    }
}
