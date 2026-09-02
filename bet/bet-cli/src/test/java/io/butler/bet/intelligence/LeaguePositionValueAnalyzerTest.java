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

class LeaguePositionValueAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void aggregatesNeutralPositionValueAndCoverageByTeam() throws Exception {
        Fixture f = fixture();
        String source = DynastyProcessValueImporter.SOURCE_1QB;
        f.values.save(PlayerValue.create(f.qb.getId(), 100, source, LocalDate.of(2026, 9, 1)));
        f.values.save(PlayerValue.create(f.wr.getId(), 80, source, LocalDate.of(2026, 9, 1)));

        var report = new LeaguePositionValueAnalyzer(f.database).analyze(f.league.getId());

        assertEquals(3, report.totalPlayers());
        assertEquals(2, report.valuedPlayers());
        assertEquals(1, report.missingPlayers());
        assertEquals(0, report.stalePlayers());
        assertEquals(2, report.teams().size());
        assertEquals(100.0, report.leaguePositions().get("QB").value());
        assertEquals(80.0, report.leaguePositions().get("WR").value());
        assertEquals(0.0, report.leaguePositions().get("RB").value());
        assertEquals(0.0, report.leaguePositions().get("RB").coveragePercent());

        var alpha = report.teams().stream().filter(team -> team.teamName().equals("Alpha")).findFirst().orElseThrow();
        assertEquals(180.0, alpha.totalPlayerValue());
        assertEquals(2, alpha.valuedPlayers());
        assertEquals(100.0, alpha.coveragePercent());
    }

    @Test
    void minimumAsOfExcludesStaleValuesButKeepsThemVisibleInCoverage() throws Exception {
        Fixture f = fixture();
        String source = DynastyProcessValueImporter.SOURCE_1QB;
        f.values.save(PlayerValue.create(f.qb.getId(), 100, source, LocalDate.of(2026, 9, 1)));
        f.values.save(PlayerValue.create(f.wr.getId(), 80, source, LocalDate.of(2026, 8, 30)));
        f.values.save(PlayerValue.create(f.rb.getId(), 60, source, LocalDate.of(2026, 9, 1)));

        var report = new LeaguePositionValueAnalyzer(f.database).analyze(
            f.league.getId(), LocalDate.of(2026, 9, 1));

        assertEquals(2, report.valuedPlayers());
        assertEquals(1, report.stalePlayers());
        assertEquals(0, report.missingPlayers());
        assertEquals(0.0, report.leaguePositions().get("WR").value());
        assertEquals(1, report.leaguePositions().get("WR").stalePlayers());
        assertEquals(0.0, report.leaguePositions().get("WR").coveragePercent());
    }

    private Fixture fixture() throws Exception {
        Database database = new Database(tempDir.resolve("position-context-" + UUID.randomUUID() + ".db"));
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        LeagueValueFormatRepository formats = new LeagueValueFormatRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        League league = new League(UUID.randomUUID().toString(), "sleeper", "League");
        Team alpha = new Team(UUID.randomUUID().toString(), "1", league.getId(), "Alpha");
        Team beta = new Team(UUID.randomUUID().toString(), "2", league.getId(), "Beta");
        Player qb = new Player(UUID.randomUUID().toString(), "qb", "QB One", "QB", "CHI");
        Player wr = new Player(UUID.randomUUID().toString(), "wr", "WR One", "WR", "MIN");
        Player rb = new Player(UUID.randomUUID().toString(), "rb", "RB One", "RB", "GB");

        leagues.save(league);
        formats.save(league.getId(), LeagueValueFormat.ONE_QB);
        teams.save(alpha);
        teams.save(beta);
        players.save(qb);
        players.save(wr);
        players.save(rb);
        rosters.save(new Roster(UUID.randomUUID().toString(), null, alpha.getId(), qb.getId(), "STARTER"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, alpha.getId(), wr.getId(), "STARTER"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, beta.getId(), rb.getId(), "STARTER"));

        return new Fixture(database, league, qb, wr, rb, values);
    }

    private record Fixture(Database database, League league, Player qb, Player wr, Player rb,
                           PlayerValueRepository values) {}
}
