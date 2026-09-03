package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueAgeOutlookCliTest {
    @Test
    void parsesExactCommand() {
        String[] args = {"league", "age-outlook", "league-1", "2026"};

        assertTrue(ButlerLeagueAgeOutlookCli.isCommand(args));
        var options = ButlerLeagueAgeOutlookCli.parse(args);

        assertEquals("league-1", options.leagueId());
        assertEquals(2026, options.season());
    }

    @Test
    void rejectsMalformedInput() {
        assertFalse(ButlerLeagueAgeOutlookCli.isCommand(new String[]{"league", "aging-model-evidence", "l1", "2026"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueAgeOutlookCli.parse(new String[]{"league", "age-outlook", "l1"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueAgeOutlookCli.parse(new String[]{"league", "age-outlook", "l1", "twenty"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerLeagueAgeOutlookCli.parse(new String[]{"league", "age-outlook", "l1", "2101"}));
    }
}
