package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekMatchupEvidenceRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamWeekMatchupEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LeagueTeamWeekMatchupEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void resolvesExactlyOneProviderPairedOpponent() throws Exception {
        Fixture fixture = fixture();
        TeamWeekMatchupEvidenceRepository evidence = new TeamWeekMatchupEvidenceRepository(fixture.database());
        LocalDate asOf = LocalDate.of(2026, 9, 18);

        evidence.save(TeamWeekMatchupEvidence.create(
            fixture.league().getId(), fixture.alpha().getId(), 2026, 3, 44, "sleeper", asOf));
        evidence.save(TeamWeekMatchupEvidence.create(
            fixture.league().getId(), fixture.beta().getId(), 2026, 3, 44, "sleeper", asOf));

        var report = new LeagueTeamWeekMatchupEvidenceAnalyzer(fixture.database()).analyze(
            fixture.league().getId(), fixture.alpha().getId(), 2026, 3);

        assertEquals(44, report.providerMatchupId());
        assertEquals(fixture.alpha().getId(), report.teamId());
        assertEquals("Alpha", report.teamName());
        assertEquals(fixture.beta().getId(), report.opponentTeamId());
        assertEquals("Beta", report.opponentTeamName());
        assertEquals(asOf, report.asOfDate());
        assertEquals(LeagueTeamWeekMatchupEvidenceAnalyzer.POLICY_ID, report.policyId());
    }

    @Test
    void blocksWhenTargetPairingIsMissing() throws Exception {
        Fixture fixture = fixture();

        var error = assertThrows(IllegalStateException.class, () ->
            new LeagueTeamWeekMatchupEvidenceAnalyzer(fixture.database()).analyze(
                fixture.league().getId(), fixture.alpha().getId(), 2026, 3));

        assertEquals(
            "BF-840 BLOCKED: exact matchup pairing evidence is unavailable for target team/week",
            error.getMessage());
    }

    @Test
    void blocksWhenProviderPairingDoesNotResolveToExactlyTwoTeams() throws Exception {
        Fixture fixture = fixture();
        Team gamma = new Team("team-3", "3", fixture.league().getId(), "Gamma");
        new TeamRepository(fixture.database()).save(gamma);

        TeamWeekMatchupEvidenceRepository evidence = new TeamWeekMatchupEvidenceRepository(fixture.database());
        LocalDate asOf = LocalDate.of(2026, 9, 18);
        evidence.save(TeamWeekMatchupEvidence.create(
            fixture.league().getId(), fixture.alpha().getId(), 2026, 3, 44, "sleeper", asOf));
        evidence.save(TeamWeekMatchupEvidence.create(
            fixture.league().getId(), fixture.beta().getId(), 2026, 3, 44, "sleeper", asOf));
        evidence.save(TeamWeekMatchupEvidence.create(
            fixture.league().getId(), gamma.getId(), 2026, 3, 44, "sleeper", asOf));

        var error = assertThrows(IllegalStateException.class, () ->
            new LeagueTeamWeekMatchupEvidenceAnalyzer(fixture.database()).analyze(
                fixture.league().getId(), fixture.alpha().getId(), 2026, 3));

        assertEquals(
            "BF-840 BLOCKED: provider matchup id 44 resolves to 3 Butler teams instead of exactly two",
            error.getMessage());
    }

    @Test
    void blocksMixedSnapshotDates() throws Exception {
        Fixture fixture = fixture();
        TeamWeekMatchupEvidenceRepository evidence = new TeamWeekMatchupEvidenceRepository(fixture.database());
        evidence.save(TeamWeekMatchupEvidence.create(
            fixture.league().getId(), fixture.alpha().getId(), 2026, 3, 44, "sleeper", LocalDate.of(2026, 9, 18)));
        evidence.save(TeamWeekMatchupEvidence.create(
            fixture.league().getId(), fixture.beta().getId(), 2026, 3, 44, "sleeper", LocalDate.of(2026, 9, 17)));

        var error = assertThrows(IllegalStateException.class, () ->
            new LeagueTeamWeekMatchupEvidenceAnalyzer(fixture.database()).analyze(
                fixture.league().getId(), fixture.alpha().getId(), 2026, 3));

        assertEquals(
            "BF-840 BLOCKED: target and opponent matchup evidence do not share one provider snapshot date",
            error.getMessage());
    }

    private Fixture fixture() throws Exception {
        Database database = new Database(tempDir.resolve("matchup.db"));
        database.initialize();
        League league = new League("league-1", "L1", "Test League", 2026);
        Team alpha = new Team("team-1", "1", league.getId(), "Alpha");
        Team beta = new Team("team-2", "2", league.getId(), "Beta");
        new LeagueRepository(database).save(league);
        TeamRepository teams = new TeamRepository(database);
        teams.save(alpha);
        teams.save(beta);
        return new Fixture(database, league, alpha, beta);
    }

    private record Fixture(Database database, League league, Team alpha, Team beta) {}
}
