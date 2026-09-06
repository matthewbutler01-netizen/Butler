package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.domain.League;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SleeperCohortCorpusAcquisitionPlannerTest {
    @TempDir Path tempDir;

    @Test
    void enumeratesAllAnchorOwnerLeaguesDeduplicatedWithProvenanceAndNoWrites() throws Exception {
        Database database = new Database(tempDir.resolve("cohort-plan.db"));
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        leagues.save(new League("anchor", "anchor-sleeper", "Anchor League", 2026));
        leagues.save(new League("existing-200", "200", "Already Known", 2025));

        FakeGateway gateway = new FakeGateway();
        gateway.rosters.put("anchor-sleeper", List.of(
            roster(3, "owner-b"), roster(1, "owner-a"), roster(2, null)));
        gateway.userLeagues.put("owner-a", List.of(
            league("300", "League 300", 2025, 2, 4, List.of("QB", "RB", "WR", "BN")),
            league("100", "League 100", 2025, 0, 15, List.of("QB", "RB", "WR", "TE", "BN"))));
        gateway.userLeagues.put("owner-b", List.of(
            league("200", "League 200", 2025, 1, 6, List.of("QB", "WR", "FLEX", "BN")),
            league("100", "League 100", 2025, 0, 15, List.of("QB", "RB", "WR", "TE", "BN"))));
        gateway.rosters.put("100", rosters(10));
        gateway.rosters.put("200", rosters(12));
        gateway.rosters.put("300", rosters(14));

        var plan = new SleeperCohortCorpusAcquisitionPlanner(gateway, database)
            .plan("anchor", 2025);

        assertEquals(SleeperCohortCorpusAcquisitionPlanner.POLICY_ID, plan.policyId());
        assertEquals(3, plan.anchorRosterCount());
        assertEquals(2, plan.anchorOwnerCount());
        assertEquals(1, plan.ownerlessAnchorRosters());
        assertEquals(List.of("owner-a", "owner-b"), plan.anchorOwnerIds());
        assertEquals(List.of("100", "200", "300"),
            plan.candidates().stream().map(SleeperCohortCorpusAcquisitionPlanner.Candidate::sleeperLeagueId).toList());
        assertEquals(List.of(10, 12, 14),
            plan.candidates().stream().map(SleeperCohortCorpusAcquisitionPlanner.Candidate::rosterCount).toList());
        assertEquals(List.of("owner-a", "owner-b"), plan.candidates().get(0).exposingOwnerIds());
        assertEquals(List.of("owner-b"), plan.candidates().get(1).exposingOwnerIds());
        assertEquals(List.of("owner-a"), plan.candidates().get(2).exposingOwnerIds());
        assertEquals(SleeperCohortCorpusAcquisitionPlanner.CandidateState.ALREADY_PERSISTED,
            plan.candidates().get(1).state());
        assertEquals("existing-200", plan.candidates().get(1).existingButlerLeagueId());

        assertEquals(2, leagues.findAll().size());
        assertEquals(2026, leagues.findById("anchor").orElseThrow().getSeason());
        assertEquals(List.of("owner-a", "owner-b"), gateway.userLeagueCalls);
    }

    private static SleeperJsonParser.SleeperLeague league(
        String id, String name, int season, int type, int rounds, List<String> positions) {
        return new SleeperJsonParser.SleeperLeague(id, name, positions, season, type, rounds, Map.of("pass_td", 4.0));
    }

    private static SleeperJsonParser.SleeperRoster roster(int rosterId, String ownerId) {
        return new SleeperJsonParser.SleeperRoster(
            rosterId, ownerId, List.of(), List.of(), List.of(), List.of());
    }

    private static List<SleeperJsonParser.SleeperRoster> rosters(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
            .mapToObj(i -> roster(i, "candidate-owner-" + i)).toList();
    }

    private static final class FakeGateway implements SleeperGateway {
        private final Map<String, List<SleeperJsonParser.SleeperLeague>> userLeagues = new HashMap<>();
        private final Map<String, List<SleeperJsonParser.SleeperRoster>> rosters = new HashMap<>();
        private final java.util.ArrayList<String> userLeagueCalls = new java.util.ArrayList<>();

        @Override
        public SleeperJsonParser.SleeperLeague fetchLeague(String leagueId) {
            throw new AssertionError("cohort discovery must not fetch outcome or unrelated league detail endpoints");
        }

        @Override
        public List<SleeperJsonParser.SleeperUser> fetchUsers(String leagueId) {
            throw new AssertionError("cohort discovery derives owner identities from anchor rosters");
        }

        @Override
        public List<SleeperJsonParser.SleeperRoster> fetchRosters(String leagueId) {
            return rosters.getOrDefault(leagueId, List.of());
        }

        @Override
        public List<SleeperJsonParser.SleeperLeague> fetchUserLeagues(String userId, int season) {
            assertEquals(2025, season);
            userLeagueCalls.add(userId);
            return userLeagues.getOrDefault(userId, List.of());
        }

        @Override
        public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() {
            throw new AssertionError("read-only cohort discovery must not fetch the NFL player universe");
        }
    }
}
