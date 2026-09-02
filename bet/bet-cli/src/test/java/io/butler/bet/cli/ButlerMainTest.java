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
    void recognizesEverySupportedLeagueOverviewArgumentForm() {
        assertTrue(ButlerMain.isSupportedLeagueOverview(new String[]{"league", "overview", "league-id"}));
        assertTrue(ButlerMain.isSupportedLeagueOverview(new String[]{"league", "overview", "league-id", "source"}));
        assertTrue(ButlerMain.isSupportedLeagueOverview(new String[]{
            "league", "overview", "league-id", "--minimum-as-of", "2026-09-01"}));
        assertTrue(ButlerMain.isSupportedLeagueOverview(new String[]{
            "league", "overview", "league-id", "source", "--minimum-as-of", "2026-09-01"}));

        assertFalse(ButlerMain.isSupportedLeagueOverview(new String[]{"league", "overview"}));
        assertFalse(ButlerMain.isSupportedLeagueOverview(new String[]{
            "league", "overview", "league-id", "--wrong-flag", "2026-09-01"}));
    }

    @Test
    void recognizesEverySupportedLeagueTeamContextArgumentForm() {
        assertTrue(ButlerMain.isSupportedLeagueTeamContext(new String[]{"league", "team-context", "league-id"}));
        assertTrue(ButlerMain.isSupportedLeagueTeamContext(new String[]{"league", "team-context", "league-id", "source"}));
        assertTrue(ButlerMain.isSupportedLeagueTeamContext(new String[]{
            "league", "team-context", "league-id", "--minimum-as-of", "2026-09-01"}));
        assertTrue(ButlerMain.isSupportedLeagueTeamContext(new String[]{
            "league", "team-context", "league-id", "source", "--minimum-as-of", "2026-09-01"}));

        assertFalse(ButlerMain.isSupportedLeagueTeamContext(new String[]{"league", "team-context"}));
        assertFalse(ButlerMain.isSupportedLeagueTeamContext(new String[]{
            "league", "team-context", "league-id", "--wrong-flag", "2026-09-01"}));
    }

    @Test
    void recognizesEverySupportedLeagueDecisionReadinessArgumentForm() {
        assertTrue(ButlerMain.isSupportedLeagueDecisionReadiness(new String[]{"league", "decision-readiness", "league-id"}));
        assertTrue(ButlerMain.isSupportedLeagueDecisionReadiness(new String[]{"league", "decision-readiness", "league-id", "source"}));
        assertTrue(ButlerMain.isSupportedLeagueDecisionReadiness(new String[]{
            "league", "decision-readiness", "league-id", "--minimum-as-of", "2026-09-01"}));
        assertTrue(ButlerMain.isSupportedLeagueDecisionReadiness(new String[]{
            "league", "decision-readiness", "league-id", "source", "--minimum-as-of", "2026-09-01"}));

        assertFalse(ButlerMain.isSupportedLeagueDecisionReadiness(new String[]{"league", "decision-readiness"}));
        assertFalse(ButlerMain.isSupportedLeagueDecisionReadiness(new String[]{
            "league", "decision-readiness", "league-id", "--wrong-flag", "2026-09-01"}));
    }

    @Test
    void recognizesEverySupportedLeaguePositionContextArgumentForm() {
        assertTrue(ButlerMain.isSupportedLeaguePositionContext(new String[]{"league", "position-context", "league-id"}));
        assertTrue(ButlerMain.isSupportedLeaguePositionContext(new String[]{"league", "position-context", "league-id", "source"}));
        assertTrue(ButlerMain.isSupportedLeaguePositionContext(new String[]{
            "league", "position-context", "league-id", "--minimum-as-of", "2026-09-01"}));
        assertTrue(ButlerMain.isSupportedLeaguePositionContext(new String[]{
            "league", "position-context", "league-id", "source", "--minimum-as-of", "2026-09-01"}));

        assertFalse(ButlerMain.isSupportedLeaguePositionContext(new String[]{"league", "position-context"}));
        assertFalse(ButlerMain.isSupportedLeaguePositionContext(new String[]{
            "league", "position-context", "league-id", "--wrong-flag", "2026-09-01"}));
    }

    @Test
    void recognizesEverySupportedLeagueDraftCapitalArgumentForm() {
        assertTrue(ButlerMain.isSupportedLeagueDraftCapital(new String[]{"league", "draft-capital", "league-id"}));
        assertTrue(ButlerMain.isSupportedLeagueDraftCapital(new String[]{"league", "draft-capital", "league-id", "source"}));
        assertTrue(ButlerMain.isSupportedLeagueDraftCapital(new String[]{
            "league", "draft-capital", "league-id", "--minimum-as-of", "2026-09-01"}));
        assertTrue(ButlerMain.isSupportedLeagueDraftCapital(new String[]{
            "league", "draft-capital", "league-id", "source", "--minimum-as-of", "2026-09-01"}));

        assertFalse(ButlerMain.isSupportedLeagueDraftCapital(new String[]{"league", "draft-capital"}));
        assertFalse(ButlerMain.isSupportedLeagueDraftCapital(new String[]{
            "league", "draft-capital", "league-id", "--wrong-flag", "2026-09-01"}));
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

    @Test
    void advertisesLeagueIntelligenceSyntax() {
        String output = capture(ButlerMain::printIntelligenceUsage);
        assertTrue(output.contains("butler league overview <league-id> [source] [--minimum-as-of YYYY-MM-DD]"));
        assertTrue(output.contains("butler league team-context <league-id> [source] [--minimum-as-of YYYY-MM-DD]"));
        assertTrue(output.contains("butler league decision-readiness <league-id> [source] [--minimum-as-of YYYY-MM-DD]"));
        assertTrue(output.contains("butler league position-context <league-id> [source] [--minimum-as-of YYYY-MM-DD]"));
        assertTrue(output.contains("butler league draft-capital <league-id> [source] [--minimum-as-of YYYY-MM-DD]"));
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
