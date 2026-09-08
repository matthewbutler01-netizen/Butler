package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LiveWaiverAvailabilityRepository;
import io.butler.bet.data.LiveWaiverCurrentWeekStatRepository;
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

class SleeperLiveWaiverCurrentWeekStatSyncTest {
    @TempDir Path tempDir;

    @Test
    void persistsPartialCurrentWeekEvidenceWithoutZeroFillingMissingRowsOrKeys() throws Exception {
        Database database = seededDatabase();
        FixtureSource source = new FixtureSource(false, false, false);
        var sync = new SleeperLiveWaiverCurrentWeekStatSync(database, source,
            Clock.fixed(Instant.parse("2026-09-08T03:00:00Z"), ZoneOffset.UTC));

        var report = sync.sync("L");

        assertEquals(SleeperLiveWaiverCurrentWeekStatSync.OBSERVATION_STATE, report.observationState());
        assertEquals(3, report.candidateCount());
        assertEquals(2, report.sourcePresentCount());
        assertEquals(1, report.sourceAbsentCount());
        assertEquals(1, report.withPassAttCount());
        assertEquals(1, report.withRushAttCount());
        assertEquals(1, report.withRecTgtCount());
        assertEquals(1, report.withReceptionsCount());
        assertEquals(1, source.weeklyStatCalls);

        var entries = new LiveWaiverCurrentWeekStatRepository(database).entries(report.statSnapshotId());
        assertEquals(List.of("p3", "p4", "p5"), entries.stream()
            .map(LiveWaiverCurrentWeekStatRepository.Entry::sleeperPlayerId).toList());

        var p3 = entries.get(0);
        assertEquals("SOURCE_PRESENT", p3.sourceState());
        assertEquals(7.0, p3.recTgt());
        assertEquals(5.0, p3.receptions());
        assertEquals(61.0, p3.recYd());
        assertNull(p3.rushAtt());
        assertTrue(p3.rawNumericJson().contains("\"extra_numeric\":9"));
        assertTrue(p3.rawNumericJson().indexOf("extra_numeric") < p3.rawNumericJson().indexOf("rec"));

        var p4 = entries.get(1);
        assertEquals("SOURCE_PRESENT", p4.sourceState());
        assertEquals(4.0, p4.passAtt());
        assertEquals(2.0, p4.rushAtt());
        assertNull(p4.recTgt());

        var p5 = entries.get(2);
        assertEquals("SOURCE_ABSENT", p5.sourceState());
        assertNull(p5.rawNumericJson());
        assertNull(p5.passAtt());
        assertNull(p5.rushAtt());
        assertNull(p5.recTgt());

        var counts = new LiveWaiverCurrentWeekStatRepository(database).counts(report.statSnapshotId());
        assertEquals(3, counts.total());
        assertEquals(2, counts.sourcePresent());
        assertEquals(1, counts.sourceAbsent());
    }

    @Test
    void newlyRosteredTargetBlocksBeforeWeeklyStatsFetch() throws Exception {
        Database database = seededDatabase();
        FixtureSource source = new FixtureSource(true, false, false);
        var sync = new SleeperLiveWaiverCurrentWeekStatSync(database, source,
            Clock.fixed(Instant.parse("2026-09-08T03:00:00Z"), ZoneOffset.UTC));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> sync.sync("L"));
        assertTrue(error.getMessage().contains("now rostered"));
        assertEquals(0, source.weeklyStatCalls);
        assertEquals(0, new LiveWaiverCurrentWeekStatRepository(database).snapshotCountForLeague("L"));
    }

    @Test
    void invalidCurrentStateBlocksBeforeWeeklyStatsFetch() throws Exception {
        Database database = seededDatabase();
        FixtureSource source = new FixtureSource(false, true, false);
        var sync = new SleeperLiveWaiverCurrentWeekStatSync(database, source,
            Clock.fixed(Instant.parse("2026-09-08T03:00:00Z"), ZoneOffset.UTC));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> sync.sync("L"));
        assertTrue(error.getMessage().contains("not 2026 regular season"));
        assertEquals(0, source.weeklyStatCalls);
        assertEquals(0, new LiveWaiverCurrentWeekStatRepository(database).snapshotCountForLeague("L"));
    }

    @Test
    void duplicateWeeklyStatIdentityFailsClosedWithoutPersistence() throws Exception {
        Database database = seededDatabase();
        FixtureSource source = new FixtureSource(false, false, true);
        var sync = new SleeperLiveWaiverCurrentWeekStatSync(database, source,
            Clock.fixed(Instant.parse("2026-09-08T03:00:00Z"), ZoneOffset.UTC));

        assertThrows(Exception.class, () -> sync.sync("L"));
        assertEquals(1, source.weeklyStatCalls);
        assertEquals(0, new LiveWaiverCurrentWeekStatRepository(database).snapshotCountForLeague("L"));
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

        new LiveWaiverAvailabilityRepository(database).save(
            new LiveWaiverAvailabilityRepository.Snapshot(
                "A", "L", "M", "S", 2026, "in_season", 1,
                SleeperLiveWaiverAvailabilitySync.POLICY_ID,
                SleeperLiveWaiverAvailabilitySync.SOURCE,
                Instant.parse("2026-09-08T02:00:00Z"),
                3, 3, 0, 3, 3, 0, 0, 3, 3),
            List.of(
                availabilityEntry("p3", "Add Only", "WR", 10, 0, "ADD_ONLY"),
                availabilityEntry("p4", "Both", "TE", 4, 2, "BOTH"),
                availabilityEntry("p5", "Drop Only", "RB", 0, 7, "DROP_ONLY")));
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

    private static LiveWaiverAvailabilityRepository.Entry availabilityEntry(
        String id, String name, String position, int adds, int drops, String membership) {
        return new LiveWaiverAvailabilityRepository.Entry(
            id, name, position, adds, drops, adds - drops, membership, "SOURCE_PRESENT",
            "CHI", "Active", null, null, null, position, 2);
    }

    private static final class FixtureSource implements SleeperLiveWaiverCurrentWeekStatSync.Source {
        private final boolean targetRostered;
        private final boolean invalidState;
        private final boolean duplicateStats;
        int weeklyStatCalls;

        FixtureSource(boolean targetRostered, boolean invalidState, boolean duplicateStats) {
            this.targetRostered = targetRostered;
            this.invalidState = invalidState;
            this.duplicateStats = duplicateStats;
        }

        @Override public String league(String sleeperLeagueId) {
            return "{\"league_id\":\"S\",\"season\":\"2026\",\"status\":\"in_season\",\"settings\":{\"leg\":1}}";
        }

        @Override public String rosters(String sleeperLeagueId) {
            return targetRostered
                ? "[{\"roster_id\":1,\"players\":[\"p1\",\"p3\"]},{\"roster_id\":2,\"players\":[\"p2\"]}]"
                : "[{\"roster_id\":1,\"players\":[\"p1\"]},{\"roster_id\":2,\"players\":[\"p2\"]}]";
        }

        @Override public String nflState() {
            return invalidState
                ? "{\"season\":\"2026\",\"week\":1,\"season_type\":\"pre\"}"
                : "{\"season\":\"2026\",\"week\":1,\"season_type\":\"regular\"}";
        }

        @Override public String weeklyStats(int season, int week) {
            weeklyStatCalls++;
            assertEquals(2026, season);
            assertEquals(1, week);
            if (duplicateStats) {
                return "{\"p3\":{\"rec_tgt\":1},\"p3\":{\"rec_tgt\":2}}";
            }
            return """
                {
                  "p3":{"rec_tgt":7,"rec":5,"rec_yd":61,"rec_td":1,"extra_numeric":9,"note":"partial"},
                  "p4":{"pass_att":4,"pass_cmp":3,"pass_yd":30,"rush_att":2,"rush_yd":11}
                }
                """;
        }
    }
}
