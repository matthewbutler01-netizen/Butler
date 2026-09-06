package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SleeperCorpusAcquisitionPlannerTest {
    @TempDir Path tempDir;

    @Test
    void enumeratesAllOwnerLeaguesInProviderIdOrderWithoutRepositoryWrites() throws Exception {
        Database database = new Database(tempDir.resolve("corpus-plan.db"));
        database.initialize();
        LeagueRepository leagues = new LeagueRepository(database);
        TeamRepository teams = new TeamRepository(database);
        leagues.save(new League("anchor", "anchor-sleeper", "Anchor League", 2026));
        teams.save(new Team("anchor-team", "7", "anchor", "Anchor Team"));
        leagues.save(new League("existing-200", "200", "Already Known", 2025));

        FakeGateway gateway = new FakeGateway();
        gateway.rosters.put("anchor-sleeper", List.of(
            roster(1, "other-owner"), roster(7, "owner-1")));
        gateway.userLeagues = List.of(
            league("300", "League 300", 2025, 2, 4, List.of("QB", "RB", "WR", "BN")),
            league("100", "League 100", 2025, 0, 15, List.of("QB", "RB", "WR", "TE", "BN")),
            league("200", "League 200", 2025, 1, 6, List.of("QB", "WR", "FLEX", "BN")));
        gateway.rosters.put("100", rosters(10));
        gateway.rosters.put("200", rosters(12));
        gateway.rosters.put("300", rosters(14));

        var plan = new SleeperCorpusAcquisitionPlanner(gateway, database)
            .plan("anchor", "anchor-team", 2025);

        assertEquals(SleeperCorpusAcquisitionPlanner.POLICY_ID, plan.policyId());
        assertEquals("owner-1", plan.sleeperOwnerId());
        assertEquals(7, plan.anchorSleeperRosterId());
        assertEquals(List.of("100", "200", "300"),
            plan.candidates().stream().map(SleeperCorpusAcquisitionPlanner.Candidate::sleeperLeagueId).toList());
        assertEquals(List.of(10, 12, 14),
            plan.candidates().stream().map(SleeperCorpusAcquisitionPlanner.Candidate::rosterCount).toList());
        assertEquals(SleeperCorpusAcquisitionPlanner.CandidateState.DISCOVERED_NOT_PERSISTED,
            plan.candidates().get(0).state());
        assertEquals(SleeperCorpusAcquisitionPlanner.CandidateState.ALREADY_PERSISTED,
            plan.candidates().get(1).state());
        assertEquals("existing-200", plan.candidates().get(1).existingButlerLeagueId());
        assertEquals(SleeperCorpusAcquisitionPlanner.CandidateState.DISCOVERED_NOT_PERSISTED,
            plan.candidates().get(2).state());

        assertEquals(2, leagues.findAll().size());
        assertEquals(2026, leagues.findById("anchor").orElseThrow().getSeason());
        assertEquals(1, teams.findByLeagueId("anchor").size());
        assertEquals(1, gateway.userLeagueCalls);
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
            .mapToObj(i -> roster(i, "owner-" + i)).toList();
    }

    private static final class FakeGateway implements SleeperGateway {
        private List<SleeperJsonParser.SleeperLeague> userLeagues = List.of();
        private final Map<String, List<SleeperJsonParser.SleeperRoster>> rosters = new HashMap<>();
        private int userLeagueCalls;

        @Override
        public SleeperJsonParser.SleeperLeague fetchLeague(String leagueId) {
            throw new AssertionError("planner must not fetch outcome or unrelated league detail endpoints");
        }

        @Override
        public List<SleeperJsonParser.SleeperUser> fetchUsers(String leagueId) {
            throw new AssertionError("planner must derive owner from roster identity, not user display metadata");
        }

        @Override
        public List<SleeperJsonParser.SleeperRoster> fetchRosters(String leagueId) {
            return rosters.getOrDefault(leagueId, List.of());
        }

        @Override
        public List<SleeperJsonParser.SleeperLeague> fetchUserLeagues(String userId, int season) {
            assertEquals("owner-1", userId);
            assertEquals(2025, season);
            userLeagueCalls++;
            return userLeagues;
        }

        @Override
        public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() {
            throw new AssertionError("read-only corpus discovery must not fetch the NFL player universe");
        }
    }
}
