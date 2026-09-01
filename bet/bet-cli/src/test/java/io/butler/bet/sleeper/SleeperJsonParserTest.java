package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SleeperJsonParserTest {
    private final SleeperJsonParser parser = new SleeperJsonParser();

    @Test
    void parsesLeagueUsersRostersAndPlayers() throws Exception {
        var league = parser.parseLeague("{\"league_id\":\"123\",\"name\":\"Dynasty League\"}");
        assertEquals("123", league.id());
        assertEquals("Dynasty League", league.name());

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
    void rejectsMissingOrInvalidRosterId() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseRosters("[{\"players\":[]}]") );
        assertThrows(IllegalArgumentException.class, () -> parser.parseRosters("[{\"roster_id\":0,\"players\":[]}]") );
    }
}
