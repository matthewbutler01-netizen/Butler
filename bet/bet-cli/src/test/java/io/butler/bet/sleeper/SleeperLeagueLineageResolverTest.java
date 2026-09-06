package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLeagueLineageResolverTest {
    @Test
    void resolvesProviderHistoryToRootAndPreservesOrderedChain() throws Exception {
        FakeSource source = new FakeSource();
        source.links.put("2025", new SleeperLeagueLineageResolver.LeagueLink("2025", 2025, "2024"));
        source.links.put("2024", new SleeperLeagueLineageResolver.LeagueLink("2024", 2024, "2023"));
        source.links.put("2023", new SleeperLeagueLineageResolver.LeagueLink("2023", 2023, null));

        var lineage = new SleeperLeagueLineageResolver(source).resolve("2025");

        assertEquals("2025", lineage.startingSleeperLeagueId());
        assertEquals(2025, lineage.startingSeason());
        assertEquals("2023", lineage.rootSleeperLeagueId());
        assertEquals(java.util.List.of("2025", "2024", "2023"),
            lineage.linksNewestToOldest().stream().map(SleeperLeagueLineageResolver.LeagueLink::leagueId).toList());
        assertTrue(lineage.containsSleeperLeagueId("2024"));
        assertEquals(3, source.calls);
    }

    @Test
    void treatsZeroSentinelAsLineageTerminatorWithoutFetchingLeagueZero() throws Exception {
        FakeSource source = new FakeSource();
        source.links.put("2022", new SleeperLeagueLineageResolver.LeagueLink("2022", 2022, " 0 "));

        var lineage = new SleeperLeagueLineageResolver(source).resolve("2022");

        assertEquals("2022", lineage.rootSleeperLeagueId());
        assertEquals(1, lineage.linksNewestToOldest().size());
        assertNull(lineage.linksNewestToOldest().get(0).previousLeagueId());
        assertEquals(1, source.calls);
    }

    @Test
    void failsClosedOnCycleOrNonDecreasingHistory() {
        FakeSource cycle = new FakeSource();
        cycle.links.put("a", new SleeperLeagueLineageResolver.LeagueLink("a", 2025, "b"));
        cycle.links.put("b", new SleeperLeagueLineageResolver.LeagueLink("b", 2024, "a"));
        assertThrows(IllegalStateException.class, () -> new SleeperLeagueLineageResolver(cycle).resolve("a"));

        FakeSource forward = new FakeSource();
        forward.links.put("a", new SleeperLeagueLineageResolver.LeagueLink("a", 2025, "b"));
        forward.links.put("b", new SleeperLeagueLineageResolver.LeagueLink("b", 2025, null));
        assertThrows(IllegalStateException.class, () -> new SleeperLeagueLineageResolver(forward).resolve("a"));
    }

    private static final class FakeSource implements SleeperLeagueLineageResolver.LinkSource {
        private final Map<String, SleeperLeagueLineageResolver.LeagueLink> links = new HashMap<>();
        private int calls;

        @Override
        public SleeperLeagueLineageResolver.LeagueLink fetch(String sleeperLeagueId) {
            calls++;
            var link = links.get(sleeperLeagueId);
            if (link == null) throw new IllegalArgumentException("missing fake link: " + sleeperLeagueId);
            return link;
        }
    }
}
