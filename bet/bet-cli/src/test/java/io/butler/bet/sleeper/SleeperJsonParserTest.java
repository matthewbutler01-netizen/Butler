package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SleeperJsonParserTest {
    private final SleeperJsonParser parser = new SleeperJsonParser();

    @Test
    void parsesLeagueUsersRostersAndPlayers() throws Exception {
        var league = parser.parseLeague("{\"league_id\":\"123\",\"name\":\"Dynasty League\"}");
        assertEquals("123", league.id());
        assertEquals("Dynasty League", league.name());

        var users = parser.parseUsers("[{\"user_id\":\"u1\",\"display_name\":\"Matt\"}]");
        assertEquals(1, users.size());
        assertEquals("Matt", users.getFirst().displayName());

        var rosters = parser.parseRosters("[{\"roster_id\":1,\"owner_id\":\"u1\",\"players\":[\"p1\",\"p2\"]}]");
        assertEquals(1, rosters.size());
        assertEquals(1, rosters.getFirst().rosterId());
        assertEquals(2, rosters.getFirst().playerIds().size());

        var players = parser.parsePlayers("{\"p1\":{\"full_name\":\"Quarter Back\",\"position\":\"QB\",\"team\":\"CHI\"},\"p2\":{\"first_name\":\"Wide\",\"last_name\":\"Receiver\",\"position\":\"WR\",\"team\":null}}");
        assertEquals("Quarter Back", players.get("p1").displayName());
        assertEquals("Wide Receiver", players.get("p2").displayName());
        assertNull(players.get("p2").nflTeam());
    }
}
