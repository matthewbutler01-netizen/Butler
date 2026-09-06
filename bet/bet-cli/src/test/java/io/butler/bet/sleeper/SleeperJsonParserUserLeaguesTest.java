package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SleeperJsonParserUserLeaguesTest {
    @Test
    void parsesUserLeagueCollectionWithLeagueMetadata() throws Exception {
        String json = """
            [
              {
                "league_id":"200",
                "name":"Second",
                "season":"2025",
                "roster_positions":["QB","RB","WR","BN"],
                "settings":{"type":2,"draft_rounds":4},
                "scoring_settings":{"pass_td":4,"rec":1}
              },
              {
                "league_id":"100",
                "name":"First",
                "season":"2025",
                "roster_positions":["QB","WR","FLEX","BN"],
                "settings":{"type":0,"draft_rounds":15},
                "scoring_settings":{"pass_td":6}
              }
            ]
            """;

        var leagues = new SleeperJsonParser().parseLeagues(json);

        assertEquals(List.of("200", "100"), leagues.stream().map(SleeperJsonParser.SleeperLeague::id).toList());
        assertEquals(2025, leagues.get(0).season());
        assertEquals(2, leagues.get(0).leagueType());
        assertEquals(4, leagues.get(0).draftRounds());
        assertEquals(List.of("QB", "RB", "WR", "BN"), leagues.get(0).rosterPositions());
        assertEquals(1.0, leagues.get(0).scoringSettings().get("rec"));
    }
}
