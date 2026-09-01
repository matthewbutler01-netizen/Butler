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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueValueCoverageAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void reportsCoverageAndRecencyForEveryPersistedSource() throws Exception {
        Database database = database();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        League league = new League(UUID.randomUUID().toString(), "league-ext", "League");
        Team team = new Team(UUID.randomUUID().toString(), "team-ext", league.getId(), "Team");
        Player first = new Player(UUID.randomUUID().toString(), "p1", "First", "QB", "BUF");
        Player second = new Player(UUID.randomUUID().toString(), "p2", "Second", "WR", "MIN");
        Player outside = new Player(UUID.randomUUID().toString(), "p3", "Outside", "RB", "DET");
        leagues.save(league);
        teams.save(team);
        players.save(first);
        players.save(second);
        players.save(outside);
        rosters.save(new Roster(UUID.randomUUID().toString(), null, team.getId(), first.getId(), "STARTER"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, team.getId(), second.getId(), "BENCH"));

        values.save(PlayerValue.create(first.getId(), 100, "market-a", LocalDate.of(2026, 9, 1)));
        values.save(PlayerValue.create(second.getId(), 90, "market-a", LocalDate.of(2026, 8, 30)));
        values.save(PlayerValue.create(first.getId(), 80, "market-b", LocalDate.of(2026, 8, 31)));
        values.save(PlayerValue.create(outside.getId(), 70, "outside-only", LocalDate.of(2026, 9, 1)));

        var report = new LeagueValueCoverageAnalyzer(database).analyze(league.getId());

        assertEquals(3, report.sources().size());
        var marketA = report.sources().get(0);
        assertEquals("market-a", marketA.source());
        assertEquals(2, marketA.valuedPlayers());
        assertEquals(0, marketA.missingValues());
        assertEquals(0, marketA.uncoveredTeams());
        assertEquals(100.0, marketA.coveragePercent());
        assertEquals(LeagueValueCoverageAnalyzer.RankingReadiness.READY, marketA.readiness());
        assertTrue(marketA.rankable());
        assertEquals(LocalDate.of(2026, 8, 30), marketA.oldestValueDate());
        assertEquals(LocalDate.of(2026, 9, 1), marketA.latestValueDate());

        var marketB = report.sources().get(1);
        assertEquals("market-b", marketB.source());
        assertEquals(1, marketB.valuedPlayers());
        assertEquals(1, marketB.missingValues());
        assertEquals(0, marketB.uncoveredTeams());
        assertEquals(50.0, marketB.coveragePercent());
        assertEquals(LeagueValueCoverageAnalyzer.RankingReadiness.PARTIAL, marketB.readiness());
        assertTrue(marketB.rankable());

        var outsideOnly = report.sources().get(2);
        assertEquals("outside-only", outsideOnly.source());
        assertEquals(0, outsideOnly.valuedPlayers());
        assertEquals(2, outsideOnly.missingValues());
        assertEquals(1, outsideOnly.uncoveredTeams());
        assertEquals(0.0, outsideOnly.coveragePercent());
        assertEquals(LeagueValueCoverageAnalyzer.RankingReadiness.UNAVAILABLE, outsideOnly.readiness());
        assertFalse(outsideOnly.rankable());
        assertNull(outsideOnly.oldestValueDate());
        assertNull(outsideOnly.latestValueDate());
    }

    @Test
    void sourceIsBlockedWhenAnyRosteredTeamHasZeroCoverage() throws Exception {
        Database database = database();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        PlayerRepository players = new PlayerRepository(database);
        RosterRepository rosters = new RosterRepository(database);
        PlayerValueRepository values = new PlayerValueRepository(database);

        League league = new League(UUID.randomUUID().toString(), "league-ext", "League");
        Team covered = new Team(UUID.randomUUID().toString(), "t1", league.getId(), "Covered");
        Team uncovered = new Team(UUID.randomUUID().toString(), "t2", league.getId(), "Uncovered");
        Player first = new Player(UUID.randomUUID().toString(), "p1", "First", "QB", "BUF");
        Player second = new Player(UUID.randomUUID().toString(), "p2", "Second", "WR", "MIN");
        leagues.save(league);
        teams.save(covered);
        teams.save(uncovered);
        players.save(first);
        players.save(second);
        rosters.save(new Roster(UUID.randomUUID().toString(), null, covered.getId(), first.getId(), "STARTER"));
        rosters.save(new Roster(UUID.randomUUID().toString(), null, uncovered.getId(), second.getId(), "STARTER"));
        values.save(PlayerValue.create(first.getId(), 100, "market", LocalDate.of(2026, 9, 1)));

        var source = new LeagueValueCoverageAnalyzer(database).analyze(league.getId()).sources().getFirst();

        assertEquals(1, source.valuedPlayers());
        assertEquals(1, source.missingValues());
        assertEquals(1, source.uncoveredTeams());
        assertEquals(LeagueValueCoverageAnalyzer.RankingReadiness.BLOCKED, source.readiness());
        assertFalse(source.rankable());
    }

    @Test
    void blankLeagueIdIsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> new LeagueValueCoverageAnalyzer(database()).analyze("   "));
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("coverage.db"));
        database.initialize();
        return database;
    }
}
