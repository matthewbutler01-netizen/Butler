package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynastyProcessLeaguePreviewAnalyzerTest {
    @TempDir
    Path tempDir;

    @Test
    void reportsOnlyRosteredLeagueImpactAndExactGaps() throws Exception {
        Database database = database();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);

        League league = new League("league", "sleeper-league", "League");
        League otherLeague = new League("other", "other-sleeper", "Other");
        Team alpha = new Team("alpha", "1", league.getId(), "Alpha");
        Team beta = new Team("beta", "2", league.getId(), "Beta");
        Team outsider = new Team("outsider", "3", otherLeague.getId(), "Outsider");
        Player matched = new Player("matched", "100", "Matched", "WR", "KC");
        Player unmatched = new Player("unmatched", "200", "Unmatched", "RB", "DET");
        Player noSleeper = Player.create("Manual", "QB", "BUF");
        Player outside = new Player("outside", "300", "Outside", "TE", "DAL");

        leagues.save(league);
        leagues.save(otherLeague);
        teams.save(alpha);
        teams.save(beta);
        teams.save(outsider);
        players.save(matched);
        players.save(unmatched);
        players.save(noSleeper);
        players.save(outside);
        rosters.save(new Roster("r1", null, alpha.getId(), matched.getId(), "STARTER"));
        rosters.save(new Roster("r2", null, alpha.getId(), unmatched.getId(), "BENCH"));
        rosters.save(new Roster("r3", null, beta.getId(), noSleeper.getId(), "STARTER"));
        rosters.save(new Roster("r4", null, outsider.getId(), outside.getId(), "STARTER"));

        var preview = preview(unmatched, outside);
        var report = new DynastyProcessLeaguePreviewAnalyzer(database).analyze("  league  ", preview);

        assertEquals("league", report.leagueId());
        assertEquals(LocalDate.of(2026, 8, 28), report.asOfDate());
        assertEquals(3, report.rosteredPlayers());
        assertEquals(1, report.matchedPlayers());
        assertEquals(1, report.unmatchedPlayers());
        assertEquals(1, report.ineligiblePlayers());
        assertEquals(2, report.affectedTeams());
        assertEquals(100.0 / 3.0, report.coveragePercent(), 0.0001);
        assertEquals(2, report.teams().size());
        assertEquals("Alpha", report.teams().get(0).teamName());
        assertEquals(1, report.teams().get(0).unmatchedPlayers());
        assertEquals(DynastyProcessLeaguePreviewAnalyzer.GapReason.PROVIDER_UNMATCHED,
            report.teams().get(0).gaps().get(0).reason());
        assertEquals("Beta", report.teams().get(1).teamName());
        assertEquals(1, report.teams().get(1).ineligiblePlayers());
        assertEquals(DynastyProcessLeaguePreviewAnalyzer.GapReason.NO_SLEEPER_ID,
            report.teams().get(1).gaps().get(0).reason());
    }

    @Test
    void rejectsUnknownLeague() throws Exception {
        Database database = database();
        assertThrows(IllegalArgumentException.class,
            () -> new DynastyProcessLeaguePreviewAnalyzer(database).analyze("missing", preview()));
    }

    private DynastyProcessValueImporter.ImportResult preview(Player... unmatchedPlayers) {
        var unmatched = java.util.Arrays.stream(unmatchedPlayers)
            .map(player -> new DynastyProcessValueImporter.UnmatchedPlayer(
                player.getId(), player.getExternalId(), player.getDisplayName()))
            .toList();
        var diagnostics = new DynastyProcessValueImporter.ProviderDiagnostics(
            3, 3, 3, 3, 0, 2, 0, 1);
        return new DynastyProcessValueImporter.ImportResult(
            LocalDate.of(2026, 8, 28), 3, 1, 0, unmatched.size(), 2, diagnostics, unmatched);
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("league-preview.db"));
        database.initialize();
        return database;
    }
}
