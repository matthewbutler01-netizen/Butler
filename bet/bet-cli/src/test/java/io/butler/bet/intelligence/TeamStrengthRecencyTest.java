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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TeamStrengthRecencyTest {
    @TempDir Path tempDir;

    @Test
    void teamReportsOldestAndLatestDatesForItsUsedValues() throws Exception {
        Database database = new Database(tempDir.resolve("team-recency.db"));
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        League league = new League(UUID.randomUUID().toString(), "league-ext", "League");
        Team team = new Team(UUID.randomUUID().toString(), "1", league.getId(), "Team");
        Player player1 = new Player(UUID.randomUUID().toString(), "p1", "First", "WR", "CHI");
        Player player2 = new Player(UUID.randomUUID().toString(), "p2", "Second", "RB", "MIN");
        leagues.save(league);
        teams.save(team);
        players.save(player1);
        players.save(player2);
        rosters.save(new Roster(UUID.randomUUID().toString(), null, team.getId(), player1.getId(), "STARTER"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, team.getId(), player2.getId(), "BENCH"));
        values.save(PlayerValue.create(player1.getId(), 100, "source", LocalDate.of(2026, 8, 30)));
        values.save(PlayerValue.create(player2.getId(), 90, "source", LocalDate.of(2026, 9, 1)));

        var rankedTeam = new TeamStrengthAnalyzer(database).rank(league.getId(), "source").teams().get(0);

        assertEquals(LocalDate.of(2026, 8, 30), rankedTeam.oldestValueDate());
        assertEquals(LocalDate.of(2026, 9, 1), rankedTeam.latestValueDate());
    }
}
