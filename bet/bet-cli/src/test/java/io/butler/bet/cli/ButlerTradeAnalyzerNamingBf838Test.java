package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeAnalyzerNamingBf838Test {

    @Test
    void managerFacingRuntimeSourcesUseTradeAnalyzerName() throws Exception {
        for (String path : new String[]{
                "scripts/butler-app-shell.ps1",
                "scripts/butler-dashboard.ps1",
                "scripts/butler-app-shell-core.ps1",
                "scripts/butler-app-shell-core-single.ps1",
                "scripts/butler-dashboard-bf807-priority-ordering-transform.ps1",
                "scripts/butler-dashboard-bf813-priority-evidence-transform.ps1",
                "scripts/butler-dashboard-bf814-priority-decision-record-transform.ps1",
                "scripts/butler-trade-lab-host.ps1",
                "scripts/butler-trade-lab.ps1",
                "scripts/butler-decision-history.ps1"
        }) {
            String text = source(path);
            assertFalse(text.contains("Trade Lab"),
                    path + " must not expose the retired manager-facing Trade Lab name");
        }
    }

    @Test
    void startupAndDashboardTradeGuidanceUseCanonicalName() throws Exception {
        String shell = source("scripts/butler-app-shell.ps1");
        String priorities = source("scripts/butler-dashboard-bf807-priority-ordering-transform.ps1");
        String evidence = source("scripts/butler-dashboard-bf813-priority-evidence-transform.ps1");
        String record = source("scripts/butler-dashboard-bf814-priority-decision-record-transform.ps1");

        assertTrue(shell.contains("Trade Analyzer: http://127.0.0.1:$Port/trade"));
        assertTrue(priorities.contains("Open Trade Analyzer"));
        assertTrue(evidence.contains("Open Trade Analyzer with a specific deal or target"));
        assertTrue(record.contains("Open Trade Analyzer with a deal or target"));
        assertTrue(record.contains(">Open Trade Analyzer</a>"));
    }

    @Test
    void bf832KeepsLegacyTranslationOnlyAsCompatibilityShim() throws Exception {
        String transform = source("scripts/butler-dashboard-bf832-command-center-visual-transform.ps1");

        assertTrue(transform.contains("$dashboardBlock.Replace('Trade Lab', 'Trade Analyzer')"));
        assertTrue(transform.contains("href=\"/trade\">Trade Lab</a>"));
        assertTrue(transform.contains("href=\"/trade\">Trade Analyzer</a>"));
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
        throw new IOException("BF-838 test could not locate " + relativePath);
    }
}
