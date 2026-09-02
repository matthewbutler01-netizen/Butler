package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerMainRosterSlotContextTest {
    @Test
    void recognizesEverySupportedRosterSlotContextArgumentForm() {
        assertTrue(ButlerMain.isSupportedLeagueRosterSlotContext(new String[]{"league", "roster-slot-context", "league-id"}));
        assertTrue(ButlerMain.isSupportedLeagueRosterSlotContext(new String[]{"league", "roster-slot-context", "league-id", "source"}));
        assertTrue(ButlerMain.isSupportedLeagueRosterSlotContext(new String[]{"league", "roster-slot-context", "league-id", "--minimum-as-of", "2026-09-01"}));
        assertTrue(ButlerMain.isSupportedLeagueRosterSlotContext(new String[]{"league", "roster-slot-context", "league-id", "source", "--minimum-as-of", "2026-09-01"}));
        assertFalse(ButlerMain.isSupportedLeagueRosterSlotContext(new String[]{"league", "roster-slot-context"}));
        assertFalse(ButlerMain.isSupportedLeagueRosterSlotContext(new String[]{"league", "roster-slot-context", "league-id", "--wrong-flag", "2026-09-01"}));
    }

    @Test
    void advertisesRosterSlotContextSyntax() {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            ButlerMain.printIntelligenceUsage();
        } finally {
            System.setOut(original);
        }
        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains(
            "butler league roster-slot-context <league-id> [source] [--minimum-as-of YYYY-MM-DD]"));
    }
}
