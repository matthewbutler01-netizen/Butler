package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SleeperMatchupParserTest {
    private final SleeperMatchupParser parser = new SleeperMatchupParser();

    @Test
    void preservesWeeklyPlayersOrderedStartersAndExactPairing() throws Exception {
        var matchups = parser.parse("""
            [
              {
                "roster_id": 3,
                "players": ["p3", "p1", "p2"],
                "starters": ["p1", "0", "p3"],
                "matchup_id": 7,
                "points": 101.25
              },
              {
                "roster_id": 8,
                "players": ["p8"],
                "starters": ["p8"],
                "matchup_id": 7,
                "points": 99.50
              }
            ]
            """);

        assertEquals(2, matchups.size());
        assertEquals(3, matchups.getFirst().rosterId());
        assertEquals(7, matchups.getFirst().matchupId());
        assertEquals(List.of("p3", "p1", "p2"), matchups.getFirst().playerIds());
        assertEquals(List.of("p1", "0", "p3"), matchups.getFirst().starterIds());
        assertEquals(8, matchups.get(1).rosterId());
        assertEquals(7, matchups.get(1).matchupId());
    }

    @Test
    void missingPlayerOrStarterArraysRemainExplicitlyEmpty() throws Exception {
        var matchups = parser.parse("""
            [
              {"roster_id":1,"matchup_id":4},
              {"roster_id":2,"matchup_id":4}
            ]
            """);
        assertEquals(List.of(), matchups.getFirst().playerIds());
        assertEquals(List.of(), matchups.getFirst().starterIds());
    }

    @Test
    void rejectsMalformedTopLevelRosterMatchupAndArrayFields() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{}"));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse("[{\"roster_id\":0,\"matchup_id\":1}]"));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse("[{\"roster_id\":1}]"));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse("[{\"roster_id\":1,\"matchup_id\":1,\"players\":\"p1\"}]"));
        assertThrows(IllegalArgumentException.class,
            () -> parser.parse("[{\"roster_id\":1,\"matchup_id\":1,\"starters\":[\"\"]}]"));
    }

    @Test
    void rejectsDuplicateRosterAndUnpairedMatchupEvidence() {
        assertThrows(IllegalStateException.class, () -> parser.parse("""
            [
              {"roster_id":1,"matchup_id":5},
              {"roster_id":1,"matchup_id":5}
            ]
            """));
        assertThrows(IllegalStateException.class, () -> parser.parse("""
            [
              {"roster_id":1,"matchup_id":5},
              {"roster_id":2,"matchup_id":6}
            ]
            """));
        assertThrows(IllegalStateException.class, () -> parser.parse("""
            [
              {"roster_id":1,"matchup_id":5},
              {"roster_id":2,"matchup_id":5},
              {"roster_id":3,"matchup_id":5}
            ]
            """));
    }
}
