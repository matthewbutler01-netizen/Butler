package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SleeperMatchupParserTest {
    private final SleeperMatchupParser parser = new SleeperMatchupParser();

    @Test
    void preservesWeeklyPlayersAndOrderedStartersLiterally() throws Exception {
        var matchups = parser.parse("""
            [
              {
                "roster_id": 3,
                "players": ["p3", "p1", "p2"],
                "starters": ["p1", "0", "p3"],
                "matchup_id": 7,
                "points": 101.25
              }
            ]
            """);

        assertEquals(1, matchups.size());
        assertEquals(3, matchups.getFirst().rosterId());
        assertEquals(List.of("p3", "p1", "p2"), matchups.getFirst().playerIds());
        assertEquals(List.of("p1", "0", "p3"), matchups.getFirst().starterIds());
    }

    @Test
    void missingPlayerOrStarterArraysRemainExplicitlyEmpty() throws Exception {
        var matchups = parser.parse("[{\"roster_id\":1}]");
        assertEquals(List.of(), matchups.getFirst().playerIds());
        assertEquals(List.of(), matchups.getFirst().starterIds());
    }

    @Test
    void rejectsMalformedTopLevelRosterIdAndArrayFields() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{}"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("[{\"roster_id\":0}]"));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse("[{\"roster_id\":1,\"players\":\"p1\"}]"));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse("[{\"roster_id\":1,\"starters\":[\"\"]}]"));
    }
}
