package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeAnalyzerBf831Test {

    @Test
    void bf827StagesTradeAnalyzerAfterSharedCommandCenterVisualBaseline() throws Exception {
        String bf827 = source("scripts/butler-app-bf827-roster-intelligence-transform.ps1");

        int bf829 = bf827.indexOf("& $bf829Transform -CorePath $CorePath");
        int bf831 = bf827.indexOf("& $bf831Transform -CorePath $CorePath");

        assertTrue(bf829 >= 0, "BF-829 visual baseline must remain staged");
        assertTrue(bf831 > bf829, "BF-831 must run after BF-830/BF-829 establishes the shared visual baseline");
        assertTrue(bf827.contains("butler-app-bf831-trade-analyzer-transform.ps1"));
    }

    @Test
    void transformUsesCommandCenterTokensAndManagerFacingTradeAnalyzerNaming() throws Exception {
        String transform = source("scripts/butler-app-bf831-trade-analyzer-transform.ps1");

        for (String marker : new String[]{
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
                "--radius:3px",
                "repeating-linear-gradient",
                "@media(prefers-color-scheme:dark)",
                "$tradeHostText = $tradeHostText.Replace('Trade Lab', 'Trade Analyzer')",
                "$lab = $lab.Replace('Trade Lab', 'Trade Analyzer')",
                "Analyze a trade",
                "Get Butler recommendation"
        }) {
            assertTrue(transform.contains(marker), "missing BF-831 command-center marker " + marker);
        }
    }

    @Test
    void transformAvoidsReservedPowerShellHostVariable() throws Exception {
        String transform = source("scripts/butler-app-bf831-trade-analyzer-transform.ps1");

        assertTrue(transform.contains("$tradeHostText = [System.IO.File]::ReadAllText($TradeHostPath)"));
        assertTrue(transform.contains("WriteAllText($TradeHostPath, $tradeHostText"));
        assertFalse(transform.contains("$host = "),
                "PowerShell variables are case-insensitive; $host collides with the read-only built-in $Host variable");
    }

    @Test
    void governedRecommendationAndExistingEvidenceRemainTheDecisionHierarchy() throws Exception {
        String transform = source("scripts/butler-app-bf831-trade-analyzer-transform.ps1");

        for (String marker : new String[]{
                "Butler recommendation",
                "trade-result",
                "StrategicVeto",
                "EvidenceComplete",
                "TransitionCoverage",
                "ProtectedCoverage",
                "Technical governed output",
                ".gate-grid",
                ".veto-item",
                ".raw-output"
        }) {
            assertTrue(transform.contains(marker), "missing governed decision/evidence marker " + marker);
        }
    }

    @Test
    void transformProtectsCurrentRoutedBf670EngineAndReadOnlyBoundary() throws Exception {
        String transform = source("scripts/butler-app-bf831-trade-analyzer-transform.ps1");
        String tradeLab = source("scripts/butler-trade-lab.ps1");

        for (String marker : new String[]{
                "trade recommendation $LeagueId",
                "ConvertTo-TradeRecommendationView",
                "PerspectiveTeamId",
                "StrategicVeto",
                "EvidenceComplete",
                "READ ONLY"
        }) {
            assertTrue(tradeLab.contains(marker), "current BF-670 trade contract missing " + marker);
            assertTrue(transform.contains(marker), "BF-831 does not guard current BF-670 marker " + marker);
        }

        assertTrue(transform.contains("BF-831 BLOCKED: governed BF-670 marker is missing after presentation transform"));
        assertTrue(transform.contains("BF-831 BLOCKED: Trade Analyzer introduced provider, API, or write behavior marker"));
        assertFalse(tradeLab.contains("Method = \"POST\""));
        assertFalse(tradeLab.contains("https://api.sleeper.app"));
        assertFalse(transform.contains("$env:"));
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
        throw new IOException("BF-831 test could not locate " + relativePath);
    }
}
