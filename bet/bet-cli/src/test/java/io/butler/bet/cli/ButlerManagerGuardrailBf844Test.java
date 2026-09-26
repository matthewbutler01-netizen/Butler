package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerManagerGuardrailBf844Test {

    @Test
    void peakLoadIncludesPassiveMatchupButNotExplicitAutofill() throws Exception {
        String load = source("scripts/butler-read-load-check.ps1");

        assertTrue(load.contains("$paths = @('/health', '/', '/team', '/matchup', '/waivers', '/league', '/trade', '/history')"));
        assertTrue(load.contains("$paths -contains '/matchup/autofill'"));
        assertTrue(load.contains("/matchup/autofill is opt-in projection work"));
        assertTrue(load.contains("$paths -contains '/refresh'"));

        String pathLine = load.lines()
            .filter(line -> line.trim().startsWith("$paths = @("))
            .findFirst()
            .orElseThrow();
        assertTrue(pathLine.contains("'/matchup'"));
        assertFalse(pathLine.contains("'/matchup/autofill'"));
        assertFalse(pathLine.contains("'/refresh'"));
    }

    @Test
    void oneCommandGuardrailCoversManagerDecisionSurfaces() throws Exception {
        String script = source("scripts/butler-manager-guardrail-acceptance.ps1");
        String cmd = source("scripts/butler-manager-guardrail-acceptance.cmd");

        for (String marker : new String[]{
                "Butler manager UX and peak-load guardrail acceptance (BF-844)",
                "Peak load: MATCHUP_INCLUDED",
                "Priority 01",
                "Your fantasy week in one view",
                "View other priorities",
                "How Butler reads this roster",
                "Review Matchup",
                "href=\"/matchup/autofill\"",
                "Weekly matchup",
                "Lineup advisor",
                "Your opponent is confirmed.",
                "Opponent not confirmed",
                "Butler waiver decision",
                "Next step",
                "Players Butler authorized for review",
                "Review authorized players",
                "Analyze a trade",
                "Choose a league opponent",
                "League: DECISION_FIRST_AND_DISCLOSURE_VERIFIED",
                "History: DECISION_FIRST_AND_DISCLOSURE_VERIFIED",
                "Gambling pressure: ABSENT_FROM_CHECKED_MANAGER_COPY",
                "Desktop parity: ALL_SEVEN_MANAGER_PAGES_VERIFIED",
                "Under-five-minute product test: PASS",
                "Working tree: CLEAN",
                "BF-844 RESULT: COMPLETE",
                "BF-534 UX GUARDRAILS: PASS"
        }) {
            assertTrue(script.contains(marker), "BF-844 live guardrail missing " + marker);
        }

        assertTrue(cmd.contains("butler-manager-guardrail-acceptance.ps1"));
    }

    @Test
    void phaseTwoStartupFailureSurfacesOwnedButlerOutput() throws Exception {
        String script = source("scripts/butler-manager-guardrail-acceptance.ps1");

        assertTrue(script.contains("$start.RedirectStandardOutput = $true"));
        assertTrue(script.contains("$start.RedirectStandardError = $true"));
        assertTrue(script.contains("Get-BoundedStartupOutput"));
        assertTrue(script.contains("startup=$diagnostic"));
    }

    @Test
    void guardrailRemainsGetOnlyAndDoesNotRequestAutofillOrRefresh() throws Exception {
        String script = source("scripts/butler-manager-guardrail-acceptance.ps1");

        assertTrue(script.contains("$request.Method = 'GET'"));
        assertTrue(script.contains("Boundary: passive GET-only manager reads; /matchup/autofill and /refresh are excluded."));
        assertFalse(script.contains("Method = 'POST'"));
        assertFalse(script.contains("'/matchup/autofill')"));
        assertFalse(script.contains("'/refresh')"));
        assertFalse(script.contains("submitTransaction"));
        assertFalse(script.contains("setFaab"));
    }

    @Test
    void runtimeCopyGuardrailChecksSpecificBettingPressurePhrases() throws Exception {
        String script = source("scripts/butler-manager-guardrail-acceptance.ps1");

        assertTrue(script.contains("function Assert-NoBettingPressure"));
        for (String phrase : new String[]{
                "sportsbook",
                "betting odds",
                "same-game parlay",
                "place a bet",
                "pick''em contest",
                "limited time offer",
                "act now",
                "jackpot",
                "deposit bonus"
        }) {
            assertTrue(script.contains(phrase), "BF-844 betting-copy guardrail missing " + phrase);
        }
    }

    @Test
    void issue534ChecksAllSevenPagesAndDesktopResponsiveContracts() throws Exception {
        String script = source("scripts/butler-manager-guardrail-acceptance.ps1");

        for (String route : new String[]{"'/'", "'/team'", "'/matchup'", "'/waivers'", "'/league'", "'/trade?load=1'", "'/history?load=1'"}) {
            assertTrue(script.contains(route), "BF-534 guardrail missing route " + route);
        }
        assertTrue(script.contains("function Assert-DesktopSurface"));
        assertTrue(script.contains("name=\"viewport\""));
        assertTrue(script.contains("grid-template-columns"));
        assertTrue(script.contains("@media(max-width:760px)"));
        assertTrue(script.contains("$productTest.Elapsed.TotalMinutes -ge 5"));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("BF-844 test could not locate " + relativePath);
    }
}
