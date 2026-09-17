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
                "@media(prefers-color-scheme:dark)",
                "<a href='/trade'>Trade Analyzer</a>",
                "<h2>Loading Trade Analyzer</h2>",
                "<h2>Trade Analyzer</h2>"
        }) {
            assertTrue(transform.contains(marker), "missing BF-831 command-center marker " + marker);
        }
    }

    @Test
    void governedRecommendationIsPrimaryAndRawEvidenceIsSecondary() throws Exception {
        String transform = source("scripts/butler-app-bf831-trade-analyzer-transform.ps1");

        int recommendation = transform.indexOf("Butler Recommendation");
        int action = transform.indexOf("decision-action");
        int evidence = transform.indexOf("trade-evidence-grid");
        int raw = transform.indexOf("Raw Butler evidence · BF-670 v5");

        assertTrue(recommendation >= 0);
        assertTrue(action >= 0);
        assertTrue(evidence >= 0);
        assertTrue(raw > recommendation, "raw BF-670 evidence should remain behind the manager-facing recommendation");
        assertTrue(transform.contains("<details class='trade-raw'>"));
        assertTrue(transform.contains("Blocking issue:"));
        assertTrue(transform.contains("Gates:"));
    }

    @Test
    void transformPreservesGovernedTradeEngineAndReadOnlyBoundary() throws Exception {
        String transform = source("scripts/butler-app-bf831-trade-analyzer-transform.ps1");
        String tradeLab = source("scripts/butler-trade-lab.ps1");

        assertTrue(transform.contains("Invoke-ButlerTradeV5"));
        assertTrue(tradeLab.contains("Invoke-ButlerTradeV5"));
        assertTrue(transform.contains("BF-831 BLOCKED: trade analyzer transform lost the governed BF-670 v5 invocation."));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("https://api.sleeper.app/v1"));
        assertFalse(transform.contains("roster mutation"));
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
