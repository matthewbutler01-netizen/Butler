package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.domain.League;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SleeperCurrentSeasonSuccessorRelinkTest {
    @TempDir Path tempDir;

    @Test
    void relinksOnlyTheUniqueExpectedSuccessorAndVerifiesReadBack() throws Exception {
        Database database = initialized("provider-2025");
        var relink = new SleeperCurrentSeasonSuccessorRelink(database, ignored -> uniqueReport());

        var report = relink.relink("l1", "successor-2026");

        assertEquals(SleeperCurrentSeasonSuccessorRelink.RelinkState.RELINKED_VERIFIED, report.state());
        assertEquals("provider-2025", report.previousSleeperLeagueId());
        assertEquals("successor-2026", report.newSleeperLeagueId());
        assertEquals(2026, report.persistedSeason());
        assertEquals(List.of("successor-2026", "provider-2025", "root-2024"), report.lineageNewestToOldest());

        League persisted = new LeagueRepository(database).findById("l1").orElseThrow();
        assertEquals("l1", persisted.getId());
        assertEquals("Best", persisted.getName());
        assertEquals("successor-2026", persisted.getExternalId());
        assertEquals(Integer.valueOf(2026), persisted.getSeason());
    }

    @Test
    void mismatchedOperatorExpectedSuccessorFailsWithoutMutation() throws Exception {
        Database database = initialized("provider-2025");
        var relink = new SleeperCurrentSeasonSuccessorRelink(database, ignored -> uniqueReport());

        assertThrows(IllegalStateException.class, () -> relink.relink("l1", "wrong-successor"));
        assertUnchanged(database, "provider-2025", 2025);
    }

    @Test
    void noSuccessorFailsWithoutMutation() throws Exception {
        Database database = initialized("provider-2025");
        var relink = new SleeperCurrentSeasonSuccessorRelink(database, ignored -> noSuccessorReport());

        assertThrows(IllegalStateException.class, () -> relink.relink("l1", "successor-2026"));
        assertUnchanged(database, "provider-2025", 2025);
    }

    @Test
    void ambiguousSuccessorsFailWithoutMutation() throws Exception {
        Database database = initialized("provider-2025");
        var relink = new SleeperCurrentSeasonSuccessorRelink(database, ignored -> ambiguousReport());

        assertThrows(IllegalStateException.class, () -> relink.relink("l1", "successor-2026"));
        assertUnchanged(database, "provider-2025", 2025);
    }

    @Test
    void stalePersistedLinkFailsWithoutOverwritingIt() throws Exception {
        Database database = initialized("newer-link");
        var relink = new SleeperCurrentSeasonSuccessorRelink(database, ignored -> uniqueReport());

        assertThrows(IllegalStateException.class, () -> relink.relink("l1", "successor-2026"));
        assertUnchanged(database, "newer-link", 2025);
    }

    @Test
    void successorAlreadyLinkedToAnotherButlerLeagueFailsWithoutMutation() throws Exception {
        Database database = initialized("provider-2025");
        new LeagueRepository(database).save(new League("l2", "successor-2026", "Other", 2026));
        var relink = new SleeperCurrentSeasonSuccessorRelink(database, ignored -> uniqueReport());

        assertThrows(IllegalStateException.class, () -> relink.relink("l1", "successor-2026"));
        assertUnchanged(database, "provider-2025", 2025);
    }

    private Database initialized(String externalId) throws Exception {
        Database database = new Database(tempDir.resolve("bf597.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", externalId, "Best", 2025));
        return database;
    }

    private static void assertUnchanged(Database database, String externalId, int season) throws Exception {
        League persisted = new LeagueRepository(database).findById("l1").orElseThrow();
        assertEquals(externalId, persisted.getExternalId());
        assertEquals(Integer.valueOf(season), persisted.getSeason());
        assertEquals("Best", persisted.getName());
    }

    private static SleeperCurrentSeasonSuccessorDiscovery.DiscoveryReport uniqueReport() {
        var candidate = candidate("successor-2026", true);
        return report(List.of(candidate), SleeperCurrentSeasonSuccessorDiscovery.DiscoveryState.UNIQUE_SUCCESSOR);
    }

    private static SleeperCurrentSeasonSuccessorDiscovery.DiscoveryReport noSuccessorReport() {
        return report(
            List.of(candidate("unrelated-2026", false)),
            SleeperCurrentSeasonSuccessorDiscovery.DiscoveryState.NO_SUCCESSOR);
    }

    private static SleeperCurrentSeasonSuccessorDiscovery.DiscoveryReport ambiguousReport() {
        return report(
            List.of(candidate("successor-2026", true), candidate("second-2026", true)),
            SleeperCurrentSeasonSuccessorDiscovery.DiscoveryState.AMBIGUOUS_SUCCESSORS);
    }

    private static SleeperCurrentSeasonSuccessorDiscovery.CandidateObservation candidate(
        String id,
        boolean matches) {
        List<String> lineage = matches
            ? List.of(id, "provider-2025", "root-2024")
            : List.of(id, "other-2025", "other-root");
        return new SleeperCurrentSeasonSuccessorDiscovery.CandidateObservation(
            id,
            "Best",
            2026,
            "drafting",
            List.of("u1"),
            lineage,
            matches);
    }

    private static SleeperCurrentSeasonSuccessorDiscovery.DiscoveryReport report(
        List<SleeperCurrentSeasonSuccessorDiscovery.CandidateObservation> candidates,
        SleeperCurrentSeasonSuccessorDiscovery.DiscoveryState state) {
        return new SleeperCurrentSeasonSuccessorDiscovery.DiscoveryReport(
            SleeperCurrentSeasonSuccessorDiscovery.POLICY_ID,
            "l1",
            "Best",
            "provider-2025",
            "Best",
            2025,
            "complete",
            2026,
            1,
            0,
            candidates,
            state);
    }
}
