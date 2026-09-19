package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardPreSnapshotTimingBf860Test {

    @Test
    void transformMeasuresPreSnapshotHelperBuckets() throws Exception {
        String transform = source("scripts/butler-bf857-inner-core-timing-transform.ps1");

        assertTrue(transform.contains("dashboard_base_fields_ms"));
        assertTrue(transform.contains("dashboard_shell_ms"));
        assertTrue(transform.contains("dashboard_refresh_block_ms"));
        assertTrue(transform.contains("dashboard_next_plan_block_ms"));
        assertTrue(transform.contains("dashboard_explanation_lookup_ms"));
        assertTrue(transform.contains("dashboard_presnapshot_tail_ms"));

        assertTrue(transform.contains("$css = Get-SharedCss"));
        assertTrue(transform.contains("$refreshPlan = Get-GovernedManualRefreshPlanView -Summary $Summary"));
        assertTrue(transform.contains("$nextDecisionPlan = Get-GovernedNextDecisionPlanView -Summary $Summary"));
        assertTrue(transform.contains("$explanation = Get-GovernedExplanationView -Summary $Summary"));
        assertTrue(transform.contains("dashboard_parse_base_ms = Get-Bf857ElapsedMs"));
    }

    @Test
    void runnerAccountsPreSnapshotAggregate() throws Exception {
        String runner = source("scripts/butler-dashboard-inner-core-timing-diagnostic.ps1");

        assertTrue(runner.contains("'dashboard_base_fields_ms'"));
        assertTrue(runner.contains("'dashboard_shell_ms'"));
        assertTrue(runner.contains("'dashboard_refresh_block_ms'"));
        assertTrue(runner.contains("'dashboard_next_plan_block_ms'"));
        assertTrue(runner.contains("'dashboard_explanation_lookup_ms'"));
        assertTrue(runner.contains("'dashboard_presnapshot_tail_ms'"));
        assertTrue(runner.contains("DashboardPreSnapshotResidualMs"));
        assertTrue(runner.contains("pre-snapshot fields="));
        assertTrue(runner.contains("BF-860 RESULT: COMPLETE"));
    }

    @Test
    void diagnosticRemainsReadOnly() throws Exception {
        String runner = source("scripts/butler-dashboard-inner-core-timing-diagnostic.ps1");

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
        throw new IOException("BF-860 test could not locate " + relativePath);
    }
}
