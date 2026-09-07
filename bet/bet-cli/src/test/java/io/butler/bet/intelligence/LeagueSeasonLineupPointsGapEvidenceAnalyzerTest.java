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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueSeasonLineupPointsGapEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void exposesTeamsInRepositoryNameOrderWithSeparateNestedDenominatorsAndNoLeagueAggregate() throws Exception {
        Database database = new Database(tempDir.resolve("league-season-lineup-gap.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("t-beta", "2", "l1", "Beta Team"));
        teams.save(new Team("t-alpha", "1", "l1", "Alpha Team"));

        var report = new LeagueSeasonLineupPointsGapEvidenceAnalyzer(database).analyze("l1", 2026);

        assertEquals(LeagueSeasonLineupPointsGapEvidenceAnalyzer.POLICY_ID, report.policyId());
        assertEquals(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.METRIC_SCOPE, report.metricScope());
        assertEquals(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WEEK_UNIVERSE, report.weekUniverse());
        assertEquals(LeagueSeasonLineupPointsGapEvidenceAnalyzer.PRESENTATION_SCOPE, report.presentationScope());
        assertEquals(HistoricalScoringLaneSelector.POLICY_ID, report.scoringLaneSelectionPolicyId());
        assertEquals(HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT, report.scoringLane());
        assertEquals(CoveredProductionScoringPolicy.POLICY_ID, report.scoringPolicyId());
        assertEquals(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.POLICY_ID, report.teamSeasonPolicyId());
        assertEquals(List.of("Alpha Team", "Beta Team"), report.teams().stream()
            .map(LeagueSeasonLineupPointsGapEvidenceAnalyzer.TeamEvidence::teamName).toList());
        assertTrue(report.teams().stream().allMatch(team -> team.seasonEvidence().aggregate().observedWeeks() == 0));
        assertTrue(report.teams().stream().allMatch(
            team -> team.seasonEvidence().aggregate().comparableTotalPointsGap().isEmpty()));
        assertTrue(report.teams().stream().allMatch(
            team -> team.seasonEvidence().scoringLane() == HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT));

        assertEquals(
            List.of("policyId", "metricScope", "weekUniverse", "presentationScope",
                "scoringLaneSelectionPolicyId", "scoringLane", "scoringPolicyId", "teamSeasonPolicyId",
                "leagueId", "leagueName", "season", "teams"),
            Arrays.stream(LeagueSeasonLineupPointsGapEvidenceAnalyzer.LeagueEvidenceReport.class.getRecordComponents())
                .map(component -> component.getName()).toList());
    }

    @Test
    void readyProviderNativeEvidenceFlowsThroughExactNestedTeamSeasonPoints() throws Exception {
        Fixture fixture = providerFixture("provider-ready.db");
        fixture.saveProviderPoints(List.of(
            fixture.providerPoints("s1", new BigDecimal("4.0")),
            fixture.providerPoints("s2", new BigDecimal("6.0")),
            fixture.providerPoints("s3", new BigDecimal("12.0"))));

        var report = fixture.analyzer().analyze("l1", 2026);

        assertEquals(HistoricalScoringLaneSelector.POLICY_ID, report.scoringLaneSelectionPolicyId());
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, report.scoringLane());
        assertEquals(HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID, report.scoringPolicyId());
        assertEquals(1, report.teams().size());
        var season = report.teams().get(0).seasonEvidence();
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, season.scoringLane());
        assertEquals(1, season.aggregate().comparableCompleteWeeks());
        assertEquals(new BigDecimal("10.0"), season.aggregate().comparableTotalStartedPoints().orElseThrow());
        assertEquals(new BigDecimal("16.0"), season.aggregate().comparableTotalPotentialPoints().orElseThrow());
        assertEquals(new BigDecimal("6.0"), season.aggregate().comparableTotalPointsGap().orElseThrow());
        var weekGap = season.weeks().get(0).pointsGap();
        assertEquals(PROVIDER_AS_OF, weekGap.providerPointsAsOf());
        assertEquals(SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE,
            weekGap.providerPointsSourceSurface());
        assertEquals("provider-l1", weekGap.providerLeagueId());
    }

    @Test
    void blockedProviderNativeEvidenceRemainsProviderLaneAndNeverFallsBackToReadyNflverse() throws Exception {
        Fixture fixture = providerFixture("provider-blocked.db");
        fixture.saveProviderPoints(List.of(
            fixture.providerPoints("s1", new BigDecimal("4.0")),
            fixture.providerPoints("s2", new BigDecimal("6.0"))));
        fixture.saveReadyNflverse();

        var report = fixture.analyzer().analyze("l1", 2026);

        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, report.scoringLane());
        assertEquals(HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID, report.scoringPolicyId());
        var season = report.teams().get(0).seasonEvidence();
        assertEquals(HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE, season.scoringLane());
        assertEquals(1, season.aggregate().blockedWeeks());
        assertEquals(0, season.aggregate().comparableCompleteWeeks());
        assertTrue(season.aggregate().comparableTotalPointsGap().isEmpty());
        assertTrue(season.weeks().get(0).blockers().stream().anyMatch(
            blocker -> blocker.contains("Missing provider-points identities") && blocker.contains("s3")));
    }

    @Test
    void rejectsTeamListThatDoesNotPreserveRepositoryNameOrder() throws Exception {
        Database database = new Database(tempDir.resolve("league-season-lineup-gap-order.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("t-beta", "2", "l1", "Beta Team"));
        teams.save(new Team("t-alpha", "1", "l1", "Alpha Team"));
        var report = new LeagueSeasonLineupPointsGapEvidenceAnalyzer(database).analyze("l1", 2026);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new LeagueSeasonLineupPointsGapEvidenceAnalyzer.LeagueEvidenceReport(
                report.policyId(),
                report.metricScope(),
                report.weekUniverse(),
                report.presentationScope(),
                report.scoringLaneSelectionPolicyId(),
                report.scoringLane(),
                report.scoringPolicyId(),
                report.teamSeasonPolicyId(),
                report.leagueId(),
                report.leagueName(),
                report.season(),
                List.of(report.teams().get(1), report.teams().get(0))));

        assertEquals("teams must preserve repository team-name order", error.getMessage());
    }

    @Test
    void rejectsNestedTeamEvidenceOnDifferentScoringLane() throws Exception {
        Fixture fixture = providerFixture("nested-lane.db");
        var nflverseTeamSeason = fixture.teamAnalyzer().analyze("l1", "t1", 2026);
        var nested = new LeagueSeasonLineupPointsGapEvidenceAnalyzer.TeamEvidence(
            "t1", "Team One", nflverseTeamSeason);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new LeagueSeasonLineupPointsGapEvidenceAnalyzer.LeagueEvidenceReport(
                LeagueSeasonLineupPointsGapEvidenceAnalyzer.POLICY_ID,
                LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.METRIC_SCOPE,
                LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WEEK_UNIVERSE,
                LeagueSeasonLineupPointsGapEvidenceAnalyzer.PRESENTATION_SCOPE,
                HistoricalScoringLaneSelector.POLICY_ID,
                HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE,
                HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID,
                LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.POLICY_ID,
                "l1",
                "League",
                2026,
                List.of(nested))));

        assertEquals("nested team evidence must match selected league-season scoring lane", error.getMessage());
    }

    private Fixture providerFixture(String fileName) throws Exception {
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
        new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
            "l1", "t1", 2026, 1, List.of("s1", "s2", "s3"), List.of("s1", "s2"),
            "sleeper", AS_OF));
        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        eligibility.replace(new PlayerFantasyPositionObservation("p1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("p2", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("p3", "sleeper", AS_OF, List.of("WR")));
        return new Fixture(database);
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
    private static final LocalDate PROVIDER_AS_OF = LocalDate.of(2026, 9, 6);

    private record Fixture(Database database) {
        LeagueSeasonLineupPointsGapEvidenceAnalyzer analyzer() {
            return new LeagueSeasonLineupPointsGapEvidenceAnalyzer(database);
        }

        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer teamAnalyzer() {
            return new LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer(database);
        }

        void saveProviderPoints(List<ProviderPlayerWeekPointsEvidence> rows) throws Exception {
            new ProviderPlayerWeekPointsEvidenceRepository(database).replaceSeasonSnapshot(
                "l1", 2026, "sleeper", PROVIDER_AS_OF, rows);
        }

        ProviderPlayerWeekPointsEvidence providerPoints(String providerPlayerId, BigDecimal points) {
            return ProviderPlayerWeekPointsEvidence.create(
                "l1", "t1", "1", "provider-l1", 2026, 1, providerPlayerId, points,
                "sleeper", SleeperProviderNativeSeasonScoringAudit.EXPECTED_SOURCE_SURFACE, PROVIDER_AS_OF);
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
