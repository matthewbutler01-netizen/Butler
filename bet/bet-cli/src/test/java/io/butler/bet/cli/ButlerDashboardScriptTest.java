package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardScriptTest {

    @Test
    void bindsOnlyToExplicitIpv4LoopbackOnDeterministicDefaultPort() throws Exception {
        String script = script();

        assertTrue(script.contains("[int]$Port = 8080"));
        assertTrue(script.contains("[System.Net.IPAddress]::Parse(\"127.0.0.1\")"));
        assertTrue(script.contains("[System.Net.Sockets.TcpListener]::new($loopback, $Port)"));
        assertTrue(script.contains("http://127.0.0.1:$Port/"));
        assertFalse(script.contains("IPAddress]::Any"));
        assertFalse(script.contains("0.0.0.0"));
    }

    @Test
    void invokesOnlyTheExistingReadOnlyGovernedSummary() throws Exception {
        String script = script();

        assertTrue(script.contains(":bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverSnapshotSync"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverMarketAttentionSync"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverProductionHydration"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverAvailabilitySync"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverCurrentWeekStatSync"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverTargetRosterProductionHydration"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverFinalRecommendationBundle"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverRecommendationAuditCapture"));
        assertFalse(script.contains("sleeper-live-waiver-next-decision-cycle.ps1"));
    }

    @Test
    void escapesDynamicValuesAndPinsBrowserSecurityHeaders() throws Exception {
        String script = script();

        assertTrue(script.contains("[System.Net.WebUtility]::HtmlEncode"));
        assertTrue(script.contains("Cache-Control: no-store"));
        assertTrue(script.contains("X-Content-Type-Options: nosniff"));
        assertTrue(script.contains("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'"));
        assertFalse(script.contains("<script"));
        assertFalse(script.contains("https://"));
    }

    @Test
    void rendersCoreGovernedDecisionAndSafetyFields() throws Exception {
        String script = script();

        assertTrue(script.contains("Current governed recommendation"));
        assertTrue(script.contains("ADD"));
        assertTrue(script.contains("DROP"));
        assertTrue(script.contains("BF-629 live actionability"));
        assertTrue(script.contains("BF-631 evidence lineage"));
        assertTrue(script.contains("BF-633 age telemetry"));
        assertTrue(script.contains("BF-635 warning threshold"));
        assertTrue(script.contains("Immutable audit"));
        assertTrue(script.contains("READ-ONLY FOUNDATION"));
    }

    @Test
    void healthEndpointIsLocalAndSummaryNativeExitCodeRemainsAuthoritative() throws Exception {
        String script = script();

        assertTrue(script.contains("$path -eq \"/health\""));
        assertTrue(script.contains("{\"status\":\"ok\",\"service\":\"butler-dashboard\",\"bind\":\"127.0.0.1\"}"));
        assertTrue(script.contains("$ErrorActionPreference = \"Continue\""));
        assertTrue(script.contains("$exitCode = $LASTEXITCODE"));
        assertTrue(script.contains("if ($exitCode -ne 0)"));
        assertTrue(script.contains("$ErrorActionPreference = $previousPreference"));
    }

    private static String script() throws IOException {
        return Files.readString(locateScript());
    }

    private static Path locateScript() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && current != null; depth++) {
            Path candidate = current.resolve("scripts/butler-dashboard.ps1");
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("BF-643 test could not locate scripts/butler-dashboard.ps1");
    }
}
