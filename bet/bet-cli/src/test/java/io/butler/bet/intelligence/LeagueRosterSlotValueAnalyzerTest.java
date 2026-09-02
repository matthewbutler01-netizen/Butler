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

class LeagueRosterSlotValueAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void reportsStarterBenchAndReserveValueWithoutStrategyLabels() throws Exception {
        Fixture f = fixture();
        f.values.save(PlayerValue.create(f.starter.getId(), 100, f.source, LocalDate.of(2026, 9, 2)));
        f.values.save(PlayerValue.create(f.bench.getId(), 60, f.source, LocalDate.of(2026, 9, 2)));
        f.values.save(PlayerValue.create(f.reserve.getId(), 40, f.source, LocalDate.of(2026, 9, 2)));

        var report = new LeagueRosterSlotValueAnalyzer(f.database).analyze(f.league.getId());
        var team = report.teams().get(0);

        assertEquals(200.0, team.totalUsablePlayerValue(), 0.001);
        assertEquals(50.0, team.starterValueSharePercent(), 0.001);
        assertEquals(100.0, team.slots().get("STARTER").value(), 0.001);
        assertEquals(60.0, team.slots().get("BENCH").value(), 0.001);
        assertEquals(40.0, team.slots().get("RESERVE").value(), 0.001);
    }

    @Test
    void staleAndMissingPlayersRemainVisibleButDoNotCountAsUsableValue() throws Exception {
        Fixture f = fixture();
        f.values.save(PlayerValue.create(f.starter.getId(), 100, f.source, LocalDate.of(2026, 9, 2)));
        f.values.save(PlayerValue.create(f.bench.getId(), 60, f.source, LocalDate.of(2026, 8, 20)));

        var report = new LeagueRosterSlotValueAnalyzer(f.database)
            .analyze(f.league.getId(), LocalDate.of(2026, 9, 1));
        var team = report.teams().get(0);

        assertEquals(100.0, team.totalUsablePlayerValue(), 0.001);
        assertEquals(1, team.slots().get("BENCH").stalePlayers());
        assertEquals(1, team.slots().get("RESERVE").missingPlayers());
        assertEquals(100.0, team.starterValueSharePercent(), 0.001);
    }

    private Fixture fixture() throws Exception {
        Database database = new Database(tempDir.resolve("roster-slot-" + UUID.randomUUID() + ".db"));
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        LeagueValueFormatRepository formats = new LeagueValueFormatRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        League league = new League(UUID.randomUUID().toString(), "sleeper-league", "League");
        Team alpha = new Team(UUID.randomUUID().toString(), "1", league.getId(), "Alpha");
        Player starter = new Player(UUID.randomUUID().toString(), "s", "Starter", "QB", "CHI");
        Player bench = new Player(UUID.randomUUID().toString(), "b", "Bench", "WR", "MIN");
        Player reserve = new Player(UUID.randomUUID().toString(), "r", "Reserve", "RB", "GB");
        leagues.save(league);
        formats.save(league.getId(), LeagueValueFormat.ONE_QB);
        teams.save(alpha);
        players.save(starter);
        players.save(bench);
        players.save(reserve);
        rosters.save(new Roster(UUID.randomUUID().toString(), null, alpha.getId(), starter.getId(), "STARTER"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, alpha.getId(), bench.getId(), "BENCH"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, alpha.getId(), reserve.getId(), "RESERVE"));

        return new Fixture(database, league, starter, bench, reserve, values,
            DynastyProcessValueImporter.SOURCE_1QB);
    }

    private record Fixture(Database database, League league, Player starter, Player bench, Player reserve,
                           PlayerValueRepository values, String source) {}
}
