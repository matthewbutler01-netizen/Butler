package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueValueFormatRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueValueFormat;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerValue;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeagueMovementAutoSourceTest {
    @TempDir
    Path tempDir;

    @Test
    void moversAndTeamMovementUseLeagueFormatSourceAutomatically() throws Exception {
        Database database = new Database(tempDir.resolve("movement.db"));
        database.initialize();
        League league = new League("league", "sleeper", "League");
        Team team = new Team("team", "1", league.getId(), "Team");
        Player player = new Player("player", "p1", "Player", "QB", "CHI");
        new LeagueRepository(database).save(league);
        new TeamRepository(database).save(team);
        new PlayerRepository(database).save(player);
        new RosterRepository(database).save(new Roster("roster", null, team.getId(), player.getId(), "STARTER"));
        new LeagueValueFormatRepository(database).save(league.getId(), LeagueValueFormat.TWO_QB);

        PlayerValueRepository values = new PlayerValueRepository(database);
        values.save(PlayerValue.create(player.getId(), 10.0, DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 8, 1)));
        values.save(PlayerValue.create(player.getId(), 12.0, DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));
        values.save(PlayerValue.create(player.getId(), 20.0, DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 8, 1)));
        values.save(PlayerValue.create(player.getId(), 27.0, DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 9, 1)));

        var movers = new LeagueValueMoverAnalyzer(database).analyze(league.getId());
        assertEquals(DynastyProcessValueImporter.SOURCE_2QB, movers.source());
        assertEquals(7.0, movers.movers().getFirst().delta());

        var teams = new TeamValueMovementAnalyzer(database).analyze(league.getId());
        assertEquals(DynastyProcessValueImporter.SOURCE_2QB, teams.source());
        assertEquals(7.0, teams.teams().getFirst().delta());
    }
}
