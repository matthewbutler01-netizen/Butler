package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LiveWaiverMarketAttentionRepository;
import io.butler.bet.data.LiveWaiverSnapshotRepository;
import io.butler.bet.domain.League;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverMarketAttentionSyncTest {
    @TempDir Path tempDir;

    @Test
    void freshRosterStableSnapshotPersistsEveryEligibleCandidateAndTrendMembership() throws Exception {
        Database database = seededDatabase(Instant.parse("2026-09-08T00:30:00Z"));
        FixtureSource source = new FixtureSource(false, false);
        var sync = new SleeperLiveWaiverMarketAttentionSync(database, source,
            Clock.fixed(Instant.parse("2026-09-08T00:45:00Z"), ZoneOffset.UTC));

        var report = sync.sync("L");

        assertEquals(4, report.candidateCount());
        assertEquals(3, report.addFrameSize());
        assertEquals(2, report.dropFrameSize());
        assertEquals(1, report.addOnlyCount());
        assertEquals(1, report.dropOnlyCount());
        assertEquals(1, report.bothCount());
        assertEquals(1, report.neitherCount());
        assertEquals(1, source.addCalls);
        assertEquals(1, source.dropCalls);
        assertEquals(1, source.leagueCalls);
        assertEquals(1, source.rosterCalls);
        assertTrue(report.topAddExamples().getFirst().contains("p3 | Add Only"));
        assertTrue(report.topDropExamples().getFirst().contains("p5 | Drop Only"));
        assertTrue(report.topNetExamples().getFirst().contains("p3 | Add Only"));

        var counts = new LiveWaiverMarketAttentionRepository(database).counts(report.marketSnapshotId());
        assertEquals(4, counts.total());
        assertEquals(1, counts.addOnly());
        assertEquals(1, counts.dropOnly());
        assertEquals(1, counts.both());
        assertEquals(1, counts.neither());
    }

    @Test
    void rosterDriftBlocksBeforeTrendingCallsAndPersistsNothing() throws Exception {
        Database database = seededDatabase(Instant.parse("2026-09-08T00:30:00Z"));
        FixtureSource source = new FixtureSource(true, false);
        var sync = new SleeperLiveWaiverMarketAttentionSync(database, source,
            Clock.fixed(Instant.parse("2026-09-08T00:45:00Z"), ZoneOffset.UTC));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> sync.sync("L"));
        assertTrue(error.getMessage().contains("roster membership drifted"));
        assertEquals(0, source.addCalls);
        assertEquals(0, source.dropCalls);
        assertEquals(0, new LiveWaiverMarketAttentionRepository(database).snapshotCountForLeague("L"));
    }

    @Test
    void staleWaiverSnapshotBlocksBeforeProviderCalls() throws Exception {
        Database database = seededDatabase(Instant.parse("2026-09-07T12:00:00Z"));
        FixtureSource source = new FixtureSource(false, false);
        var sync = new SleeperLiveWaiverMarketAttentionSync(database, source,
            Clock.fixed(Instant.parse("2026-09-08T00:45:00Z"), ZoneOffset.UTC));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> sync.sync("L"));
        assertTrue(error.getMessage().contains("snapshot is stale"));
        assertEquals(0, source.leagueCalls);
        assertEquals(0, source.rosterCalls);
        assertEquals(0, source.addCalls);
        assertEquals(0, source.dropCalls);
    }

    @Test
    void duplicateTrendIdentityFailsClosedWithoutPersistence() throws Exception {
        Database database = seededDatabase(Instant.parse("2026-09-08T00:30:00Z"));
        FixtureSource source = new FixtureSource(false, true);
        var sync = new SleeperLiveWaiverMarketAttentionSync(database, source,
            Clock.fixed(Instant.parse("2026-09-08T00:45:00Z"), ZoneOffset.UTC));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> sync.sync("L"));
        assertTrue(error.getMessage().contains("Duplicate Sleeper add trending player id"));
        assertEquals(0, new LiveWaiverMarketAttentionRepository(database).snapshotCountForLeague("L"));
    }

    private Database seededDatabase(Instant snapshotObservedAt) throws Exception {
        Database database = new Database(tempDir.resolve("butler-test.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("L", "S", "Test League", 2026));
        var repository = new LiveWaiverSnapshotRepository(database);
        repository.save(new LiveWaiverSnapshotRepository.Snapshot(
            "W", "L", "S", 2026, "in_season", 1,
            "players/nfl?active=true",
            SleeperLiveWaiverUniverseAudit.POLICY_ID,
            SleeperLiveWaiverSnapshotSync.ELIGIBILITY_POLICY_ID,
            snapshotObservedAt,
            2, 6, 2, 0, 4, 4),
            List.of(
                entry("p1", "Roster QB", "QB", true, false),
                entry("p2", "Roster RB", "RB", true, false),
                entry("p3", "Add Only", "WR", false, true),
                entry("p4", "Both", "TE", false, true),
                entry("p5", "Drop Only", "RB", false, true),
                entry("p6", "Neither", "QB", false, true)));
        return database;
    }

    private static LiveWaiverSnapshotRepository.Entry entry(
        String id, String name, String position, boolean rostered, boolean eligible) {
        return new LiveWaiverSnapshotRepository.Entry(
            id, name, position, List.of(position), "CHI", "Active",
            rostered, !rostered, eligible, rostered ? "ROSTERED" : "LEAGUE_ELIGIBLE_POSITION_MATCH");
    }

    private static final class FixtureSource implements SleeperLiveWaiverMarketAttentionSync.Source {
        private final boolean rosterDrift;
        private final boolean duplicateAdd;
        int leagueCalls;
        int rosterCalls;
        int addCalls;
        int dropCalls;

        FixtureSource(boolean rosterDrift, boolean duplicateAdd) {
            this.rosterDrift = rosterDrift;
            this.duplicateAdd = duplicateAdd;
        }

        @Override public String league(String sleeperLeagueId) {
            leagueCalls++;
            return """
                {"league_id":"S","season":"2026","status":"in_season","settings":{"leg":1}}
                """;
        }

        @Override public String rosters(String sleeperLeagueId) {
            rosterCalls++;
            return rosterDrift
                ? "[{\"roster_id\":1,\"players\":[\"p1\",\"p7\"]},{\"roster_id\":2,\"players\":[\"p2\"]}]"
                : "[{\"roster_id\":1,\"players\":[\"p1\"]},{\"roster_id\":2,\"players\":[\"p2\"]}]";
        }

        @Override public String trending(String type, int lookbackHours, int limit) {
            assertEquals(24, lookbackHours);
            assertEquals(200, limit);
            if ("add".equals(type)) {
                addCalls++;
                if (duplicateAdd) {
                    return "[{\"player_id\":\"p3\",\"count\":10},{\"player_id\":\"p3\",\"count\":9}]";
                }
                return "[{\"player_id\":\"p3\",\"count\":10},{\"player_id\":\"p4\",\"count\":4},{\"player_id\":\"x\",\"count\":99}]";
            }
            dropCalls++;
            return "[{\"player_id\":\"p5\",\"count\":7},{\"player_id\":\"p4\",\"count\":2}]";
        }
    }
}
