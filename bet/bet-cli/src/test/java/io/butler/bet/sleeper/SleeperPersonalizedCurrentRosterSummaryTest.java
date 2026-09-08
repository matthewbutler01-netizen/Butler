package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperPersonalizedCurrentRosterSummaryTest {

    @Test
    void presentsExactLiveRosterWithStarterBenchReserveTaxiAndNoNameInference() throws Exception {
        String league = """
            {"league_id":"league-1","roster_positions":["QB","RB","WR","TE","SUPER_FLEX","BN","BN","BN"]}
            """;
        String rosters = """
            [
              {"roster_id":5,"owner_id":"other","players":["x"],"starters":[]},
              {"roster_id":6,"owner_id":"user-1","players":["p1","p2","p3","p4","p5","missing"],
               "starters":["p1","p2","p3","p4","p5"],"reserve":["p4x"],"taxi":[]}
            ]
            """;
        // Correct the fixture to keep reserve membership exact while leaving one unmapped bench identity.
        rosters = rosters.replace("\"reserve\":[\"p4x\"]", "\"reserve\":[\"missing\"]");

        Map<String, SleeperPersonalizedCurrentRosterSummary.PlayerDisplay> mapped = Map.of(
            "p1", player("p1", "Drake Maye", "QB", "NE"),
            "p2", player("p2", "Travis Etienne", "RB", "JAX"),
            "p3", player("p3", "Josh Downs", "WR", "IND"),
            "p4", player("p4", "Dalton Kincaid", "TE", "BUF"),
            "p5", player("p5", "Baker Mayfield", "QB", "TB")
        );
        var service = new SleeperPersonalizedCurrentRosterSummary(
            source(league, rosters), mapped::get);

        var report = service.summarize(target());

        assertEquals(SleeperPersonalizedCurrentRosterSummary.RosterState.LIVE_CURRENT_ROSTER_VERIFIED, report.state());
        assertEquals(SleeperPersonalizedCurrentRosterSummary.LineupSlotState.STARTER_SLOTS_VERIFIED, report.lineupSlotState());
        assertEquals(6, report.players().size());
        assertEquals("QB", report.players().get(0).lineupSlot());
        assertEquals("SUPER_FLEX", report.players().get(4).lineupSlot());
        assertEquals(SleeperPersonalizedCurrentRosterSummary.RosterRole.STARTER, report.players().get(0).role());

        var unmapped = report.players().stream().filter(value -> value.sleeperPlayerId().equals("missing")).findFirst().orElseThrow();
        assertEquals(SleeperPersonalizedCurrentRosterSummary.RosterRole.RESERVE, unmapped.role());
        assertEquals(SleeperPersonalizedCurrentRosterSummary.MappingState.UNMAPPED, unmapped.mappingState());
        assertNull(unmapped.displayName());
        assertNull(unmapped.position());
        assertNull(unmapped.nflTeam());
    }

    @Test
    void lineupSlotsRemainUnavailableRatherThanInventedWhenProviderCountsDoNotReconcile() throws Exception {
        String league = "{\"league_id\":\"league-1\",\"roster_positions\":[\"QB\",\"RB\",\"BN\"]}";
        String rosters = "[{\"roster_id\":6,\"owner_id\":\"user-1\",\"players\":[\"p1\"],\"starters\":[\"p1\"]}]";
        var service = new SleeperPersonalizedCurrentRosterSummary(
            source(league, rosters), id -> player(id, "Drake Maye", "QB", "NE"));

        var report = service.summarize(target());

        assertEquals(SleeperPersonalizedCurrentRosterSummary.LineupSlotState.STARTER_SLOTS_UNAVAILABLE, report.lineupSlotState());
        assertNull(report.players().get(0).lineupSlot());
    }

    @Test
    void failsClosedWhenLiveRosterOwnershipDoesNotMatchBf623Target() {
        String league = "{\"league_id\":\"league-1\",\"roster_positions\":[\"QB\",\"BN\"]}";
        String rosters = "[{\"roster_id\":6,\"owner_id\":\"different-user\",\"players\":[\"p1\"],\"starters\":[\"p1\"]}]";
        var service = new SleeperPersonalizedCurrentRosterSummary(
            source(league, rosters), id -> player(id, "Drake Maye", "QB", "NE"));

        var error = assertThrows(IllegalStateException.class, () -> service.summarize(target()));
        assertTrue(error.getMessage().contains("owner does not match BF-623"));
    }

    @Test
    void failsClosedOnOverlappingRosterRoles() {
        String league = "{\"league_id\":\"league-1\",\"roster_positions\":[\"QB\",\"BN\"]}";
        String rosters = "[{\"roster_id\":6,\"owner_id\":\"user-1\",\"players\":[\"p1\"],\"starters\":[\"p1\"],\"reserve\":[\"p1\"]}]";
        var service = new SleeperPersonalizedCurrentRosterSummary(
            source(league, rosters), id -> player(id, "Drake Maye", "QB", "NE"));

        var error = assertThrows(IllegalStateException.class, () -> service.summarize(target()));
        assertTrue(error.getMessage().contains("overlapping starter/reserve role"));
    }

    private static SleeperPersonalizedCurrentRosterSummary.Source source(String league, String rosters) {
        return new SleeperPersonalizedCurrentRosterSummary.Source() {
            @Override public String league(String sleeperLeagueId) { return league; }
            @Override public String rosters(String sleeperLeagueId) { return rosters; }
        };
    }

    private static SleeperPersonalizedCurrentRosterSummary.PlayerDisplay player(
        String id, String name, String position, String team) {
        return new SleeperPersonalizedCurrentRosterSummary.PlayerDisplay(id, name, position, team);
    }

    private static SleeperPersonalizedTargetService.VerifiedTarget target() {
        return new SleeperPersonalizedTargetService.VerifiedTarget(
            SleeperPersonalizedTargetService.BF623_POLICY_ID,
            "butler-league-1",
            "mbutler0624",
            "user-1",
            "league-1",
            "Hard(CORE)-Dynasty",
            "in_season",
            6,
            SleeperPersonalizedTargetService.MembershipRole.OWNER,
            "mbutler0624",
            "nuke the whales",
            SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED);
    }
}
