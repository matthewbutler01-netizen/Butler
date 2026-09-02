package io.butler.bet.cli;

import io.butler.bet.intelligence.LeagueActionPlanAnalyzer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerMainTest {
    @Test
    void recognizesEverySupportedLeagueStatusArgumentForm() {
        assertTrue(ButlerMain.isSupportedLeagueStatus(new String[]{"league", "status", "league-id"}));
        assertTrue(ButlerMain.isSupportedLeagueStatus(new String[]{"league", "status", "league-id", "source"}));
        assertTrue(ButlerMain.isSupportedLeagueStatus(new String[]{
            "league", "status", "league-id", "--minimum-as-of", "2026-09-01"}));
        assertTrue(ButlerMain.isSupportedLeagueStatus(new String[]{
            "league", "status", "league-id", "source", "--minimum-as-of", "2026-09-01"}));

        assertFalse(ButlerMain.isSupportedLeagueStatus(new String[]{"league", "list"}));
        assertFalse(ButlerMain.isSupportedLeagueStatus(new String[]{"league", "status"}));
        assertFalse(ButlerMain.isSupportedLeagueStatus(new String[]{
            "league", "status", "league-id", "--wrong-flag", "2026-09-01"}));
    }

    @Test
    void printsRequiredAndOptionalActionsWithDeterministicCommands() {
        var actions = List.of(
            new LeagueActionPlanAnalyzer.Action(
                1,
                LeagueActionPlanAnalyzer.ActionKind.REFRESH_LEAGUE_VALUES,
                true,
                "Refresh league values.",
                "butler sleeper sync-all 123"),
            new LeagueActionPlanAnalyzer.Action(
                2,
                LeagueActionPlanAnalyzer.ActionKind.CAPTURE_FUTURE_VALUE_SNAPSHOT,
                false,
                "Capture another value snapshot.",
                "butler player value-refresh dynastyprocess"));

        String output = capture(() -> ButlerMain.printLeagueActions(actions));

        assertTrue(output.contains("Next actions:"));
        assertTrue(output.contains("1. REQUIRED  REFRESH_LEAGUE_VALUES  Refresh league values."));
        assertTrue(output.contains("butler sleeper sync-all 123"));
        assertTrue(output.contains("2. OPTIONAL  CAPTURE_FUTURE_VALUE_SNAPSHOT  Capture another value snapshot."));
        assertTrue(output.contains("butler player value-refresh dynastyprocess"));
    }

    @Test
    void printsExplicitNoActionState() {
        String output = capture(() -> ButlerMain.printLeagueActions(List.of()));
        assertTrue(output.contains("Next actions: none."));
    }

    private static String capture(Runnable runnable) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            runnable.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
