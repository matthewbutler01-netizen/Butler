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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamStrengthAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void quarterbackAndStarterDepthIncreaseStrengthScore() {
        double strong = TeamStrengthAnalyzer.score(
            Map.of("QB", 2, "RB", 4, "WR", 5, "TE", 2),
            Map.of("STARTER", 9, "BENCH", 4));
        double thin = TeamStrengthAnalyzer.score(
            Map.of("QB", 1, "RB", 2, "WR", 3, "TE", 1),
            Map.of("STARTER", 6, "BENCH", 3));
        assertTrue(strong > thin);
    }

    @Test
    void startersAreWorthMoreThanBenchOrReserveSlots() {
        double starter = TeamStrengthAnalyzer.score(Map.of(), Map.of("STARTER", 1));
        double bench = TeamStrengthAnalyzer.score(Map.of(), Map.of("BENCH", 1));
        double reserve = TeamStrengthAnalyzer.score(Map.of(), Map.of("RESERVE", 1));
        assertTrue(starter > bench);
        assertTrue(bench > reserve);
    }

    @Test
    void missingValuesAreCountedOnlyForSelectedSource() throws Exception {
        Fixture fixture = fixture();
        fixture.values.save(PlayerValue.create(fixture.player1.getId(), 100, "market-a", LocalDate.of(2026, 9, 1)));
        fixture.values.save(PlayerValue.create(fixture.player2.getId(), 999, "market-b", LocalDate.of(2026, 9, 1)));

        var report = new TeamStrengthAnalyzer(fixture.database).rank(fixture.league.getId(), "market-a");
        var team = report.teams().get(0);

        assertEquals("market-a", report.source());
        assertEquals(100.0, team.playerValue());
        assertEquals(1, team.valuedPlayers());
        assertEquals(1, team.missingValues());
    }

    @Test
    void compositionBreaksTiesButNeverOverridesPlayerValue() throws Exception {
        Fixture fixture = fixture();
        Team weakComposition = fixture.team;
        Team strongComposition = new Team(UUID.randomUUID().toString(), "2", fixture.league.getId(), "Strong Composition");
        fixture.teams.save(strongComposition);
        Player strongTeamPlayer = new Player(UUID.randomUUID().toString(), "p3", "Third", "QB", "CHI");
        fixture.players.save(strongTeamPlayer);
        fixture.rosters.save(new Roster(UUID.randomUUID().toString(), null, strongComposition.getId(), strongTeamPlayer.getId(), "STARTER"));

        fixture.values.save(PlayerValue.create(fixture.player1.getId(), 101, "source", LocalDate.of(2026, 9, 1)));
        fixture.values.save(PlayerValue.create(strongTeamPlayer.getId(), 100, "source", LocalDate.of(2026, 9, 1)));
        var valueWins = new TeamStrengthAnalyzer(fixture.database).rank(fixture.league.getId(), "source");
        assertEquals(weakComposition.getId(), valueWins.teams().get(0).teamId());

        fixture.values.save(PlayerValue.create(fixture.player1.getId(), 100, "source", LocalDate.of(2026, 9, 2)));
        var tie = new TeamStrengthAnalyzer(fixture.database).rank(fixture.league.getId(), "source");
        assertEquals(strongComposition.getId(), tie.teams().get(0).teamId());
    }

    @Test
    void blankSourceAndLeagueIdAreRejected() throws Exception {
        Fixture fixture = fixture();
        TeamStrengthAnalyzer analyzer = new TeamStrengthAnalyzer(fixture.database);
        assertThrows(IllegalArgumentException.class, () -> analyzer.rank(fixture.league.getId(), "   "));
        assertThrows(IllegalArgumentException.class, () -> analyzer.rank("   ", "source"));
    }

    private Fixture fixture() throws Exception {
        Database database = new Database(tempDir.resolve("ranking.db"));
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        League league = new League(UUID.randomUUID().toString(), "league-ext", "League");
        Team team = new Team(UUID.randomUUID().toString(), "1", league.getId(), "Value Leader");
        Player player1 = new Player(UUID.randomUUID().toString(), "p1", "First", "K", "CHI");
        Player player2 = new Player(UUID.randomUUID().toString(), "p2", "Second", "K", "MIN");
        leagues.save(league);
        teams.save(team);
        players.save(player1);
        players.save(player2);
        rosters.save(new Roster(UUID.randomUUID().toString(), null, team.getId(), player1.getId(), "BENCH"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, team.getId(), player2.getId(), "BENCH"));
        return new Fixture(database, league, team, player1, player2, teams, players, rosters, values);
    }

    private record Fixture(Database database, League league, Team team, Player player1, Player player2,
                           TeamRepository teams, PlayerRepository players, RosterRepository rosters,
                           PlayerValueRepository values) {}
}
