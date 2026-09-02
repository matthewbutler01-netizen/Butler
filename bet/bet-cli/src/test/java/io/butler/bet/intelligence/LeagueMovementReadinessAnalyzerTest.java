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

class LeagueMovementReadinessAnalyzerTest {
    @TempDir
    Path tempDir;

    @Test
    void classifiesUnavailableBlockedPartialAndReadyWithoutInventingThresholds() throws Exception {
        assertEquals(LeagueMovementReadinessAnalyzer.Readiness.UNAVAILABLE, unavailable().readiness());
        assertEquals(LeagueMovementReadinessAnalyzer.Readiness.BLOCKED, blocked().readiness());
        assertEquals(LeagueMovementReadinessAnalyzer.Readiness.PARTIAL, partial().readiness());
        assertEquals(LeagueMovementReadinessAnalyzer.Readiness.READY, ready().readiness());
    }

    private LeagueMovementReadinessAnalyzer.ReadinessReport unavailable() throws Exception {
        Fixture fixture = fixture("unavailable");
        fixture.values.save(PlayerValue.create(fixture.first.getId(), 10.0, "market", LocalDate.of(2026, 9, 1)));
        return fixture.analyzer.analyze(fixture.league.getId(), "market");
    }

    private LeagueMovementReadinessAnalyzer.ReadinessReport blocked() throws Exception {
        Fixture fixture = fixture("blocked");
        fixture.values.save(PlayerValue.create(fixture.first.getId(), 10.0, "market", LocalDate.of(2026, 8, 1)));
        fixture.values.save(PlayerValue.create(fixture.second.getId(), 20.0, "market", LocalDate.of(2026, 9, 1)));
        var report = fixture.analyzer.analyze(fixture.league.getId(), "market");
        assertEquals(2, report.totalPlayers());
        assertEquals(0, report.comparablePlayers());
        assertEquals(2, report.missingPlayers());
        return report;
    }

    private LeagueMovementReadinessAnalyzer.ReadinessReport partial() throws Exception {
        Fixture fixture = fixture("partial");
        saveAlignedHistory(fixture.values, fixture.first, 10.0, 15.0);
        fixture.values.save(PlayerValue.create(fixture.second.getId(), 20.0, "market", LocalDate.of(2026, 9, 1)));
        var report = fixture.analyzer.analyze(fixture.league.getId(), "market");
        assertEquals(1, report.comparablePlayers());
        assertEquals(1, report.missingPlayers());
        assertEquals(50.0, report.coveragePercent());
        return report;
    }

    private LeagueMovementReadinessAnalyzer.ReadinessReport ready() throws Exception {
        Fixture fixture = fixture("ready");
        saveAlignedHistory(fixture.values, fixture.first, 10.0, 15.0);
        saveAlignedHistory(fixture.values, fixture.second, 20.0, 18.0);
        var report = fixture.analyzer.analyze(fixture.league.getId(), "market");
        assertEquals(2, report.comparablePlayers());
        assertEquals(0, report.missingPlayers());
        assertEquals(LocalDate.of(2026, 8, 1), report.previousDate());
        assertEquals(LocalDate.of(2026, 9, 1), report.latestDate());
        return report;
    }

    private Fixture fixture(String name) throws Exception {
        Database database = new Database(tempDir.resolve(name + ".db"));
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        League league = new League("league-" + name, null, "League");
        Team team = new Team("team-" + name, null, league.getId(), "Team");
        Player first = Player.create("First", "WR", "KC");
        Player second = Player.create("Second", "RB", "DET");
        leagues.save(league);
        teams.save(team);
        players.save(first);
        players.save(second);
        rosters.save(new Roster("r1-" + name, null, team.getId(), first.getId(), "STARTER"));
        rosters.save(new Roster("r2-" + name, null, team.getId(), second.getId(), "BENCH"));
        return new Fixture(league, first, second, values, new LeagueMovementReadinessAnalyzer(database));
    }

    private static void saveAlignedHistory(PlayerValueRepository values, Player player,
                                           double previous, double latest) throws Exception {
        values.save(PlayerValue.create(player.getId(), previous, "market", LocalDate.of(2026, 8, 1)));
        values.save(PlayerValue.create(player.getId(), latest, "market", LocalDate.of(2026, 9, 1)));
    }

    private record Fixture(League league, Player first, Player second,
                           PlayerValueRepository values, LeagueMovementReadinessAnalyzer analyzer) {}
}
