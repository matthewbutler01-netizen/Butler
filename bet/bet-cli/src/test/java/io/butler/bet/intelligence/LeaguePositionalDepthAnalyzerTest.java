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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeaguePositionalDepthAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void reportsPlayerCountsAndTopValueDistributionWithinPosition() throws Exception {
        Fixture f = fixture();
        save(f, f.wr1, 100, LocalDate.of(2026, 9, 2));
        save(f, f.wr2, 60, LocalDate.of(2026, 9, 2));
        save(f, f.wr3, 40, LocalDate.of(2026, 9, 2));

        var report = new LeaguePositionalDepthAnalyzer(f.database).analyze(f.league.getId());
        var wr = report.teams().get(0).positions().get("WR");

        assertEquals(3, wr.totalPlayers());
        assertEquals(3, wr.valuedPlayers());
        assertEquals(200.0, wr.totalUsableValue(), 0.001);
        assertEquals(50.0, wr.topOneSharePercent(), 0.001);
        assertEquals(80.0, wr.topTwoSharePercent(), 0.001);
        assertEquals(100.0, wr.topThreeSharePercent(), 0.001);
        assertEquals("WR One", wr.topPlayers(1).get(0).playerName());
    }

    @Test
    void staleAndMissingPlayersRemainPartOfDepthCoverageButNotUsableValue() throws Exception {
        Fixture f = fixture();
        save(f, f.wr1, 100, LocalDate.of(2026, 9, 2));
        save(f, f.wr2, 60, LocalDate.of(2026, 8, 20));

        var report = new LeaguePositionalDepthAnalyzer(f.database)
            .analyze(f.league.getId(), LocalDate.of(2026, 9, 1));
        var wr = report.teams().get(0).positions().get("WR");

        assertEquals(3, wr.totalPlayers());
        assertEquals(1, wr.valuedPlayers());
        assertEquals(1, wr.stalePlayers());
        assertEquals(1, wr.missingPlayers());
        assertEquals(100.0, wr.totalUsableValue(), 0.001);
        assertEquals(100.0, wr.topOneSharePercent(), 0.001);
    }

    private void save(Fixture f, Player player, double value, LocalDate date) throws Exception {
        f.values.save(PlayerValue.create(player.getId(), value, f.source, date));
    }

    private Fixture fixture() throws Exception {
        Database database = new Database(tempDir.resolve("positional-depth-" + UUID.randomUUID() + ".db"));
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        LeagueValueFormatRepository formats = new LeagueValueFormatRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        League league = new League(UUID.randomUUID().toString(), "sleeper-league", "League");
        Team alpha = new Team(UUID.randomUUID().toString(), "1", league.getId(), "Alpha");
        Player wr1 = new Player(UUID.randomUUID().toString(), "wr1", "WR One", "WR", "CHI");
        Player wr2 = new Player(UUID.randomUUID().toString(), "wr2", "WR Two", "WR", "MIN");
        Player wr3 = new Player(UUID.randomUUID().toString(), "wr3", "WR Three", "WR", "GB");
        leagues.save(league);
        formats.save(league.getId(), LeagueValueFormat.ONE_QB);
        teams.save(alpha);
        players.save(wr1);
        players.save(wr2);
        players.save(wr3);
        rosters.save(new Roster(UUID.randomUUID().toString(), null, alpha.getId(), wr1.getId(), "STARTER"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, alpha.getId(), wr2.getId(), "STARTER"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, alpha.getId(), wr3.getId(), "BENCH"));
        return new Fixture(database, league, wr1, wr2, wr3, values, DynastyProcessValueImporter.SOURCE_1QB);
    }

    private record Fixture(Database database, League league, Player wr1, Player wr2, Player wr3,
                           PlayerValueRepository values, String source) {}
}
