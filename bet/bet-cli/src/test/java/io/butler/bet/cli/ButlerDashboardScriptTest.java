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
    void invokesOnlyApprovedReadOnlyDecisionAndRosterTasks() throws Exception {
        String script = script();
        assertTrue(script.contains(":bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary"));
        assertTrue(script.contains(":bet:bet-cli:sleeperPersonalizedCurrentRosterSummary"));
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
    void keepsHumanReadableDecisionUxAndFixesAuditSpacing() throws Exception {
        String script = script();
        assertTrue(script.contains("Ready to act"));
        assertTrue(script.contains("Move completed"));
        assertTrue(script.contains("Move pending"));
        assertTrue(script.contains("Do not act"));
        assertTrue(script.contains("What to do"));
        assertTrue(script.contains("Live roster check passed"));
        assertTrue(script.contains("Evidence lineage verified"));
        assertTrue(script.contains("Warning boundary:"));
        assertTrue(script.contains("lineage-copy"));
        assertTrue(script.contains("Every governed recommendation remains traceable"));
    }

    @Test
    void exposesReadOnlyMyTeamRouteWithExactRosterIdentityAndNoGrades() throws Exception {
        String script = script();
        assertTrue(script.contains("href=\"/roster\""));
        assertTrue(script.contains("$path -eq \"/roster\""));
        assertTrue(script.contains("BF-645 roster JSON:"));
        assertTrue(script.contains("Your live Sleeper roster"));
        assertTrue(script.contains("Quarterbacks"));
        assertTrue(script.contains("Running backs"));
        assertTrue(script.contains("Wide receivers"));
        assertTrue(script.contains("Tight ends"));
        assertTrue(script.contains("STARTER"));
        assertTrue(script.contains("RESERVE"));
        assertTrue(script.contains("TAXI"));
        assertTrue(script.contains("Sleeper ID"));
        assertTrue(script.contains("Unmapped Sleeper identity"));
        assertTrue(script.contains("names are never used to infer identity"));
        assertFalse(script.contains("Player grade"));
        assertFalse(script.contains("Roster grade"));
    }

    @Test
    void healthEndpointAndNativeGradleExitCodeRemainAuthoritative() throws Exception {
        String script = script();
        assertTrue(script.contains("$path -eq \"/health\""));
        assertTrue(script.contains("{\"status\":\"ok\",\"service\":\"butler-dashboard\",\"bind\":\"127.0.0.1\"}"));
        assertTrue(script.contains("$ErrorActionPreference = \"Continue\""));
        assertTrue(script.contains("$exitCode = $LASTEXITCODE"));
        assertTrue(script.contains("if ($exitCode -ne 0)"));
        assertTrue(script.contains("$ErrorActionPreference = $previousPreference"));
        assertTrue(script.contains("$parts[0] -ne \"GET\""));
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
        throw new IllegalStateException("BF-645 test could not locate scripts/butler-dashboard.ps1");
    }
}
