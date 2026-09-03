package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SleeperRosterPerformanceParsingTest {
    @Test
    void parsesRecordAndFractionalPointsFromRosterSettings() throws Exception {
        String json = """
            [{
              "roster_id": 1,
              "owner_id": "u1",
              "players": ["p1"],
              "starters": ["p1"],
              "reserve": [],
              "taxi": [],
              "settings": {
                "wins": 6,
                "losses": 3,
                "ties": 1,
                "fpts": 1012,
                "fpts_decimal": 45,
                "fpts_against": 930,
                "fpts_against_decimal": 7
              }
            }]
            """;

        var roster = new SleeperJsonParser().parseRosters(json).get(0);
        assertEquals(6, roster.wins());
        assertEquals(3, roster.losses());
        assertEquals(1, roster.ties());
        assertEquals(1012.45, roster.pointsFor(), 0.000001);
        assertEquals(930.07, roster.pointsAgainst(), 0.000001);
    }

    @Test
    void missingSettingsProduceValidZeroGameSnapshot() throws Exception {
        var roster = new SleeperJsonParser().parseRosters("[{\"roster_id\":1}]").get(0);
        assertEquals(0, roster.wins());
        assertEquals(0, roster.losses());
        assertEquals(0.0, roster.pointsFor());
    }

    @Test
    void rejectsOutOfRangeSleeperPointDecimals() {
        String json = "[{\"roster_id\":1,\"settings\":{\"fpts\":100,\"fpts_decimal\":125}}]";
        assertThrows(IllegalArgumentException.class, () -> new SleeperJsonParser().parseRosters(json));
    }
}
