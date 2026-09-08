package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverUniverseAuditTest {
    @TempDir Path tempDir;

    @Test
    void provesExactActiveMinusRosteredUniverseAndFetchesEachFrameSurfaceOnce() throws Exception {
        Database database = initialized();
        FixtureSource source = new FixtureSource();
        source.activePlayers = """
            {
              "p1":{"full_name":"Roster One","position":"QB","team":"CHI","status":"Active","fantasy_positions":["QB"]},
              "p2":{"full_name":"Roster Two","position":"RB","team":"DET","status":"Active","fantasy_positions":["RB"]},
              "p3":{"full_name":"Free Three","position":"WR","team":"MIN","status":"Active","fantasy_positions":["WR"]},
              "p4":{"full_name":"Free Four","position":"TE","team":null,"status":"Active","fantasy_positions":["TE"]}
            }
            """;

        var report = audit(database, source).audit("l1");

        assertEquals(SleeperLiveWaiverUniverseAudit.AuditState.READY, report.state());
        assertEquals(2, report.currentRosterPlayerIdentities());
        assertEquals(4, report.activePlayerIdentities());
        assertEquals(2, report.activeRosteredPlayerIdentities());
        assertEquals(2, report.freeAgentIdentities());
        assertEquals(0, report.exactButlerMappedFreeAgents());
        assertEquals(2, report.unmappedFreeAgents());
        assertEquals(java.util.List.of(), report.rosteredPlayerIdsAbsentFromActiveSource());
        assertEquals(2, report.freeAgentsWithPosition());
        assertEquals(2, report.freeAgentsWithFantasyPositions());
        assertEquals(1, report.freeAgentsWithTeam());
        assertEquals(2, report.freeAgentsWithStatus());
        assertEquals(java.util.List.of("p3", "p4"),
            report.freeAgentExamples().stream().map(SleeperLiveWaiverUniverseAudit.PlayerExample::playerId).toList());
        assertEquals(1, source.leagueCalls);
        assertEquals(1, source.rosterCalls);
        assertEquals(1, source.userCalls);
        assertEquals(1, source.activeCalls);
    }

    @Test
    void emptyActiveSourceBlocks() throws Exception {
        Database database = initialized();
        FixtureSource source = new FixtureSource();
        source.activePlayers = "{}";

        var report = audit(database, source).audit("l1");

        assertEquals(SleeperLiveWaiverUniverseAudit.AuditState.BLOCKED, report.state());
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("returned no identities")));
    }

    @Test
    void staleLiveReadinessBlocksBeforeWaiverUniverseCanBeTrusted() throws Exception {
        Database database = initialized();
        FixtureSource source = new FixtureSource();
        source.league = leagueJson("drafting");

        var report = audit(database, source).audit("l1");

        assertEquals(SleeperLiveWaiverUniverseAudit.AuditState.BLOCKED, report.state());
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("BF-598 current-roster")));
    }

    @Test
    void rosteredIdentityAbsentFromActiveSourceIsReportedButNotMisclassifiedAsFreeAgent() throws Exception {
        Database database = initialized();
        FixtureSource source = new FixtureSource();
        source.activePlayers = """
            {
              "p1":{"full_name":"Roster One","position":"QB","team":"CHI","status":"Active","fantasy_positions":["QB"]},
              "p3":{"full_name":"Free Three","position":"WR","team":"MIN","status":"Active","fantasy_positions":["WR"]}
            }
            """;

        var report = audit(database, source).audit("l1");

        assertEquals(SleeperLiveWaiverUniverseAudit.AuditState.READY, report.state());
        assertEquals(java.util.List.of("p2"), report.rosteredPlayerIdsAbsentFromActiveSource());
        assertEquals(1, report.activeRosteredPlayerIdentities());
        assertEquals(1, report.freeAgentIdentities());
        assertEquals("p3", report.freeAgentExamples().getFirst().playerId());
    }

    @Test
    void duplicateCurrentPlayerAcrossProviderRostersBlocksInsteadOfSilentlyDeduplicating() throws Exception {
        Database database = initialized();
        FixtureSource source = new FixtureSource();
        source.rosters = """
            [
              {"roster_id":1,"owner_id":"u1","players":["p1"],"starters":["p1"]},
              {"roster_id":2,"owner_id":"u2","players":["p1","p2"],"starters":["p2"]}
            ]
            """;

        var report = audit(database, source).audit("l1");

        assertEquals(SleeperLiveWaiverUniverseAudit.AuditState.BLOCKED, report.state());
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("duplicate player identities")));
    }

    private SleeperLiveWaiverUniverseAudit audit(Database database, FixtureSource source) {
        return new SleeperLiveWaiverUniverseAudit(
            database,
            source,
            Clock.fixed(Instant.parse("2026-09-08T00:30:00Z"), ZoneOffset.UTC));
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("bf601.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "provider-1", "League", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "One"));
        new TeamRepository(database).save(new Team("t2", "2", "l1", "Two"));
        new PlayerRepository(database).save(new Player("i1", "p1", "Roster One", "QB", "CHI"));
        new PlayerRepository(database).save(new Player("i2", "p2", "Roster Two", "RB", "DET"));
        return database;
    }

    private static String leagueJson(String status) {
        return """
            {
              "league_id":"provider-1",
              "name":"League",
              "season":"2026",
              "status":"%s",
              "total_rosters":2,
              "settings":{"leg":1},
              "roster_positions":["QB","RB","WR","TE","BN"],
              "scoring_settings":{"pass_yd":0.04,"rec":1.0}
            }
            """.formatted(status);
    }

    private static final class FixtureSource implements SleeperLiveWaiverUniverseAudit.FrameSource {
        String league = leagueJson("in_season");
        String rosters = """
            [
              {"roster_id":1,"owner_id":"u1","players":["p1"],"starters":["p1"]},
              {"roster_id":2,"owner_id":"u2","players":["p2"],"starters":["p2"]}
            ]
            """;
        String users = "[{\"user_id\":\"u1\"},{\"user_id\":\"u2\"}]";
        String activePlayers = """
            {
              "p1":{"full_name":"Roster One","position":"QB","team":"CHI","status":"Active","fantasy_positions":["QB"]},
              "p2":{"full_name":"Roster Two","position":"RB","team":"DET","status":"Active","fantasy_positions":["RB"]},
              "p3":{"full_name":"Free Three","position":"WR","team":"MIN","status":"Active","fantasy_positions":["WR"]}
            }
            """;
        int leagueCalls;
        int rosterCalls;
        int userCalls;
        int activeCalls;

        @Override public String league(String sleeperLeagueId) { leagueCalls++; return league; }
        @Override public String rosters(String sleeperLeagueId) { rosterCalls++; return rosters; }
        @Override public String users(String sleeperLeagueId) { userCalls++; return users; }
        @Override public String activePlayers() { activeCalls++; return activePlayers; }
    }
}
