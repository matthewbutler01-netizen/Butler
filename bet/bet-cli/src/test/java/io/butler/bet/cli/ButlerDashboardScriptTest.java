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
    void rendersHumanReadableRecommendationBeforeTechnicalCodes() throws Exception {
        String script = script();

        assertTrue(script.contains("Current Butler recommendation"));
        assertTrue(script.contains("Ready to act"));
        assertTrue(script.contains("What to do"));
        assertTrue(script.contains("player-name"));
        assertTrue(script.contains("player-meta"));
        assertTrue(script.contains("Sleeper ID"));
        assertTrue(script.contains("Butler verified the decision"));
        assertTrue(script.contains("Live roster check passed"));
        assertTrue(script.contains("Evidence lineage verified"));
        assertTrue(script.contains("Warning boundary:"));
        assertTrue(script.contains("Technical details"));
        assertTrue(script.contains("Decision state:"));
        assertTrue(script.contains("Audit ID:"));
        assertTrue(script.contains("READ ONLY."));
    }

    @Test
    void formatsEvidenceAgeWithoutChangingSixHourPolicy() throws Exception {
        String script = script();

        assertTrue(script.contains("function Format-Age"));
        assertTrue(script.contains("sec ago"));
        assertTrue(script.contains("min ago"));
        assertTrue(script.contains("hr ago"));
        assertTrue(script.contains("hr $minutes min ago"));
        assertTrue(script.contains("$threshold = 21600L"));
        assertTrue(script.contains("$thresholdHours = [math]::Round($threshold / 3600, 1)"));
        assertTrue(script.contains("BF-635 refresh-warning threshold seconds:"));
    }

    @Test
    void keepsExactRawGovernanceDataAvailableForAuditability() throws Exception {
        String script = script();

        assertTrue(script.contains("BF-629:"));
        assertTrue(script.contains("BF-631:"));
        assertTrue(script.contains("BF-633:"));
        assertTrue(script.contains("Captured UTC:"));
        assertTrue(script.contains("Telemetry UTC:"));
        assertTrue(script.contains("BF-603 observed:"));
        assertTrue(script.contains("BF-603 age:"));
        assertTrue(script.contains("BF-602 observed:"));
        assertTrue(script.contains("BF-602 age:"));
        assertTrue(script.contains("raw-guard"));
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
        throw new IllegalStateException("BF-643/BF-644 test could not locate scripts/butler-dashboard.ps1");
    }
}
