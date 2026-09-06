package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.domain.League;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperCohortCorpusHydratorTest {
    @TempDir Path tempDir;

    @Test
    void reusesExistingProviderLineageImportsOnlyLatestAnchorForNewLineagesAndHydratesAllDiscoveredSeasons()
        throws Exception {
        Database database = seededDatabase("A26");
        FakeDiscovery discovery = new FakeDiscovery();
        discovery.plans.put(2024, plan(2024, List.of(
            candidate("A24", "Anchor", 2024, 12),
            candidate("B24", "Ten Team", 2024, 10))));
        discovery.plans.put(2025, plan(2025, List.of(
            candidate("A25", "Anchor", 2025, 12),
            candidate("B25", "Ten Team", 2025, 10),
            candidate("C25", "Fourteen Team", 2025, 14))));

        FakeLineages lineages = new FakeLineages();
        lineages.add(lineage("A26", 2026, link("A26", 2026, "A25"), link("A25", 2025, "A24"), link("A24", 2024, null)));
        lineages.add(lineage("A25", 2025, link("A25", 2025, "A24"), link("A24", 2024, null)));
        lineages.add(lineage("A24", 2024, link("A24", 2024, null)));
        lineages.add(lineage("B25", 2025, link("B25", 2025, "B24"), link("B24", 2024, null)));
        lineages.add(lineage("B24", 2024, link("B24", 2024, null)));
        lineages.add(lineage("C25", 2025, link("C25", 2025, null)));

        List<String> imported = new ArrayList<>();
        SleeperCohortCorpusHydrator.LeagueAnchorImporter importer = sleeperId -> {
            imported.add(sleeperId);
            return switch (sleeperId) {
                case "B25" -> "butler-B";
                case "C25" -> "butler-C";
                default -> throw new AssertionError("unexpected import: " + sleeperId);
            };
        };

        List<String> synced = new ArrayList<>();
        SleeperCohortCorpusHydrator.SeasonEvidenceHydrator seasonHydrator = (butlerId, season) -> {
            synced.add(butlerId + ":" + season);
            String sleeper = switch (butlerId + ":" + season) {
                case "anchor:2024" -> "A24";
                case "anchor:2025" -> "A25";
                case "butler-B:2024" -> "B24";
                case "butler-B:2025" -> "B25";
                case "butler-C:2025" -> "C25";
                default -> throw new AssertionError("unexpected sync: " + butlerId + ":" + season);
            };
            return new SleeperCohortCorpusHydrator.SeasonHydration(season, sleeper, 16, 160, 0, "2026-09-06");
        };

        var result = new SleeperCohortCorpusHydrator(
            discovery, lineages::resolve, database, importer, seasonHydrator)
            .hydrate("anchor", 2024, 2025);

        assertEquals(5, result.discoveredCandidateSeasonLeagues());
        assertEquals(3, result.providerRootGroups());
        assertEquals(2, result.newButlerLineages());
        assertEquals(1, result.reusedButlerLineages());
        assertEquals(0, result.blockedOrImportFailedLineages());
        assertEquals(5, result.successfulSeasons());
        assertEquals(0, result.failedSeasons());
        assertEquals(List.of("B25", "C25"), imported);
        assertEquals(List.of(
            "anchor:2024", "anchor:2025",
            "butler-B:2024", "butler-B:2025",
            "butler-C:2025"), synced);
        assertEquals(List.of(
            SleeperCohortCorpusHydrator.LineageState.REUSED_EXISTING_BUTLER_LINEAGE,
            SleeperCohortCorpusHydrator.LineageState.IMPORTED_NEW_BUTLER_LINEAGE,
            SleeperCohortCorpusHydrator.LineageState.IMPORTED_NEW_BUTLER_LINEAGE),
            result.lineages().stream().map(SleeperCohortCorpusHydrator.LineageHydrationResult::state).toList());
    }

    @Test
    void preservesSeasonEvidenceFailureAndContinuesOtherSeasonsInSameFixedLineage() throws Exception {
        Database database = seededDatabase("A26");
        FakeDiscovery discovery = new FakeDiscovery();
        discovery.plans.put(2024, plan(2024, List.of(candidate("A24", "Anchor", 2024, 12))));
        discovery.plans.put(2025, plan(2025, List.of(candidate("A25", "Anchor", 2025, 12))));

        FakeLineages lineages = new FakeLineages();
        lineages.add(lineage("A26", 2026, link("A26", 2026, "A25"), link("A25", 2025, "A24"), link("A24", 2024, null)));
        lineages.add(lineage("A25", 2025, link("A25", 2025, "A24"), link("A24", 2024, null)));
        lineages.add(lineage("A24", 2024, link("A24", 2024, null)));

        var result = new SleeperCohortCorpusHydrator(
            discovery,
            lineages::resolve,
            database,
            sleeperId -> { throw new AssertionError("existing lineage must not be imported"); },
            (butlerId, season) -> {
                if (season == 2024) throw new IllegalStateException("historical roster identities changed");
                return new SleeperCohortCorpusHydrator.SeasonHydration(2025, "A25", 16, 192, 0, "2026-09-06");
            })
            .hydrate("anchor", 2024, 2025);

        assertEquals(1, result.successfulSeasons());
        assertEquals(1, result.failedSeasons());
        assertEquals(SleeperCohortCorpusHydrator.SeasonState.FAILED,
            result.lineages().get(0).seasons().get(0).state());
        assertTrue(result.lineages().get(0).seasons().get(0).failure().contains("roster identities"));
        assertEquals(SleeperCohortCorpusHydrator.SeasonState.SUCCESS,
            result.lineages().get(0).seasons().get(1).state());
    }

    @Test
    void blocksForkedProviderHistoryInsteadOfCollapsingBranchesIntoOneButlerLeague() throws Exception {
        Database database = seededDatabase("A26");
        FakeDiscovery discovery = new FakeDiscovery();
        discovery.plans.put(2025, plan(2025, List.of(
            candidate("X25A", "Fork A", 2025, 10),
            candidate("X25B", "Fork B", 2025, 10))));

        FakeLineages lineages = new FakeLineages();
        lineages.add(lineage("A26", 2026, link("A26", 2026, null)));
        lineages.add(lineage("X25A", 2025, link("X25A", 2025, "X24"), link("X24", 2024, null)));
        lineages.add(lineage("X25B", 2025, link("X25B", 2025, "X24"), link("X24", 2024, null)));

        var result = new SleeperCohortCorpusHydrator(
            discovery,
            lineages::resolve,
            database,
            sleeperId -> { throw new AssertionError("branched lineage must not import"); },
            (butlerId, season) -> { throw new AssertionError("branched lineage must not hydrate"); })
            .hydrate("anchor", 2025, 2025);

        assertEquals(1, result.providerRootGroups());
        assertEquals(1, result.blockedOrImportFailedLineages());
        assertEquals(SleeperCohortCorpusHydrator.LineageState.BLOCKED_BRANCHED_OR_AMBIGUOUS_LINEAGE,
            result.lineages().get(0).state());
        assertTrue(result.lineages().get(0).failure().contains("neither is an ancestor"));
    }

    private Database seededDatabase(String sleeperExternalId) throws Exception {
        Database database = new Database(tempDir.resolve(java.util.UUID.randomUUID() + ".db"));
        database.initialize();
        new LeagueRepository(database).save(new League("anchor", sleeperExternalId, "Anchor", 2026));
        return database;
    }

    private static SleeperCohortCorpusAcquisitionPlanner.AcquisitionPlan plan(
        int season, List<SleeperCohortCorpusAcquisitionPlanner.Candidate> candidates) {
        return new SleeperCohortCorpusAcquisitionPlanner.AcquisitionPlan(
            SleeperCohortCorpusAcquisitionPlanner.POLICY_ID,
            SleeperCohortCorpusAcquisitionPlanner.BOUNDARY,
            "anchor",
            "A26",
            2,
            2,
            0,
            List.of("owner-1", "owner-2"),
            season,
            candidates);
    }

    private static SleeperCohortCorpusAcquisitionPlanner.Candidate candidate(
        String id, String name, int season, int rosterCount) {
        return new SleeperCohortCorpusAcquisitionPlanner.Candidate(
            id, name, season, 2, 4, rosterCount,
            List.of("QB", "RB", "WR", "FLEX", "BN"),
            List.of("owner-1"),
            SleeperCohortCorpusAcquisitionPlanner.CandidateState.DISCOVERED_NOT_PERSISTED,
            null);
    }

    private static SleeperLeagueLineageResolver.LeagueLink link(String id, int season, String previous) {
        return new SleeperLeagueLineageResolver.LeagueLink(id, season, previous);
    }

    private static SleeperLeagueLineageResolver.Lineage lineage(
        String startingId, int season, SleeperLeagueLineageResolver.LeagueLink... links) {
        List<SleeperLeagueLineageResolver.LeagueLink> chain = List.of(links);
        return new SleeperLeagueLineageResolver.Lineage(
            startingId, chain.get(chain.size() - 1).leagueId(), season, chain);
    }

    private static final class FakeDiscovery implements SleeperCohortCorpusHydrator.Discovery {
        private final Map<Integer, SleeperCohortCorpusAcquisitionPlanner.AcquisitionPlan> plans = new HashMap<>();

        @Override
        public SleeperCohortCorpusAcquisitionPlanner.AcquisitionPlan plan(String anchorButlerLeagueId, int season) {
            assertEquals("anchor", anchorButlerLeagueId);
            var plan = plans.get(season);
            if (plan == null) throw new AssertionError("missing fake plan for season " + season);
            return plan;
        }
    }

    private static final class FakeLineages {
        private final Map<String, SleeperLeagueLineageResolver.Lineage> values = new HashMap<>();

        void add(SleeperLeagueLineageResolver.Lineage lineage) {
            values.put(lineage.startingSleeperLeagueId(), lineage);
        }

        SleeperLeagueLineageResolver.Lineage resolve(String sleeperId) {
            var lineage = values.get(sleeperId);
            if (lineage == null) throw new AssertionError("missing fake lineage for " + sleeperId);
            return lineage;
        }
    }
}
