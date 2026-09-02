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

class TeamStrengthAutoSourceTest {
    @TempDir
    Path tempDir;

    @Test
    void automaticRankingUsesPersistedLeagueFormatSource() throws Exception {
        Database database = new Database(tempDir.resolve("auto-rank.db"));
        database.initialize();
        League league = new League("league", "sleeper", "League");
        Team team = new Team("team", "1", league.getId(), "Team");
        Player player = new Player("player", "p1", "Quarterback", "QB", "CHI");
        new LeagueRepository(database).save(league);
        new TeamRepository(database).save(team);
        new PlayerRepository(database).save(player);
        new RosterRepository(database).save(new Roster("roster", null, team.getId(), player.getId(), "STARTER"));
        PlayerValueRepository values = new PlayerValueRepository(database);
        values.save(PlayerValue.create(player.getId(), 10.0, DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));
        values.save(PlayerValue.create(player.getId(), 25.0, DynastyProcessValueImporter.SOURCE_2QB, LocalDate.of(2026, 9, 1)));
        LeagueValueFormatRepository formats = new LeagueValueFormatRepository(database);

        formats.save(league.getId(), LeagueValueFormat.ONE_QB);
        var oneQb = new TeamStrengthAnalyzer(database).rank(league.getId());
        assertEquals(DynastyProcessValueImporter.SOURCE_1QB, oneQb.source());
        assertEquals(10.0, oneQb.teams().getFirst().playerValue());

        formats.save(league.getId(), LeagueValueFormat.TWO_QB);
        var twoQb = new TeamStrengthAnalyzer(database).rank(league.getId());
        assertEquals(DynastyProcessValueImporter.SOURCE_2QB, twoQb.source());
        assertEquals(25.0, twoQb.teams().getFirst().playerValue());
    }

    @Test
    void automaticRankingKeepsMinimumDateGuard() throws Exception {
        Database database = new Database(tempDir.resolve("auto-date.db"));
        database.initialize();
        League league = new League("league", null, "League");
        Team team = new Team("team", null, league.getId(), "Team");
        Player player = new Player("player", null, "Player", "WR", "KC");
        new LeagueRepository(database).save(league);
        new TeamRepository(database).save(team);
        new PlayerRepository(database).save(player);
        new RosterRepository(database).save(new Roster("roster", null, team.getId(), player.getId(), "STARTER"));
        new LeagueValueFormatRepository(database).save(league.getId(), LeagueValueFormat.ONE_QB);
        new PlayerValueRepository(database).save(PlayerValue.create(player.getId(), 10.0,
            DynastyProcessValueImporter.SOURCE_1QB, LocalDate.of(2026, 9, 1)));

        var report = new TeamStrengthAnalyzer(database).rank(league.getId(), LocalDate.of(2026, 9, 1));
        assertEquals(DynastyProcessValueImporter.SOURCE_1QB, report.source());
    }
}
