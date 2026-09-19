package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardHtmlSubstageTimingBf859Test {

    @Test
    void transformMeasuresFinalDashboardRendererSubstages() throws Exception {
        String transform = source("scripts/butler-bf857-inner-core-timing-transform.ps1");

        assertTrue(transform.contains("dashboard_parse_base_ms"));
        assertTrue(transform.contains("dashboard_snapshot_ms"));
        assertTrue(transform.contains("dashboard_priority_ms"));
        assertTrue(transform.contains("dashboard_decision_ms"));
        assertTrue(transform.contains("dashboard_manager_ms"));
        assertTrue(transform.contains("dashboard_materialize_ms"));

        assertTrue(transform.contains("Get-Bf809AutoFillSnapshot -LeagueKey"));
        assertTrue(transform.contains("$priorityQueueHtml = $priorityCardList -join"));
        assertTrue(transform.contains("# BF-819 is presentation-only."));
        assertTrue(transform.contains("$bf859HtmlResult = @\""));
        assertTrue(transform.contains("return $bf859HtmlResult"));
    }

    @Test
    void runnerRequiresAndReportsAllHtmlSubstages() throws Exception {
        String runner = source("scripts/butler-dashboard-inner-core-timing-diagnostic.ps1");

        assertTrue(runner.contains("'dashboard_parse_base_ms'"));
        assertTrue(runner.contains("'dashboard_snapshot_ms'"));
        assertTrue(runner.contains("'dashboard_priority_ms'"));
        assertTrue(runner.contains("'dashboard_decision_ms'"));
        assertTrue(runner.contains("'dashboard_manager_ms'"));
        assertTrue(runner.contains("'dashboard_materialize_ms'"));
        assertTrue(runner.contains("html-stages parse="));
        assertTrue(runner.contains("DashboardHtmlResidualMs"));
        assertTrue(runner.contains("BF-859 RESULT: COMPLETE"));
    }

    @Test
    void diagnosticRemainsReadOnlyAndOptIn() throws Exception {
        String transform = source("scripts/butler-bf857-inner-core-timing-transform.ps1");
        String runner = source("scripts/butler-dashboard-inner-core-timing-diagnostic.ps1");

        assertTrue(transform.contains("BUTLER_APP_BF857_CORE_TIMING -cne '1'"));
        assertTrue(runner.contains("$env:BUTLER_APP_BF857_CORE_TIMING = '1'"));
        assertFalse(runner.contains("'/refresh'"));
        assertFalse(runner.contains("Method = 'POST'"));
        assertFalse(runner.contains("submitTransaction"));
        assertFalse(runner.contains("setFaab"));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-859 test could not locate " + relativePath);
    }
}
