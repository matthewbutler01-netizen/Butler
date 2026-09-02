package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerValue;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TeamValueMovementAnalyzerTest {
    @TempDir
    Path tempDir;

    @Test
    void aggregatesPlayerMovementByTeamRanksByAbsoluteDeltaAndExposesCoverage() throws Exception {
        Database database = database();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        League league = new League("league-a", null, "League A");
        Team alpha = new Team("team-a", null, league.getId(), "Alpha");
        Team beta = new Team("team-b", null, league.getId(), "Beta");
        Team gamma = new Team("team-c", null, league.getId(), "Gamma");
        leagues.save(league);
        teams.save(alpha);
        teams.save(beta);
        teams.save(gamma);

        Player aRiser = Player.create("Alpha Riser", "WR", "KC");
        Player aFaller = Player.create("Alpha Faller", "RB", "DET");
        Player bFaller = Player.create("Beta Faller", "QB", "BUF");
        Player insufficient = Player.create("Insufficient", "TE", "DAL");
        Player noHistory = Player.create("No History", "WR", "CHI");
        players.save(aRiser);
        players.save(aFaller);
        players.save(bFaller);
        players.save(insufficient);
        players.save(noHistory);

        rosters.save(new Roster("r1", null, alpha.getId(), aRiser.getId(), "STARTER"));
        rosters.save(new Roster("r2", null, alpha.getId(), aFaller.getId(), "BENCH"));
        rosters.save(new Roster("r3", null, beta.getId(), bFaller.getId(), "STARTER"));
        rosters.save(new Roster("r4", null, beta.getId(), insufficient.getId(), "BENCH"));
        rosters.save(new Roster("r5", null, gamma.getId(), noHistory.getId(), "STARTER"));

        saveHistory(values, aRiser, 60.0, 80.0);
        saveHistory(values, aFaller, 70.0, 60.0);
        saveHistory(values, bFaller, 100.0, 65.0);
        values.save(PlayerValue.create(insufficient.getId(), 50.0, "market", LocalDate.of(2026, 9, 1)));

        var report = new TeamValueMovementAnalyzer(database).analyze("  league-a  ", "  market  ");

        assertEquals("league-a", report.leagueId());
        assertEquals("market", report.source());
        assertEquals(3, report.teams().size());

        var betaMovement = report.teams().get(0);
        assertEquals("Beta", betaMovement.teamName());
        assertEquals(-35.0, betaMovement.delta());
        assertEquals(2, betaMovement.rosterSize());
        assertEquals(1, betaMovement.playersWithHistory());
        assertEquals(1, betaMovement.playersWithoutHistory());
        assertEquals(50.0, betaMovement.historyCoveragePercent());
        assertEquals(0, betaMovement.risers());
        assertEquals(1, betaMovement.fallers());
        assertEquals(0, betaMovement.unchanged());

        var alphaMovement = report.teams().get(1);
        assertEquals("Alpha", alphaMovement.teamName());
        assertEquals(10.0, alphaMovement.delta());
        assertEquals(2, alphaMovement.playersWithHistory());
        assertEquals(0, alphaMovement.playersWithoutHistory());
        assertEquals(100.0, alphaMovement.historyCoveragePercent());
        assertEquals(1, alphaMovement.risers());
        assertEquals(1, alphaMovement.fallers());

        var gammaMovement = report.teams().get(2);
        assertEquals("Gamma", gammaMovement.teamName());
        assertEquals(0.0, gammaMovement.delta());
        assertEquals(1, gammaMovement.rosterSize());
        assertEquals(0, gammaMovement.playersWithHistory());
        assertEquals(1, gammaMovement.playersWithoutHistory());
        assertEquals(0.0, gammaMovement.historyCoveragePercent());
    }

    @Test
    void rejectsBlankLeagueOrSource() throws Exception {
        Database database = database();
        TeamValueMovementAnalyzer analyzer = new TeamValueMovementAnalyzer(database);
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze("   ", "market"));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze("league", "   "));
    }

    private static void saveHistory(PlayerValueRepository values, Player player,
                                    double previous, double latest) throws Exception {
        values.save(PlayerValue.create(player.getId(), previous, "market", LocalDate.of(2026, 8, 1)));
        values.save(PlayerValue.create(player.getId(), latest, "market", LocalDate.of(2026, 9, 1)));
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("team-movement.db"));
        database.initialize();
        return database;
    }
}
