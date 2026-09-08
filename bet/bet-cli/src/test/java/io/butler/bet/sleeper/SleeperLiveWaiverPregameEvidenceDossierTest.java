package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LiveWaiverAvailabilityRepository;
import io.butler.bet.data.LiveWaiverCurrentWeekStatRepository;
import io.butler.bet.data.LiveWaiverMarketAttentionRepository;
import io.butler.bet.data.LiveWaiverSnapshotRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerSeasonProduction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverPregameEvidenceDossierTest {
    @TempDir Path tempDir;

    @Test
    void composesExactPregameLanesAndKeepsUnobservedWeekDistinctFromZero() throws Exception {
        Database database = seededDatabase();

        var report = new SleeperLiveWaiverPregameEvidenceDossier(database).audit("L");

        assertEquals("M", report.marketSnapshotId());
        assertEquals("A", report.availabilitySnapshotId());
        assertEquals("C", report.currentWeekSnapshotId());
        assertEquals(3, report.candidateCount());
        assertEquals(2, report.teamKnownCount());
        assertEquals(3, report.providerStatusKnownCount());
        assertEquals(1, report.injuryFlagPresentCount());
        assertEquals(2, report.depthEvidencePresentCount());
        assertEquals(1, report.priorSeasonProductionPresentCount());
        assertEquals(0, report.currentWeekObservedCount());

        assertEquals(List.of("p3", "p4", "p5"), report.candidates().stream()
            .map(value -> value.market().sleeperPlayerId()).toList());

        var p3 = report.candidates().get(0);
        assertEquals("PRIOR_SEASON_PRODUCTION_PRESENT", p3.priorSeasonProductionState());
        assertEquals(1, p3.priorSeasonProduction().size());
        assertEquals(712, p3.priorSeasonProduction().get(0).receivingYards());
        assertEquals("INJURY_FLAG_PRESENT", p3.injuryEvidenceState());
        assertEquals("CURRENT_WEEK_UNOBSERVED", p3.currentWeekEvidenceState());
        assertEquals("SOURCE_ABSENT", p3.currentWeek().sourceState());
        assertEquals(null, p3.currentWeek().recTgt());

        var p4 = report.candidates().get(1);
        assertEquals("PRIOR_SEASON_PRODUCTION_MISSING", p4.priorSeasonProductionState());
        assertEquals("DEPTH_EVIDENCE_MISSING", p4.depthEvidenceState());

        var p5 = report.candidates().get(2);
        assertEquals("CURRENT_TEAM_UNKNOWN", p5.teamEvidenceState());
        assertEquals("INJURY_FLAG_ABSENT_OR_UNKNOWN", p5.injuryEvidenceState());
    }

    @Test
    void newerAvailabilitySnapshotWithoutMatchingCurrentWeekSnapshotFailsClosed() throws Exception {
        Database database = seededDatabase();
        new LiveWaiverAvailabilityRepository(database).save(
            availabilitySnapshot("B", Instant.parse("2026-09-08T03:30:00Z")), availabilityEntries());

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new SleeperLiveWaiverPregameEvidenceDossier(database).audit("L"));
        assertTrue(error.getMessage().contains("does not reference selected BF-606 snapshot"));
    }

    private Database seededDatabase() throws Exception {
        Database database = new Database(tempDir.resolve("butler-test.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("L", "S", "Test League", 2026));

        new LiveWaiverSnapshotRepository(database).save(
            new LiveWaiverSnapshotRepository.Snapshot(
                "W", "L", "S", 2026, "in_season", 1,
                SleeperLiveWaiverUniverseAudit.ACTIVE_PLAYER_SOURCE,
                SleeperLiveWaiverUniverseAudit.POLICY_ID,
                SleeperLiveWaiverSnapshotSync.ELIGIBILITY_POLICY_ID,
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
        players.save(new Player("b5", "p5", "Drop Only", "RB", null));

        new PlayerSeasonProductionRepository(database).save(new PlayerSeasonProduction(
            "prod-p3", "b3", 2025, 17, 0, 0, 0, 41, 1, 58, 712, 5, 0,
            "nflverse", LocalDate.parse("2026-09-01")));

        new LiveWaiverAvailabilityRepository(database).save(
            availabilitySnapshot("A", Instant.parse("2026-09-08T02:00:00Z")), availabilityEntries());

        new LiveWaiverCurrentWeekStatRepository(database).save(
            new LiveWaiverCurrentWeekStatRepository.Snapshot(
                "C", "L", "M", "A", "S", 2026, "in_season", 1,
                2026, 1, "regular", SleeperLiveWaiverCurrentWeekStatSync.POLICY_ID,
                "stats/nfl/regular/2026/1", SleeperLiveWaiverCurrentWeekStatSync.OBSERVATION_STATE,
                Instant.parse("2026-09-08T03:00:00Z"), 3, 0, 3, 0, 0, 0, 0),
            List.of(
                absentWeekEntry("p3", "Add Only", "WR", 10, 0, "ADD_ONLY"),
                absentWeekEntry("p4", "Both", "TE", 4, 2, "BOTH"),
                absentWeekEntry("p5", "Drop Only", "RB", 0, 7, "DROP_ONLY")));
        return database;
    }

    private static LiveWaiverAvailabilityRepository.Snapshot availabilitySnapshot(String id, Instant observed) {
        return new LiveWaiverAvailabilityRepository.Snapshot(
            id, "L", "M", "S", 2026, "in_season", 1,
            SleeperLiveWaiverAvailabilitySync.POLICY_ID,
            SleeperLiveWaiverAvailabilitySync.SOURCE,
            observed, 3, 3, 0, 2, 3, 1, 0, 2, 2);
    }

    private static List<LiveWaiverAvailabilityRepository.Entry> availabilityEntries() {
        return List.of(
            new LiveWaiverAvailabilityRepository.Entry(
                "p3", "Add Only", "WR", 10, 0, 10, "ADD_ONLY", "SOURCE_PRESENT",
                "CHI", "Active", "Questionable", "2026-09-06", null, "WR", 2),
            new LiveWaiverAvailabilityRepository.Entry(
                "p4", "Both", "TE", 4, 2, 2, "BOTH", "SOURCE_PRESENT",
                "KC", "Active", null, null, null, null, null),
            new LiveWaiverAvailabilityRepository.Entry(
                "p5", "Drop Only", "RB", 0, 7, -7, "DROP_ONLY", "SOURCE_PRESENT",
                null, "Active", null, null, null, "RB", 3));
    }

    private static LiveWaiverCurrentWeekStatRepository.Entry absentWeekEntry(
        String id, String name, String position, int adds, int drops, String membership) {
        return new LiveWaiverCurrentWeekStatRepository.Entry(
            id, name, position, adds, drops, adds - drops, membership, "SOURCE_ABSENT", null,
            null, null, null, null, null, null, null, null, null, null, null, null, null);
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
}
