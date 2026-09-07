package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.domain.League;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperCurrentSeasonSuccessorDiscoveryTest {
    @TempDir Path tempDir;

    @Test
    void provesOneUniqueSuccessorAndDeduplicatesItAcrossOwners() throws Exception {
        Database database = initialized();
        var discovery = new SleeperCurrentSeasonSuccessorDiscovery(database, source(
            """
                {"league_id":"provider-2025","name":"Best","season":"2025","status":"complete"}
                """,
            """
                [
                  {"roster_id":1,"owner_id":"u1"},
                  {"roster_id":2,"owner_id":"u2"}
                ]
                """,
            Map.of(
                "u1", """
                    [
                      {"league_id":"successor","name":"Best","season":"2026","status":"in_season"},
                      {"league_id":"same-name-unrelated","name":"Best","season":"2026","status":"in_season"}
                    ]
                    """,
                "u2", """
                    [{"league_id":"successor","name":"Best","season":"2026","status":"in_season"}]
                    """),
            Map.of(
                "successor", lineage("successor", "provider-2025"),
                "same-name-unrelated", unrelatedLineage("same-name-unrelated"))));

        var report = discovery.discover("l1");

        assertEquals(SleeperCurrentSeasonSuccessorDiscovery.DiscoveryState.UNIQUE_SUCCESSOR, report.state());
        assertEquals(2, report.distinctOwnerIds());
        assertEquals(0, report.ownerlessRosters());
        assertEquals(2, report.candidates().size());
        assertEquals(1, report.matchingCandidates().size());
        assertEquals("successor", report.uniqueSuccessorSleeperLeagueId());
        var successor = report.candidates().stream()
            .filter(candidate -> candidate.sleeperLeagueId().equals("successor"))
            .findFirst().orElseThrow();
        assertEquals(List.of("u1", "u2"), successor.surfacedByOwnerIds());
        assertTrue(successor.lineageContainsLinkedLeague());
        var rejected = report.candidates().stream()
            .filter(candidate -> candidate.sleeperLeagueId().equals("same-name-unrelated"))
            .findFirst().orElseThrow();
        assertFalse(rejected.lineageContainsLinkedLeague());
    }

    @Test
    void keepsZeroMatchesExplicitEvenWithOwnerlessRoster() throws Exception {
        Database database = initialized();
        var discovery = new SleeperCurrentSeasonSuccessorDiscovery(database, source(
            "{\"league_id\":\"provider-2025\",\"name\":\"Best\",\"season\":\"2025\",\"status\":\"complete\"}",
            "[{\"roster_id\":1,\"owner_id\":\"u1\"},{\"roster_id\":2,\"owner_id\":null}]",
            Map.of("u1", "[{\"league_id\":\"other\",\"name\":\"Other\",\"season\":\"2026\"}]"),
            Map.of("other", unrelatedLineage("other"))));

        var report = discovery.discover("l1");

        assertEquals(SleeperCurrentSeasonSuccessorDiscovery.DiscoveryState.NO_SUCCESSOR, report.state());
        assertEquals(1, report.ownerlessRosters());
        assertEquals(0, report.matchingCandidates().size());
        assertNull(report.uniqueSuccessorSleeperLeagueId());
    }

    @Test
    void refusesToChooseWhenMultipleLineageBackedSuccessorsExist() throws Exception {
        Database database = initialized();
        var discovery = new SleeperCurrentSeasonSuccessorDiscovery(database, source(
            "{\"league_id\":\"provider-2025\",\"name\":\"Best\",\"season\":\"2025\",\"status\":\"complete\"}",
            "[{\"roster_id\":1,\"owner_id\":\"u1\"}]",
            Map.of("u1", """
                [
                  {"league_id":"a","name":"A","season":"2026"},
                  {"league_id":"b","name":"B","season":"2026"}
                ]
                """),
            Map.of("a", lineage("a", "provider-2025"), "b", lineage("b", "provider-2025"))));

        var report = discovery.discover("l1");

        assertEquals(SleeperCurrentSeasonSuccessorDiscovery.DiscoveryState.AMBIGUOUS_SUCCESSORS, report.state());
        assertEquals(2, report.matchingCandidates().size());
        assertNull(report.uniqueSuccessorSleeperLeagueId());
    }

    private Database initialized() throws Exception {
        Database database = new Database(tempDir.resolve("bf596.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "provider-2025", "Best", 2025));
        return database;
    }

    private static SleeperCurrentSeasonSuccessorDiscovery.Source source(
        String league,
        String rosters,
        Map<String, String> userLeagues,
        Map<String, SleeperLeagueLineageResolver.Lineage> lineages) {
        return new SleeperCurrentSeasonSuccessorDiscovery.Source() {
            @Override public String league(String sleeperLeagueId) { return league; }
            @Override public String rosters(String sleeperLeagueId) { return rosters; }
            @Override public String userLeagues(String ownerId, int season) { return userLeagues.getOrDefault(ownerId, "[]"); }
            @Override public SleeperLeagueLineageResolver.Lineage lineage(String sleeperLeagueId) {
                return lineages.get(sleeperLeagueId);
            }
        };
    }

    private static SleeperLeagueLineageResolver.Lineage lineage(String current, String previous) {
        return new SleeperLeagueLineageResolver.Lineage(
            current,
            previous,
            2026,
            List.of(
                new SleeperLeagueLineageResolver.LeagueLink(current, 2026, previous),
                new SleeperLeagueLineageResolver.LeagueLink(previous, 2025, null)));
    }

    private static SleeperLeagueLineageResolver.Lineage unrelatedLineage(String current) {
        return new SleeperLeagueLineageResolver.Lineage(
            current,
            "other-2025",
            2026,
            List.of(
                new SleeperLeagueLineageResolver.LeagueLink(current, 2026, "other-2025"),
                new SleeperLeagueLineageResolver.LeagueLink("other-2025", 2025, null)));
    }
}
