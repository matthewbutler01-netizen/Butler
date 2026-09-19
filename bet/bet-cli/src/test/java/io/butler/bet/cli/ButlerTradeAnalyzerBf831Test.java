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
    void bf827ValidatesTradeAnalyzerAfterSharedCommandCenterVisualBaseline() throws Exception {
        String bf827 = source("scripts/butler-app-bf827-roster-intelligence-transform.ps1");

        int bf829 = bf827.indexOf("& $bf829Transform -CorePath $CorePath");
        int bf831 = bf827.indexOf("& $bf831Transform -CorePath $CorePath");

        assertTrue(bf829 >= 0, "BF-829 visual baseline must remain staged");
        assertTrue(bf831 > bf829, "BF-831 validation must run after BF-830/BF-829 establishes the shared visual baseline");
        assertTrue(bf827.contains("butler-app-bf831-trade-analyzer-transform.ps1"));
    }

    @Test
    void canonicalSourcesOwnBf833FamilyTradeAnalyzerPresentation() throws Exception {
        String tradeHost = source("scripts/butler-trade-lab-host.ps1");
        String tradeLab = source("scripts/butler-trade-lab.ps1");

        for (String marker : new String[]{
                "read-only Trade Analyzer module",
                "--bg:#F3F2EE",
                "--surface:#FFFFFF",
                "--surface-2:#F7F6F2",
                "--line:#D9DCD7",
                "--turf:#376E50",
                "--turf-deep:#28543D",
                "--gold:#A77418",
                "--ink:#1E2521",
                "--muted:#68726B",
                "--brick:#A65245",
                "--font-display:'Inter'",
                "--radius:10px",
                "background-image:none",
                "--bg:#111315",
                "--surface:#191C1E",
                "--surface-2:#202426",
                "@media(prefers-color-scheme:dark)",
                "Trade Analyzer",
                "Opening Trade Analyzer..."
        }) {
            assertTrue(tradeHost.contains(marker), "canonical Trade Analyzer host missing " + marker);
        }
        assertFalse(tradeHost.contains("'Teko'"), "canonical Trade Analyzer host must not depend on Teko");
        assertFalse(tradeHost.contains("repeating-linear-gradient"), "canonical Trade Analyzer host must not retain field-line backgrounds");

        for (String marker : new String[]{
                "read-only Trade Analyzer app module",
                "Butler recommendation",
                "Analyze a trade",
                "Get Butler recommendation",
                ".trade-result{border-left:4px solid var(--turf)}",
                ".trade-proof",
                ".gate-grid",
                ".veto-item",
                ".raw-output"
        }) {
            assertTrue(tradeLab.contains(marker), "canonical Trade Analyzer page missing " + marker);
        }
    }

    @Test
    void laterLoadedDecisionHistoryKeepsTradeAnalyzerNavNaming() throws Exception {
        String history = source("scripts/butler-decision-history.ps1");

        assertTrue(history.contains("Trade Analyzer</a>"),
                "the later-loaded Decision History nav must preserve Trade Analyzer naming");
        assertFalse(history.contains("Trade Lab</a>"),
                "Decision History must not override the shared nav back to legacy Trade Lab naming");
    }

    @Test
    void bf831StagesBf837ButNeverRewritesCanonicalTradeSources() throws Exception {
        String transform = source("scripts/butler-app-bf831-trade-analyzer-transform.ps1");

        assertTrue(transform.contains("butler-app-bf837-manager-page-visual-transform.ps1"));
        assertTrue(transform.contains("& $bf837Transform -CorePath $CorePath"));
        assertTrue(transform.contains("$tradeHostText = [System.IO.File]::ReadAllText($TradeHostPath)"));
        assertTrue(transform.contains("$lab = [System.IO.File]::ReadAllText($TradeLabPath)"));
        assertTrue(transform.contains("canonical Trade Analyzer host marker is missing"));
        assertTrue(transform.contains("canonical Trade Analyzer decision marker is missing"));

        assertFalse(transform.contains("WriteAllText("),
                "BF-831 must never rewrite the tracked Trade Analyzer source files");
        assertFalse(transform.contains("Replace-Block"),
                "BF-831 must not retain source-transform replacement behavior");
        assertFalse(transform.contains("$tradeHostText = $tradeHostText.Replace"),
                "BF-831 must not mutate canonical Trade Analyzer host source");
        assertFalse(transform.contains("$lab = $lab.Replace"),
                "BF-831 must not mutate canonical Trade Analyzer page source");
        assertFalse(transform.contains("$host = "),
                "PowerShell variables are case-insensitive; $host collides with the read-only built-in $Host variable");
    }

    @Test
    void validatorParsesCoreAndBothCanonicalTradeModules() throws Exception {
        String transform = source("scripts/butler-app-bf831-trade-analyzer-transform.ps1");

        assertTrue(transform.contains("foreach ($pathToParse in @($CorePath, $TradeHostPath, $TradeLabPath))"));
        assertTrue(transform.contains("System.Management.Automation.Language.Parser]::ParseFile($pathToParse"));
        assertTrue(transform.contains("Trade Analyzer validation failed PowerShell parse"));
        assertTrue(transform.contains("Extent.StartLineNumber"));
    }

    @Test
    void governedRecommendationAndReadOnlyBoundaryRemainCanonical() throws Exception {
        String transform = source("scripts/butler-app-bf831-trade-analyzer-transform.ps1");
        String tradeLab = source("scripts/butler-trade-lab.ps1");

        for (String marker : new String[]{
                "trade recommendation $LeagueId",
                "ConvertTo-TradeRecommendationView",
                "PerspectiveTeamId",
                "StrategicVeto",
                "EvidenceComplete",
                "TransitionCoverage",
                "ProtectedCoverage",
                "Why Butler says this",
                "Raw decision record",
                "READ ONLY"
        }) {
            assertTrue(tradeLab.contains(marker), "current BF-670 trade contract missing " + marker);
            assertTrue(transform.contains(marker), "BF-831 validator does not guard current BF-670 marker " + marker);
        }

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
