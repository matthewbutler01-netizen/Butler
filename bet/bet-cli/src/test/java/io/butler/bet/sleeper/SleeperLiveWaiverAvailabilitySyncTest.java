package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LiveWaiverAvailabilityRepository;
import io.butler.bet.data.LiveWaiverMarketAttentionRepository;
import io.butler.bet.data.LiveWaiverSnapshotRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverAvailabilitySyncTest {
    @TempDir Path tempDir;

    @Test
    void persistsExactMarketActiveFrameAndPreservesNullAndAbsentSourceEvidence() throws Exception {
        Database database = seededDatabase();
        FixtureSource source = new FixtureSource(false, false);
        var sync = new SleeperLiveWaiverAvailabilitySync(database, source,
            Clock.fixed(Instant.parse("2026-09-08T02:00:00Z"), ZoneOffset.UTC));

        var report = sync.sync("L");

        assertEquals(3, report.candidateCount());
        assertEquals(2, report.sourcePresentCount());
        assertEquals(1, report.sourceAbsentCount());
        assertEquals(2, report.withTeamCount());
        assertEquals(2, report.withStatusCount());
        assertEquals(1, report.withInjuryStatusCount());
        assertEquals(1, report.withPracticeParticipationCount());
        assertEquals(1, report.withDepthChartPositionCount());
        assertEquals(1, report.withDepthChartOrderCount());
        assertEquals(1, source.activeCalls);

        var entries = new LiveWaiverAvailabilityRepository(database).entries(report.availabilitySnapshotId());
        assertEquals(List.of("p3", "p4", "p5"), entries.stream().map(LiveWaiverAvailabilityRepository.Entry::sleeperPlayerId).toList());
        var p3 = entries.get(0);
        assertEquals("Questionable", p3.injuryStatus());
        assertEquals("Limited", p3.practiceParticipation());
        assertEquals("2", p3.depthChartPosition());
        assertEquals(2, p3.depthChartOrder());

        var p4 = entries.get(1);
        assertEquals("SOURCE_PRESENT", p4.sourceState());
        assertNull(p4.injuryStatus());
        assertNull(p4.practiceParticipation());
        assertNull(p4.depthChartPosition());
        assertNull(p4.depthChartOrder());

        var p5 = entries.get(2);
        assertEquals("SOURCE_ABSENT", p5.sourceState());
        assertNull(p5.currentTeam());
        assertNull(p5.currentStatus());
        assertNull(p5.injuryStatus());

        var counts = new LiveWaiverAvailabilityRepository(database).counts(report.availabilitySnapshotId());
        assertEquals(3, counts.total());
        assertEquals(2, counts.sourcePresent());
        assertEquals(1, counts.sourceAbsent());
    }

    @Test
    void newlyRosteredMarketCandidateBlocksBeforeDailyPlayerMapFetch() throws Exception {
        Database database = seededDatabase();
        FixtureSource source = new FixtureSource(true, false);
        var sync = new SleeperLiveWaiverAvailabilitySync(database, source,
            Clock.fixed(Instant.parse("2026-09-08T02:00:00Z"), ZoneOffset.UTC));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> sync.sync("L"));
        assertTrue(error.getMessage().contains("now rostered"));
        assertEquals(1, source.leagueCalls);
        assertEquals(1, source.rosterCalls);
        assertEquals(0, source.activeCalls);
        assertEquals(0, new LiveWaiverAvailabilityRepository(database).snapshotCountForLeague("L"));
    }

    @Test
    void secondSameFrameSyncInsideTwentyFourHoursBlocksBeforeAnyProviderCalls() throws Exception {
        Database database = seededDatabase();
        FixtureSource first = new FixtureSource(false, false);
        new SleeperLiveWaiverAvailabilitySync(database, first,
            Clock.fixed(Instant.parse("2026-09-08T02:00:00Z"), ZoneOffset.UTC)).sync("L");
        assertEquals(1, first.activeCalls);

        FixtureSource second = new FixtureSource(false, false);
        var secondSync = new SleeperLiveWaiverAvailabilitySync(database, second,
            Clock.fixed(Instant.parse("2026-09-08T03:00:00Z"), ZoneOffset.UTC));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> secondSync.sync("L"));

        assertTrue(error.getMessage().contains("24 hours"));
        assertEquals(0, second.leagueCalls);
        assertEquals(0, second.rosterCalls);
        assertEquals(0, second.activeCalls);
        assertEquals(1, new LiveWaiverAvailabilityRepository(database).snapshotCountForLeague("L"));
    }

    @Test
    void duplicateActivePlayerJsonKeyFailsClosedWithoutPersistence() throws Exception {
        Database database = seededDatabase();
        FixtureSource source = new FixtureSource(false, true);
        var sync = new SleeperLiveWaiverAvailabilitySync(database, source,
            Clock.fixed(Instant.parse("2026-09-08T02:00:00Z"), ZoneOffset.UTC));

        assertThrows(Exception.class, () -> sync.sync("L"));
        assertEquals(1, source.activeCalls);
        assertEquals(0, new LiveWaiverAvailabilityRepository(database).snapshotCountForLeague("L"));
    }

    private Database seededDatabase() throws Exception {
        Database database = new Database(tempDir.resolve("butler-test.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("L", "S", "Test League", 2026));

        new LiveWaiverSnapshotRepository(database).save(
            new LiveWaiverSnapshotRepository.Snapshot(
                "W", "L", "S", 2026, "in_season", 1, SleeperLiveWaiverUniverseAudit.ACTIVE_PLAYER_SOURCE,
                SleeperLiveWaiverUniverseAudit.POLICY_ID, SleeperLiveWaiverSnapshotSync.ELIGIBILITY_POLICY_ID,
                Instant.parse("2026-09-08T00:30:00Z"), 2, 6, 2, 0, 4, 4),
            List.of(
                waiverEntry("p1", "Roster QB", "QB", true, false),
                waiverEntry("p2", "Roster RB", "RB", true, false),
                waiverEntry("p3", "Add Only", "WR", false, true),
                waiverEntry("p4", "Both", "TE", false, true),
                waiverEntry("p5", "Drop Only", "RB", false, true),
                waiverEntry("p6", "Neither", "QB", false, true)));

        new LiveWaiverMarketAttentionRepository(database).save(
            new LiveWaiverMarketAttentionRepository.Snapshot(
                "M", "L", "W", "S", 2026, "in_season", 1,
                SleeperLiveWaiverMarketAttentionSync.POLICY_ID, 24, 200,
                Instant.parse("2026-09-08T01:00:00Z"), 4, 2, 2, 1, 1, 1, 1),
            List.of(
                marketEntry("p3", "Add Only", "WR", 10, 0, "ADD_ONLY"),
                marketEntry("p4", "Both", "TE", 4, 2, "BOTH"),
                marketEntry("p5", "Drop Only", "RB", 0, 7, "DROP_ONLY"),
                marketEntry("p6", "Neither", "QB", 0, 0, "NEITHER")));

        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("b3", "p3", "Add Only", "WR", "CHI"));
        players.save(new Player("b4", "p4", "Both", "TE", "KC"));
        players.save(new Player("b5", "p5", "Drop Only", "RB", "GB"));
        return database;
    }

    private static LiveWaiverSnapshotRepository.Entry waiverEntry(
        String id, String name, String position, boolean rostered, boolean eligible) {
        return new LiveWaiverSnapshotRepository.Entry(
            id, name, position, List.of(position), "CHI", "Active", rostered, !rostered, eligible,
            rostered ? "ROSTERED" : "LEAGUE_ELIGIBLE_POSITION_MATCH");
    }

    private static LiveWaiverMarketAttentionRepository.Entry marketEntry(
        String id, String name, String position, int adds, int drops, String membership) {
        return new LiveWaiverMarketAttentionRepository.Entry(
            id, name, position, "CHI", "Active", adds, drops, adds - drops,
            adds > 0, drops > 0, membership);
    }

    private static final class FixtureSource implements SleeperLiveWaiverAvailabilitySync.Source {
        private final boolean targetRostered;
        private final boolean duplicateActiveKey;
        int leagueCalls;
        int rosterCalls;
        int activeCalls;

        FixtureSource(boolean targetRostered, boolean duplicateActiveKey) {
            this.targetRostered = targetRostered;
            this.duplicateActiveKey = duplicateActiveKey;
        }

        @Override public String league(String sleeperLeagueId) {
            leagueCalls++;
            return "{\"league_id\":\"S\",\"season\":\"2026\",\"status\":\"in_season\",\"settings\":{\"leg\":1}}";
        }

        @Override public String rosters(String sleeperLeagueId) {
            rosterCalls++;
            return targetRostered
                ? "[{\"roster_id\":1,\"players\":[\"p1\",\"p3\"]},{\"roster_id\":2,\"players\":[\"p2\"]}]"
                : "[{\"roster_id\":1,\"players\":[\"p1\"]},{\"roster_id\":2,\"players\":[\"p2\"]}]";
        }

        @Override public String activePlayers() {
            activeCalls++;
            if (duplicateActiveKey) return "{\"p3\":{},\"p3\":{}}";
            return """
                {
                  "p3": {
                    "team":"CHI",
                    "status":"Active",
                    "injury_status":"Questionable",
                    "injury_start_date":"2026-09-06",
                    "practice_participation":"Limited",
                    "depth_chart_position":2,
                    "depth_chart_order":2
                  },
                  "p4": {
                    "team":"KC",
                    "status":"Active",
                    "injury_status":null,
                    "injury_start_date":null,
                    "practice_participation":null,
                    "depth_chart_position":null,
                    "depth_chart_order":null
                  }
                }
                """;
        }
    }
}
