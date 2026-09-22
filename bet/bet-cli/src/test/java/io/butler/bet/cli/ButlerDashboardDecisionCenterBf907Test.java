package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardDecisionCenterBf907Test {

    @Test
    void dashboardAddsButlerSpecificWeekAtAGlance() throws Exception {
        String transform = source("scripts/butler-dashboard-bf907-decision-center-transform.ps1");

        assertTrue(transform.contains("BF-907 Decision Center"));
        assertTrue(transform.contains("Week at a glance"));
        assertTrue(transform.contains("Your fantasy week in one view"));
        assertTrue(transform.contains("Kind = \"Lineup\""));
        assertTrue(transform.contains("Kind = \"Waivers\""));
        assertTrue(transform.contains("Kind = \"Trade\""));
        assertTrue(transform.contains("$managerAttentionCount"));
        assertTrue(transform.contains("$bf907WeekGlanceHtml"));
    }

    @Test
    void dashboardKeepsUsefulSecondaryToolsOutsidePrimaryNav() throws Exception {
        String transform = source("scripts/butler-dashboard-bf907-decision-center-transform.ps1");

        assertTrue(transform.contains("href=\"/matchup\">Weekly Matchup</a>"));
        assertTrue(transform.contains("href=\"/players\">Player Search</a>"));
        assertTrue(transform.contains("href=\"/compare\">Player Compare</a>"));
        assertTrue(transform.contains("href=\"/league\">League</a>"));
        assertFalse(transform.contains("Get-AppNav -Active 'compare'"));
        assertFalse(transform.contains("Player Compare</a></nav>"));
    }

    @Test
    void decisionCenterRunsAfterVisualUnificationAndBeforeDiagnostics() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf899 = staging.indexOf("& $bf899Transform -DashboardPath $DashboardPath");
        int bf907 = staging.indexOf("& $bf907Transform -DashboardPath $DashboardPath");
        int bf857 = staging.indexOf("# BF-857: diagnostic-only inner-core timing runs last");

        assertTrue(bf899 >= 0, "BF-899 staging marker missing");
        assertTrue(bf907 > bf899, "BF-907 must run after BF-899 visual unification");
        assertTrue(bf857 > bf907, "BF-857 diagnostics must remain after BF-907 final Dashboard presentation");
        int bf907Gate = staging.lastIndexOf("if (Test-Path -LiteralPath $stagedCore -PathType Leaf)", bf907);
        assertTrue(bf907Gate >= 0 && bf907Gate < bf907,
            "BF-907 must remain gated to the full-app staged-core manager path");
    }

    @Test
    void decisionCenterRemainsPresentationOnly() throws Exception {
        String transform = source("scripts/butler-dashboard-bf907-decision-center-transform.ps1");

        int safetyScan = transform.indexOf("$bf907InstalledSurface -match");
        assertTrue(safetyScan > 0, "BF-907 safety scan must remain present");
        String operational = transform.substring(0, safetyScan);

        for (String forbidden : new String[] {
            "Invoke-RestMethod",
            "Invoke-WebRequest",
            "Invoke-ButlerReadOnly",
            "Invoke-Bf742DashboardWorkerRead",
            "https://api.sleeper.app",
            "Method = \"POST\"",
            "submitTransaction",
            "setFaab",
            "AutoFillLineupOptimizer"
        }) {
            assertFalse(operational.contains(forbidden),
                "BF-907 operational transform introduced read/write marker " + forbidden);
        }

        assertTrue(transform.contains("generated Dashboard failed PowerShell parse"));
        assertTrue(transform.contains("$bf907InstalledSurface = $bf907Css + [Environment]::NewLine + $bf907Prelude"));
        assertTrue(transform.contains("Decision Center presentation introduced an operational read/write marker"));
    }

    @Test
    void fullDecisionQueueAndProgressiveDisclosureRemainPresent() throws Exception {
        String transform = source("scripts/butler-dashboard-bf907-decision-center-transform.ps1");

        assertTrue(transform.contains("<section class=\"panel manager-queue\">"));
        assertTrue(transform.contains("Full decision queue"));
        assertFalse(transform.contains("details id=\"decision-details\" class=\"proof-mode\"></details>"));
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
        throw new IOException("BF-907 test could not locate " + relativePath);
    }
}
