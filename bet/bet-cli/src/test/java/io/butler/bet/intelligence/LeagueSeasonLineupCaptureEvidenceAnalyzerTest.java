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

class LeagueSeasonLineupCaptureEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void exposesNeutralTeamNameOrderWithSeparateCaptureDenominatorsAndNoLeagueAggregate() throws Exception {
        Database database = fixture();

        var report = new LeagueSeasonLineupCaptureEvidenceAnalyzer(database).analyze("l1", 2026);

        assertEquals(LeagueSeasonLineupCaptureEvidenceAnalyzer.POLICY_ID, report.policyId());
        assertEquals(LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.METRIC_SCOPE, report.metricScope());
        assertEquals(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WEEK_UNIVERSE, report.weekUniverse());
        assertEquals(LeagueSeasonLineupCaptureEvidenceAnalyzer.PRESENTATION_SCOPE, report.presentationScope());
        assertEquals(LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.POLICY_ID, report.teamSeasonPolicyId());
        assertEquals(List.of("Alpha Team", "Beta Team"), report.teams().stream()
            .map(LeagueSeasonLineupCaptureEvidenceAnalyzer.TeamEvidence::teamName).toList());

        var alpha = report.teams().get(0).seasonEvidence();
        assertEquals(
            LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.CaptureRateState.UNAVAILABLE_NO_COMPARABLE_WEEKS,
            alpha.rateState());
        assertEquals(0, alpha.sourceSeasonPointsGap().aggregate().observedWeeks());

        var beta = report.teams().get(1).seasonEvidence();
        assertEquals(LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.CaptureRateState.AVAILABLE, beta.rateState());
        assertEquals(1, beta.sourceSeasonPointsGap().aggregate().observedWeeks());
        assertEquals(1, beta.sourceSeasonPointsGap().aggregate().comparableCompleteWeeks());
        assertEquals(new BigDecimal("0.625000"), beta.lineupCaptureRate().orElseThrow());

        assertEquals(
            List.of("policyId", "metricScope", "weekUniverse", "presentationScope", "teamSeasonPolicyId",
                "leagueId", "leagueName", "season", "teams"),
            Arrays.stream(LeagueSeasonLineupCaptureEvidenceAnalyzer.LeagueEvidenceReport.class.getRecordComponents())
                .map(component -> component.getName()).toList());
    }

    @Test
    void rejectsTeamListThatDoesNotPreserveRepositoryNameOrder() throws Exception {
        var report = new LeagueSeasonLineupCaptureEvidenceAnalyzer(fixture()).analyze("l1", 2026);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new LeagueSeasonLineupCaptureEvidenceAnalyzer.LeagueEvidenceReport(
                report.policyId(), report.metricScope(), report.weekUniverse(), report.presentationScope(),
                report.teamSeasonPolicyId(), report.leagueId(), report.leagueName(), report.season(),
                List.of(report.teams().get(1), report.teams().get(0))));

        assertEquals("teams must preserve repository team-name order", error.getMessage());
    }

    private Database fixture() throws Exception {
        Database database = new Database(tempDir.resolve("league-season-capture.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "L1", "League", 2026));
        TeamRepository teams = new TeamRepository(database);
        teams.save(new Team("t-beta", "2", "l1", "Beta Team"));
        teams.save(new Team("t-alpha", "1", "l1", "Alpha Team"));

        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("p1", "s1", "Quarterback", "QB", "CHI"));
        players.save(new Player("p2", "s2", "Receiver Two", "WR", "DET"));
        players.save(new Player("p3", "s3", "Receiver Three", "WR", "MIN"));
        new LeagueConfigurationObservationRepository(database).replace(new LeagueConfigurationObservation(
            "l1", "sleeper", AS_OF, 2026,
            List.of("QB", "WR", "BN"), Map.of("pass_td", 4.0, "rec_td", 6.0)));

        PlayerFantasyPositionObservationRepository eligibility =
            new PlayerFantasyPositionObservationRepository(database);
        eligibility.replace(new PlayerFantasyPositionObservation("p1", "sleeper", AS_OF, List.of("QB")));
        eligibility.replace(new PlayerFantasyPositionObservation("p2", "sleeper", AS_OF, List.of("WR")));
        eligibility.replace(new PlayerFantasyPositionObservation("p3", "sleeper", AS_OF, List.of("WR")));

        new TeamWeekRosterEvidenceRepository(database).save(TeamWeekRosterEvidence.create(
            "l1", "t-beta", 2026, 1,
            List.of("s1", "s2", "s3"), List.of("s1", "s2"), "sleeper", AS_OF));
        new PlayerWeekProductionCoverageRepository(database).replace(new PlayerWeekProductionCoverage(
            2026, 1, "nflverse", URI.create("https://example.test/week.csv"), AS_OF,
            50, 3, 0, List.of("p1", "p2", "p3")));
        PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
        production.save(PlayerWeekProduction.create(
            "p1", 2026, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, "nflverse", AS_OF));
        production.save(PlayerWeekProduction.create(
            "p2", 2026, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, "nflverse", AS_OF));
        production.save(PlayerWeekProduction.create(
            "p3", 2026, 1, 0, 0, 0, 0, 0, 0, 0, 2, 0, "nflverse", AS_OF));
        return database;
    }

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 5);
}
