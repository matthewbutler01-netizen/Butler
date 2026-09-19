package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardVarianceDiagnosticBf868Test {

    @Test
    void runnerDefaultsToSevenBoundedWarmSamples() throws Exception {
        String runner = source("scripts/butler-dashboard-inner-core-timing-diagnostic.ps1");

        assertTrue(runner.contains("[ValidateRange(5, 15)]"));
        assertTrue(runner.contains("[int]$SampleCount = 7"));
        assertTrue(runner.contains("for ($i = 1; $i -le $SampleCount; $i++)"));
        assertTrue(runner.contains("Start-Sleep -Seconds 6"));
        assertTrue(runner.contains("Samples: $SampleCount warm miss-path requests"));
        assertTrue(runner.contains("BF868_SAMPLE_COUNT={0}"));
    }

    @Test
    void runnerReportsInterpolatedP90AndMaxForKeyStages() throws Exception {
        String runner = source("scripts/butler-dashboard-inner-core-timing-diagnostic.ps1");

        assertTrue(runner.contains("function Get-Percentile"));
        assertTrue(runner.contains("$position = ([double]($values.Count - 1)) * $Percentile"));
        assertTrue(runner.contains("$weight = $position - [double]$lower"));
        assertTrue(runner.contains("function Get-Maximum"));
        assertTrue(runner.contains("Warm miss-path variance (p90 / max)"));

        for (String property : new String[] {
            "CoreProxyMs",
            "PoolBackendMs",
            "PreservedDashboardMs",
            "DashboardSummaryMs",
            "DashboardHtmlMs",
            "OuterToPoolResidualMs",
            "PoolToPreservedResidualMs",
            "DashboardOtherResidualMs"
        }) {
            assertTrue(runner.contains("-Property '" + property + "' -Percentile 0.90"));
            assertTrue(runner.contains("-Property '" + property + "'"));
        }
    }

    @Test
    void existingP50AndReadOnlyBoundaryRemainIntact() throws Exception {
        String runner = source("scripts/butler-dashboard-inner-core-timing-diagnostic.ps1");

        assertTrue(runner.contains("Warm miss-path p50"));
        assertTrue(runner.contains("Get-Median -Items $results -Property 'CoreProxyMs'"));
        assertTrue(runner.contains("BF-860 RESULT: COMPLETE"));
        assertTrue(runner.contains("BF-868 RESULT: COMPLETE"));
        assertTrue(runner.contains("read-only Dashboard GET only"));
        assertTrue(runner.contains("BF-856/BF-857/BF-859/BF-860/BF-868/BF-869 timing is diagnostic-only"));

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
        throw new IOException("BF-868 test could not locate " + relativePath);
    }
}
