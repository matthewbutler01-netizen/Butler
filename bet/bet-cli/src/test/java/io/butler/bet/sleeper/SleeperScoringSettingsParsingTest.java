package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperScoringSettingsParsingTest {
    private final SleeperJsonParser parser = new SleeperJsonParser();

    @Test
    void parsesSignedAndDecimalScoringSettingsWithoutInterpretation() throws Exception {
        var league = parser.parseLeague("""
            {
              "league_id":"L1",
              "name":"League",
              "season":"2026",
              "settings":{"type":2,"draft_rounds":4},
              "scoring_settings":{
                "pass_td":6,
                "pass_int":-2,
                "rec":0.5,
                "rush_yd":0.1,
                "bonus_rec_te":"0.25"
              }
            }
            """);

        assertEquals(Map.of(
            "pass_td", 6.0,
            "pass_int", -2.0,
            "rec", 0.5,
            "rush_yd", 0.1,
            "bonus_rec_te", 0.25), league.scoringSettings());
    }

    @Test
    void missingScoringSettingsRemainExplicitlyEmpty() throws Exception {
        var league = parser.parseLeague("{\"league_id\":\"L1\",\"name\":\"League\"}");
        assertTrue(league.scoringSettings().isEmpty());
    }

    @Test
    void malformedScoringSettingFailsInsteadOfBeingSilentlyDropped() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseLeague("""
            {
              "league_id":"L1",
              "name":"League",
              "scoring_settings":{"pass_td":"not-a-number"}
            }
            """));
    }
}
