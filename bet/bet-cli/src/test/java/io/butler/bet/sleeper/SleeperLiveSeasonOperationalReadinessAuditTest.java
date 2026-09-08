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

class SleeperLiveSeasonOperationalReadinessAuditTest {
    @TempDir Path tempDir;

    @Test
    void reportsReadyCurrentRosterLineupAndTradeWhenExactLiveEvidenceAligns() throws Exception {
        Database database = initialized();
        new TeamRepository(database).save(new Team("t1", "1", "l1", "One"));
        new TeamRepository(database).save(new Team("t2", "2", "l1", "Two"));
        new PlayerRepository(database).save(new Player("p-int-1", "p1", "Player One", "QB", "NE"));
        new PlayerRepository(database).save(new Player("p-int-2", "p2", "Player Two", "RB", "SEA"));

        Instant observedAt = Instant.parse("2026-09-07T22:45:00Z");
        var audit = new SleeperLiveSeasonOperationalReadinessAudit(
            database,
            source(
                leagueJson(2),
                """
                    [
                      {"roster_id":1,"owner_id":"u1","players":["p1"],"starters":["p1"]},
                      {"roster_id":2,"owner_id":"u2","players":["p2"],"starters":["p2"]}
                    ]
                    """,
                "[{\"user_id\":\"u1\"},{\"user_id\":\"u2\"}]"),
            Clock.fixed(observedAt, ZoneOffset.UTC));

        var report = audit.audit("l1");

        assertEquals(2026, report.providerSeason());
        assertEquals("in_season", report.providerStatus());
        assertEquals(1, report.providerLeg());
        assertEquals(2, report.providerRosterCount());
        assertEquals(2, report.persistedTeamCount());
        assertEquals(2, report.distinctCurrentPlayerIds());
        assertEquals(2, report.exactMappedCurrentPlayerIds());
        assertEquals(0, report.unmappedCurrentPlayerIds());
        assertEquals(SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.READY,
            report.currentRosterContext().state());
        assertEquals(SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.READY,
            report.lineupContextPrerequisites().state());
        assertEquals(SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.READY,
            report.tradeContextPrerequisites().state());
        assertEquals(SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.NOT_YET_AUDITED,
            report.waiverFreeAgentInventoryPrerequisites().state());
        assertEquals(observedAt, report.observedAtUtc());
    }

    @Test
    void blocksStaleTeamMappingUnmappedPlayerAndMissingOwnerEvidence() throws Exception {
        Database database = initialized();
        new TeamRepository(database).save(new Team("t1", "1", "l1", "One"));
        new PlayerRepository(database).save(new Player("p-int-1", "p1", "Player One", "QB", "NE"));

        var audit = new SleeperLiveSeasonOperationalReadinessAudit(
            database,
            source(
                leagueJson(3),
                """
                    [
                      {"roster_id":1,"owner_id":null,"players":["p1"],"starters":["p1"]},
                      {"roster_id":2,"owner_id":"u-missing","players":["p3"]}
                    ]
                    """,
                "[{\"user_id\":\"u1\"}]"),
            Clock.fixed(Instant.parse("2026-09-07T22:45:00Z"), ZoneOffset.UTC));

        var report = audit.audit("l1");

        assertEquals(1, report.unmappedCurrentPlayerIds());
        assertEquals(java.util.List.of("p3"), report.unmappedPlayerExamples());
        assertEquals(java.util.List.of("2"), report.providerRosterIdsMissingPersistedTeam());
        assertEquals(1, report.ownerlessRosters());
        assertEquals(java.util.List.of("u-missing"), report.unknownOwnerIds());
        assertEquals(SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.BLOCKED,
            report.currentRosterContext().state());
        assertEquals(SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.BLOCKED,
            report.lineupContextPrerequisites().state());
        assertEquals(SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.BLOCKED,
            report.tradeContextPrerequisites().state());
        assertTrue(report.currentRosterContext().blockers().stream()
            .anyMatch(value -> value.contains("declared roster count 3")));
        assertTrue(report.currentRosterContext().blockers().stream()
            .anyMatch(value -> value.contains("unmapped") || value.contains("no exact Butler mapping")));
        assertTrue(report.lineupContextPrerequisites().blockers().stream()
            .anyMatch(value -> value.contains("no owner_id")));
        assertTrue(report.lineupContextPrerequisites().blockers().stream()
            .anyMatch(value -> value.contains("no starters field")));
    }

    @Test
    void blocksDraftingLeagueWithEmptyRosterAndStarterIdentities() throws Exception {
        Database database = initialized();
        new TeamRepository(database).save(new Team("t1", "1", "l1", "One"));
        new TeamRepository(database).save(new Team("t2", "2", "l1", "Two"));

        var audit = new SleeperLiveSeasonOperationalReadinessAudit(
            database,
            source(
                leagueJson(2, "drafting"),
                """
                    [
                      {"roster_id":1,"owner_id":"u1","players":[],"starters":[]},
                      {"roster_id":2,"owner_id":"u2","players":[],"starters":[]}
                    ]
                    """,
                "[{\"user_id\":\"u1\"},{\"user_id\":\"u2\"}]"),
            Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC));

        var report = audit.audit("l1");

        assertEquals(SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.BLOCKED,
            report.currentRosterContext().state());
        assertEquals(SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.BLOCKED,
            report.lineupContextPrerequisites().state());
        assertEquals(SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.BLOCKED,
            report.tradeContextPrerequisites().state());
        assertTrue(report.currentRosterContext().blockers().stream()
            .anyMatch(value -> value.contains("status is drafting")));
        assertTrue(report.currentRosterContext().blockers().stream()
            .anyMatch(value -> value.contains("2 provider roster(s) contain no current player identities")));
        assertTrue(report.lineupContextPrerequisites().blockers().stream()
            .anyMatch(value -> value.contains("2 provider roster(s) contain no starter identities")));
    }

    @Test
    void blocksRosterAndTradeWhenAnyInSeasonRosterHasNoPlayers() throws Exception {
        Database database = initialized();
        new TeamRepository(database).save(new Team("t1", "1", "l1", "One"));
        new TeamRepository(database).save(new Team("t2", "2", "l1", "Two"));
        new PlayerRepository(database).save(new Player("p-int-1", "p1", "Player One", "QB", "NE"));

        var audit = new SleeperLiveSeasonOperationalReadinessAudit(
            database,
            source(
                leagueJson(2),
                """
                    [
                      {"roster_id":1,"owner_id":"u1","players":["p1"],"starters":["p1"]},
                      {"roster_id":2,"owner_id":"u2","players":[],"starters":[]}
                    ]
                    """,
                "[{\"user_id\":\"u1\"},{\"user_id\":\"u2\"}]"),
            Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC));

        var report = audit.audit("l1");

        assertEquals(SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.BLOCKED,
            report.currentRosterContext().state());
        assertEquals(SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.BLOCKED,
            report.tradeContextPrerequisites().state());
        assertTrue(report.currentRosterContext().blockers().stream()
            .anyMatch(value -> value.contains("1 provider roster(s) contain no current player identities")));
    }

    @Test
    void blocksOnlyLineupWhenPlayersExistButAStarterListIsEmpty() throws Exception {
        Database database = initialized();
        new TeamRepository(database).save(new Team("t1", "1", "l1", "One"));
        new TeamRepository(database).save(new Team("t2", "2", "l1", "Two"));
        new PlayerRepository(database).save(new Player("p-int-1", "p1", "Player One", "QB", "NE"));
        new PlayerRepository(database).save(new Player("p-int-2", "p2", "Player Two", "RB", "SEA"));

        var audit = new SleeperLiveSeasonOperationalReadinessAudit(
            database,
            source(
                leagueJson(2),
                """
                    [
                      {"roster_id":1,"owner_id":"u1","players":["p1"],"starters":["p1"]},
                      {"roster_id":2,"owner_id":"u2","players":["p2"],"starters":[]}
                    ]
                    """,
                "[{\"user_id\":\"u1\"},{\"user_id\":\"u2\"}]"),
            Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC));

        var report = audit.audit("l1");

        assertEquals(SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.READY,
            report.currentRosterContext().state());
        assertEquals(SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.BLOCKED,
            report.lineupContextPrerequisites().state());
        assertEquals(SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.READY,
            report.tradeContextPrerequisites().state());
        assertTrue(report.lineupContextPrerequisites().blockers().stream()
            .anyMatch(value -> value.contains("1 provider roster(s) contain no starter identities")));
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("bf595.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "provider-1", "League", 2026));
        return database;
    }

    private static String leagueJson(int totalRosters) {
        return leagueJson(totalRosters, "in_season");
    }

    private static String leagueJson(int totalRosters, String status) {
        return """
            {
              "league_id":"provider-1",
              "name":"League",
              "season":"2026",
              "status":"%s",
              "total_rosters":%d,
              "settings":{"leg":1},
              "roster_positions":["QB","RB","WR","BN"],
              "scoring_settings":{"pass_yd":0.04,"rec":1.0}
            }
            """.formatted(status, totalRosters);
    }

    private static SleeperLiveSeasonOperationalReadinessAudit.Source source(
        String league,
        String rosters,
        String users) {
        return new SleeperLiveSeasonOperationalReadinessAudit.Source() {
            @Override public String league(String sleeperLeagueId) { return league; }
            @Override public String rosters(String sleeperLeagueId) { return rosters; }
            @Override public String users(String sleeperLeagueId) { return users; }
        };
    }
}
