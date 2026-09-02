package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SleeperJsonParserTest {
    private final SleeperJsonParser parser = new SleeperJsonParser();

    @Test
    void parsesLeagueUsersRostersAndPlayers() throws Exception {
        var league = parser.parseLeague("{\"league_id\":\"123\",\"name\":\"Dynasty League\",\"season\":\"2026\",\"settings\":{\"type\":2,\"draft_rounds\":5}}");
        assertEquals("123", league.id());
        assertEquals("Dynasty League", league.name());
        assertEquals(2026, league.season());
        assertEquals(2, league.leagueType());
        assertEquals(5, league.draftRounds());

        var users = parser.parseUsers("[{\"user_id\":\"u1\",\"display_name\":\"Matt\",\"metadata\":{\"team_name\":\"Butler Dynasty\"}}]");
        assertEquals(1, users.size());
        assertEquals("Matt", users.getFirst().displayName());
        assertEquals("Butler Dynasty", users.getFirst().teamName());

        var rosters = parser.parseRosters("[{\"roster_id\":1,\"owner_id\":\"u1\",\"players\":[\"p1\",\"p2\",\"p3\",\"p4\"],\"starters\":[\"p1\"],\"reserve\":[\"p3\"],\"taxi\":[\"p4\"]}]");
        assertEquals(1, rosters.size());
        assertEquals(1, rosters.getFirst().rosterId());
        assertEquals(4, rosters.getFirst().playerIds().size());
        assertEquals(java.util.List.of("p1"), rosters.getFirst().starterIds());
        assertEquals(java.util.List.of("p3"), rosters.getFirst().reserveIds());
        assertEquals(java.util.List.of("p4"), rosters.getFirst().taxiIds());

        var players = parser.parsePlayers("{\"p1\":{\"full_name\":\"Quarter Back\",\"position\":\"QB\",\"team\":\"CHI\"},\"p2\":{\"first_name\":\"Wide\",\"last_name\":\"Receiver\",\"position\":\"WR\",\"team\":null}}");
        assertEquals("Quarter Back", players.get("p1").displayName());
        assertEquals("Wide Receiver", players.get("p2").displayName());
        assertNull(players.get("p2").nflTeam());
    }

    @Test
    void parsesTradedDraftPicks() throws Exception {
        var picks = parser.parseTradedPicks("""
            [
              {"season":"2027","round":1,"roster_id":2,"previous_owner_id":2,"owner_id":5},
              {"season":"2028","round":3,"roster_id":4,"previous_owner_id":7,"owner_id":9}
            ]
            """);

        assertEquals(2, picks.size());
        assertEquals(2027, picks.get(0).season());
        assertEquals(1, picks.get(0).round());
        assertEquals(2, picks.get(0).originalRosterId());
        assertEquals(2, picks.get(0).previousOwnerRosterId());
        assertEquals(5, picks.get(0).ownerRosterId());
        assertEquals(2028, picks.get(1).season());
        assertEquals(9, picks.get(1).ownerRosterId());
    }

    @Test
    void missingDraftMetadataDefaultsToUnknownWithoutBreakingLegacyLeagueParsing() throws Exception {
        var league = parser.parseLeague("{\"league_id\":\"123\",\"name\":\"Legacy\"}");
        assertEquals(0, league.season());
        assertEquals(0, league.leagueType());
        assertEquals(0, league.draftRounds());
    }

    @Test
    void rejectsMissingOrInvalidRosterId() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseRosters("[{\"players\":[]}]") );
        assertThrows(IllegalArgumentException.class, () -> parser.parseRosters("[{\"roster_id\":0,\"players\":[]}]") );
    }

    @Test
    void rejectsInvalidTradedPickFields() {
        assertThrows(IllegalArgumentException.class,
            () -> parser.parseTradedPicks("[{\"season\":\"2027\",\"round\":0,\"roster_id\":1,\"previous_owner_id\":1,\"owner_id\":2}]"));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parseTradedPicks("[{\"season\":\"bad\",\"round\":1,\"roster_id\":1,\"previous_owner_id\":1,\"owner_id\":2}]"));
    }
}
