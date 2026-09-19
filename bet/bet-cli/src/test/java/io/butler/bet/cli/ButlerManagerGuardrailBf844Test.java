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
                "Butler Command Center",
                "What matters now",
                "Your decision queue",
                "View decision details",
                "Weekly matchup",
                "Lineup advisor",
                "OPPONENT CONFIRMED",
                "Opponent not confirmed",
                "Butler waiver decision",
                "What to do now",
                "Technical and audit details",
                "Analyze a trade",
                "Choose a league opponent",
                "Gambling pressure: ABSENT_FROM_CHECKED_MANAGER_COPY",
                "Working tree: CLEAN",
                "BF-844 RESULT: COMPLETE"
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
                "pick''em contest"
        }) {
            assertTrue(script.contains(phrase), "BF-844 betting-copy guardrail missing " + phrase);
        }
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
