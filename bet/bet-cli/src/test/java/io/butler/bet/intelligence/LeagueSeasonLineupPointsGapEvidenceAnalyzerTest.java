package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

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
        assertEquals(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.POLICY_ID, report.teamSeasonPolicyId());
        assertEquals(List.of("Alpha Team", "Beta Team"), report.teams().stream()
            .map(LeagueSeasonLineupPointsGapEvidenceAnalyzer.TeamEvidence::teamName).toList());
        assertTrue(report.teams().stream().allMatch(team -> team.seasonEvidence().aggregate().observedWeeks() == 0));
        assertTrue(report.teams().stream().allMatch(
            team -> team.seasonEvidence().aggregate().comparableTotalPointsGap().isEmpty()));

        assertEquals(
            List.of("policyId", "metricScope", "weekUniverse", "presentationScope", "teamSeasonPolicyId",
                "leagueId", "leagueName", "season", "teams"),
            Arrays.stream(LeagueSeasonLineupPointsGapEvidenceAnalyzer.LeagueEvidenceReport.class.getRecordComponents())
                .map(component -> component.getName()).toList());
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
                report.teamSeasonPolicyId(),
                report.leagueId(),
                report.leagueName(),
                report.season(),
                List.of(report.teams().get(1), report.teams().get(0))));

        assertEquals("teams must preserve repository team-name order", error.getMessage());
    }
}
