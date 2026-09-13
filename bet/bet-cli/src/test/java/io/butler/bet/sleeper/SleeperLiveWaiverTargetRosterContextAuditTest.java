package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueLineupConfigurationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverTargetRosterContextAuditTest {
    @TempDir Path tempDir;

    @Test
    void resolvesExactOwnerAndPartitionsLiveTargetRosterWithoutRanking() throws Exception {
        Database database = seededDatabase();
        var audit = audit(database, standardRosters(), standardUsers(), rosteredIds());

        var report = audit.audit("L", "owner-1");

        assertEquals("M", report.marketSnapshotId());
        assertEquals("W", report.waiverSnapshotId());
        assertEquals(51, report.candidateCount());
        assertEquals(42, report.reviewableCandidateCount());
        assertEquals(1, report.rosterId());
        assertEquals("T1", report.butlerTeamId());
        assertEquals(7, report.targetPlayerCount());
        assertEquals(4, report.starterCount());
        assertEquals(1, report.benchCount());
        assertEquals(1, report.reserveCount());
        assertEquals(1, report.taxiCount());
        assertEquals(6, report.exactMappedTargetPlayers());
        assertEquals(1, report.unmappedTargetPlayers());
        assertEquals(List.of("QB", "RB", "WR", "FLEX"), report.startingSlots());
        assertEquals("QB", report.targetPlayers().get(0).lineupSlot());
        assertEquals("UNMAPPED_CANONICAL", report.targetPlayers().get(6).mappingState());
        assertEquals("p7", report.targetPlayers().get(6).sleeperPlayerId());
    }

    @Test
    void currentRosterMembershipDriftFailsClosed() throws Exception {
        Database database = seededDatabase();
        Set<String> stale = Set.of("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8");
        var audit = audit(database, standardRosters(), standardUsers(), stale);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> audit.audit("L", "owner-1"));
        assertTrue(error.getMessage().contains("roster membership drifted"));
        assertTrue(error.getMessage().contains("p9"));
    }

    @Test
    void exactOwnerResolvingToMultipleRostersFailsClosed() throws Exception {
        Database database = seededDatabase();
        String rosters = standardRosters().replace("\"owner_id\":\"owner-2\"", "\"owner_id\":\"owner-1\"");
        var audit = audit(database, rosters, standardUsers(), rosteredIds());

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> audit.audit("L", "owner-1"));
        assertTrue(error.getMessage().contains("resolves to 2 current rosters"));
    }

    @Test
    void missingExactOwnerUserFailsClosed() throws Exception {
        Database database = seededDatabase();
        String users = "[{\"user_id\":\"owner-2\",\"display_name\":\"Other\"}]";
        var audit = audit(database, standardRosters(), users, rosteredIds());

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> audit.audit("L", "owner-1"));
        assertTrue(error.getMessage().contains("absent from current provider users"));
    }

    @Test
    void duplicatePlayerAcrossCurrentRostersFailsClosed() throws Exception {
        Database database = seededDatabase();
        String rosters = standardRosters().replace("[\"p8\",\"p9\"]", "[\"p5\",\"p9\"]");
        Set<String> frame = Set.of("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p9");
        var audit = audit(database, rosters, standardUsers(), frame);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> audit.audit("L", "owner-1"));
        assertTrue(error.getMessage().contains("appears on multiple current rosters"));
    }

    private Database seededDatabase() throws Exception {
        Database database = new Database(tempDir.resolve("butler-test.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("L", "S", "League", 2026));
        new TeamRepository(database).save(new Team("T1", "1", "L", "Target Team"));
        new LeagueLineupConfigurationRepository(database).replace(
            "L", List.of("QB", "RB", "WR", "FLEX", "BN", "BN"));
        PlayerRepository players = new PlayerRepository(database);
        for (int i = 1; i <= 6; i++) {
            players.save(new Player("B" + i, "p" + i, "Player " + i,
                i == 1 ? "QB" : i == 2 || i == 5 || i == 6 ? "RB" : "WR", "TM"));
        }
        return database;
    }

    private static SleeperLiveWaiverTargetRosterContextAudit audit(
        Database database, String rosters, String users, Set<String> rosteredIds) {
        var readiness = new SleeperLiveWaiverTargetRosterContextAudit.ReadinessFrame("M", 51, 42);
        var frame = new SleeperLiveWaiverTargetRosterContextAudit.MarketRosterFrame(
            "M", "W", "S", 2026, "in_season", rosteredIds);
        return new SleeperLiveWaiverTargetRosterContextAudit(
            database,
            ignored -> readiness,
            (leagueId, marketId) -> frame,
            new FakeSource(leagueJson(), rosters, users));
    }

    private static Set<String> rosteredIds() {
        return Set.of("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9");
    }

    private static String leagueJson() {
        return """
            {"league_id":"S","season":"2026","status":"in_season","total_rosters":2,
             "settings":{"leg":1},
             "roster_positions":["QB","RB","WR","FLEX","BN","BN"]}
            """;
    }

    private static String standardUsers() {
        return """
            [
              {"user_id":"owner-1","display_name":"Target Owner","metadata":{"team_name":"Target Team"}},
              {"user_id":"owner-2","display_name":"Other Owner","metadata":{"team_name":"Other Team"}}
            ]
            """;
    }

    private static String standardRosters() {
        return """
            [
              {"roster_id":1,"owner_id":"owner-1",
               "players":["p1","p2","p3","p4","p5","p6","p7"],
               "starters":["p1","p2","p3","p4"],"reserve":["p6"],"taxi":["p7"]},
              {"roster_id":2,"owner_id":"owner-2",
               "players":["p8","p9"],"starters":["p8","p9"],"reserve":[],"taxi":[]}
            ]
            """;
    }

    private record FakeSource(String league, String rosters, String users)
        implements SleeperLiveWaiverTargetRosterContextAudit.Source {
        @Override public String league(String sleeperLeagueId) { return league; }
        @Override public String rosters(String sleeperLeagueId) { return rosters; }
        @Override public String users(String sleeperLeagueId) { return users; }
    }
}
