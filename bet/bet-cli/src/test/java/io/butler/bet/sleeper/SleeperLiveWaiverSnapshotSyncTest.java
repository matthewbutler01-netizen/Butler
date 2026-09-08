package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueLineupConfigurationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LiveWaiverSnapshotRepository;
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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverSnapshotSyncTest {
    @TempDir Path tempDir;

    @Test
    void readyFramePersistsCompleteRawSnapshotAndLeagueEligibleProjectionWithoutCanonicalPlayerPollution() throws Exception {
        Database database = seededDatabase();
        var source = new FixtureSource("in_season");
        var sync = new SleeperLiveWaiverSnapshotSync(database, source,
            Clock.fixed(Instant.parse("2026-09-08T00:30:00Z"), ZoneOffset.UTC));

        var report = sync.sync("L");

        assertEquals(5, report.persistedActiveEntries());
        assertEquals(2, report.currentRosterIdentities());
        assertEquals(2, report.activeRosteredEntries());
        assertEquals(3, report.freeAgentEntries());
        assertEquals(1, report.leagueEligibleFreeAgents());
        assertEquals(2, report.canonicalPlayersBefore());
        assertEquals(2, report.canonicalPlayersAfter());
        assertEquals(1, report.snapshotCountForLeague());
        assertEquals(2, report.eligibilityReasons().get("ROSTERED"));
        assertEquals(1, report.eligibilityReasons().get("LEAGUE_ELIGIBLE_POSITION_MATCH"));
        assertEquals(1, report.eligibilityReasons().get("NO_LEAGUE_ELIGIBLE_POSITION_MATCH"));
        assertEquals(1, report.eligibilityReasons().get("NO_SUPPORTED_FANTASY_POSITION"));
        assertTrue(report.eligibleExamples().getFirst().contains("p3 | Free Receiver"));

        var counts = new LiveWaiverSnapshotRepository(database).counts(report.snapshotId());
        assertEquals(5, counts.total());
        assertEquals(2, counts.rostered());
        assertEquals(3, counts.freeAgents());
        assertEquals(1, counts.eligibleFreeAgents());
    }

    @Test
    void blockedBf601FramePersistsNothing() throws Exception {
        Database database = seededDatabase();
        var sync = new SleeperLiveWaiverSnapshotSync(database, new FixtureSource("drafting"),
            Clock.fixed(Instant.parse("2026-09-08T00:30:00Z"), ZoneOffset.UTC));

        assertThrows(IllegalStateException.class, () -> sync.sync("L"));
        assertEquals(0, new LiveWaiverSnapshotRepository(database).snapshotCountForLeague("L"));
    }

    @Test
    void repeatedReadyRunsRetainImmutableObservations() throws Exception {
        Database database = seededDatabase();
        var source = new FixtureSource("in_season");
        var sync = new SleeperLiveWaiverSnapshotSync(database, source,
            Clock.fixed(Instant.parse("2026-09-08T00:30:00Z"), ZoneOffset.UTC));

        var first = sync.sync("L");
        var second = sync.sync("L");

        assertTrue(!first.snapshotId().equals(second.snapshotId()));
        assertEquals(2, second.snapshotCountForLeague());
    }

    @Test
    void eligibilitySlotExpansionIsVersionedAndDeterministic() {
        Set<String> positions = SleeperLiveWaiverSnapshotSync.eligiblePositions(List.of(
            "QB", "FLEX", "SUPER_FLEX", "REC_FLEX", "WRRB_FLEX", "IDP_FLEX", "BN", "IR", "TAXI"));
        assertEquals(Set.of("QB", "RB", "WR", "TE", "DB", "DL", "LB"), positions);
    }

    private Database seededDatabase() throws Exception {
        Database database = new Database(tempDir.resolve("butler-test.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("L", "S", "Test League", 2026));
        new TeamRepository(database).save(new Team("T1", "1", "L", "One"));
        new TeamRepository(database).save(new Team("T2", "2", "L", "Two"));
        new PlayerRepository(database).save(new Player("P1", "p1", "Roster QB", "QB", "CHI"));
        new PlayerRepository(database).save(new Player("P2", "p2", "Roster RB", "RB", "DET"));
        new LeagueLineupConfigurationRepository(database).replace("L",
            List.of("QB", "RB", "WR", "TE", "FLEX", "SUPER_FLEX", "BN"));
        return database;
    }

    private static final class FixtureSource implements SleeperLiveWaiverSnapshotSync.FrameSource {
        private final String status;
        FixtureSource(String status) { this.status = status; }

        @Override public String league(String id) {
            return """
                {"league_id":"S","name":"Test League","season":"2026","status":"%s","total_rosters":2,
                 "settings":{"leg":1},
                 "roster_positions":["QB","RB","WR","TE","FLEX","SUPER_FLEX","BN"],
                 "scoring_settings":{"pass_yd":0.04,"rec":1.0}}
                """.formatted(status);
        }

        @Override public String users(String id) {
            return "[{\"user_id\":\"u1\"},{\"user_id\":\"u2\"}]";
        }

        @Override public String rosters(String id) {
            return """
                [
                  {"roster_id":1,"owner_id":"u1","players":["p1"],"starters":["p1"]},
                  {"roster_id":2,"owner_id":"u2","players":["p2"],"starters":["p2"]}
                ]
                """;
        }

        @Override public String activePlayers() {
            return """
                {
                  "p1":{"full_name":"Roster QB","position":"QB","fantasy_positions":["QB"],"team":"CHI","status":"Active"},
                  "p2":{"full_name":"Roster RB","position":"RB","fantasy_positions":["RB"],"team":"DET","status":"Active"},
                  "p3":{"full_name":"Free Receiver","position":"WR","fantasy_positions":["WR"],"team":"MIN","status":"Active"},
                  "p4":{"full_name":"IDP Only","position":"LB","fantasy_positions":["LB"],"team":"GB","status":"Active"},
                  "p5":{"full_name":"Unknown Position","fantasy_positions":[],"team":null,"status":"Active"}
                }
                """;
        }
    }
}
