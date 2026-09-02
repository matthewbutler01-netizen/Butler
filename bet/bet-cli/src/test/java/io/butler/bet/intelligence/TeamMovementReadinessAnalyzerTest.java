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

class TeamMovementReadinessAnalyzerTest {
    @TempDir
    Path tempDir;

    @Test
    void classifiesEachTeamFromExactAlignedCoverage() throws Exception {
        Database database = database();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        League league = new League("league", null, "League");
        Team ready = new Team("ready", null, league.getId(), "Ready");
        Team partial = new Team("partial", null, league.getId(), "Partial");
        Team blocked = new Team("blocked", null, league.getId(), "Blocked");
        leagues.save(league);
        teams.save(ready);
        teams.save(partial);
        teams.save(blocked);

        Player readyPlayer = player(players, "Ready Player", "WR");
        Player partialComplete = player(players, "Partial Complete", "RB");
        Player partialMissing = player(players, "Partial Missing", "TE");
        Player blockedPlayer = player(players, "Blocked Player", "QB");
        rosters.save(new Roster("r1", null, ready.getId(), readyPlayer.getId(), "STARTER"));
        rosters.save(new Roster("r2", null, partial.getId(), partialComplete.getId(), "STARTER"));
        rosters.save(new Roster("r3", null, partial.getId(), partialMissing.getId(), "BENCH"));
        rosters.save(new Roster("r4", null, blocked.getId(), blockedPlayer.getId(), "STARTER"));

        history(values, readyPlayer, 10.0, 20.0);
        history(values, partialComplete, 20.0, 25.0);
        values.save(PlayerValue.create(partialMissing.getId(), 30.0, "market", LocalDate.of(2026, 9, 1)));
        values.save(PlayerValue.create(blockedPlayer.getId(), 40.0, "market", LocalDate.of(2026, 9, 1)));

        var report = new TeamMovementReadinessAnalyzer(database).analyze("league", "market");

        assertEquals(LocalDate.of(2026, 8, 1), report.previousDate());
        assertEquals(LocalDate.of(2026, 9, 1), report.latestDate());
        assertEquals(3, report.teams().size());

        var readyStatus = report.teams().stream().filter(team -> team.teamId().equals("ready")).findFirst().orElseThrow();
        assertEquals(TeamMovementReadinessAnalyzer.TeamStatus.READY, readyStatus.status());
        assertEquals(100.0, readyStatus.coveragePercent());

        var partialStatus = report.teams().stream().filter(team -> team.teamId().equals("partial")).findFirst().orElseThrow();
        assertEquals(TeamMovementReadinessAnalyzer.TeamStatus.PARTIAL, partialStatus.status());
        assertEquals(1, partialStatus.comparablePlayers());
        assertEquals(1, partialStatus.missingPlayers());

        var blockedStatus = report.teams().stream().filter(team -> team.teamId().equals("blocked")).findFirst().orElseThrow();
        assertEquals(TeamMovementReadinessAnalyzer.TeamStatus.BLOCKED, blockedStatus.status());
        assertEquals(0, blockedStatus.comparablePlayers());
    }

    @Test
    void marksTeamsUnavailableWhenNoAlignedWindowExists() throws Exception {
        Database database = database();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        League league = new League("league", null, "League");
        Team team = new Team("team", null, league.getId(), "Team");
        Player player = player(players, "Player", "WR");
        leagues.save(league);
        teams.save(team);
        rosters.save(new Roster("r1", null, team.getId(), player.getId(), "STARTER"));
        values.save(PlayerValue.create(player.getId(), 10.0, "market", LocalDate.of(2026, 9, 1)));

        var report = new TeamMovementReadinessAnalyzer(database).analyze("league", "market");

        assertEquals(TeamMovementReadinessAnalyzer.TeamStatus.UNAVAILABLE, report.teams().get(0).status());
    }

    private static Player player(PlayerRepository players, String name, String position) throws Exception {
        Player player = Player.create(name, position, "KC");
        players.save(player);
        return player;
    }

    private static void history(PlayerValueRepository values, Player player, double previous, double latest) throws Exception {
        values.save(PlayerValue.create(player.getId(), previous, "market", LocalDate.of(2026, 8, 1)));
        values.save(PlayerValue.create(player.getId(), latest, "market", LocalDate.of(2026, 9, 1)));
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("team-readiness.db"));
        database.initialize();
        return database;
    }
}
