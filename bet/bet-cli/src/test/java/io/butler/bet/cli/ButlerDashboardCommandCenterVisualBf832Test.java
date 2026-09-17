package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardCommandCenterVisualBf832Test {

    @Test
    void bf814StagesDashboardVisualAlignmentAfterTheExistingDecisionPackageChainReturns() throws Exception {
        String bf814 = source("scripts/butler-dashboard-bf814-priority-decision-record-transform.ps1");

        int bf815 = bf814.indexOf("& $bf815Transform -DashboardPath $DashboardPath");
        int bf832 = bf814.indexOf("& $bf832Transform -DashboardPath $DashboardPath");

        assertTrue(bf815 >= 0, "BF-815 unified decision package must remain staged");
        assertTrue(bf832 > bf815, "BF-832 must run after BF-815/BF-816 and nested staging return");
        assertTrue(bf814.contains("butler-dashboard-bf832-command-center-visual-transform.ps1"));
    }

    @Test
    void dashboardUsesTheCurrentButlerVisualTokensWithoutRestylingOtherPages() throws Exception {
        String transform = source("scripts/butler-dashboard-bf832-command-center-visual-transform.ps1");

        for (String marker : new String[]{
                "body class=\"dashboard-page\"",
                "shell dashboard-command-center",
                "--bg:#F4F2EA",
                "--surface:#FFFFFF",
                "--surface-2:#ECE9DD",
                "--line:#D8D4C4",
                "--turf:#2E6B47",
                "--turf-deep:#1F4D33",
                "--gold:#C98A1F",
                "--ink:#16201A",
                "--muted:#5B6459",
                "--brick:#A8452F",
                "--font-display:'Teko'",
                "repeating-linear-gradient",
                "@media(prefers-color-scheme:dark)",
                ".dashboard-command-center .priority-card.primary",
                ".dashboard-command-center .evidence-grid"
        }) {
            assertTrue(transform.contains(marker), "missing BF-832 visual marker " + marker);
        }

        assertTrue(transform.contains("Scope the new visual system to the Dashboard page"));
        assertFalse(transform.contains("body{--bg:#F4F2EA"),
                "BF-832 visual variables must stay scoped to the Dashboard page");
    }

    @Test
    void dashboardUsesTradeAnalyzerPresentationNaming() throws Exception {
        String transform = source("scripts/butler-dashboard-bf832-command-center-visual-transform.ps1");

        assertTrue(transform.contains("$dashboardBlock = $dashboardBlock.Replace('Trade Lab', 'Trade Analyzer')"));
        assertTrue(transform.contains("href=\"/trade\">Trade Analyzer</a>"));
        assertTrue(transform.contains("Trade Analyzer navigation contract expected one match"));
    }

    @Test
    void visualTransformRemainsPresentationOnlyAndFailClosed() throws Exception {
        String transform = source("scripts/butler-dashboard-bf832-command-center-visual-transform.ps1");

        for (String forbidden : new String[]{
                "https://api.sleeper.app",
                "FantasyProsApiClient",
                "BUTLER_FANTASYPROS_API_KEY",
                "submitTransaction",
                "AutoFillLineupOptimizer",
                "Method = \"POST\"",
                "AttentionGroup =",
                "CURRENT_AND_ACTIONABLE"
        }) {
            assertFalse(transform.contains(forbidden), "BF-832 must not introduce decision/provider/write behavior: " + forbidden);
        }

        assertTrue(transform.contains("generated Dashboard failed PowerShell parse"));
        assertTrue(transform.contains("System.Management.Automation.Language.Parser"));
        assertTrue(transform.contains("Dashboard visual alignment introduced provider, optimizer, or write behavior"));
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
        throw new IOException("BF-832 test could not locate " + relativePath);
    }
}
