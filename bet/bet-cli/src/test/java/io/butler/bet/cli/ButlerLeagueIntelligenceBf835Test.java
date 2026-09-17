package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueIntelligenceBf835Test {

    @Test
    void bf827StagesLeagueIntelligenceAfterTradePresentation() throws Exception {
        String bf827 = source("scripts/butler-app-bf827-roster-intelligence-transform.ps1");

        int bf831 = bf827.indexOf("& $bf831Transform -CorePath $CorePath");
        int bf835 = bf827.indexOf("& $bf835Transform -CorePath $CorePath");

        assertTrue(bf831 >= 0, "BF-831 Trade Analyzer staging must remain present");
        assertTrue(bf835 > bf831, "BF-835 must run after shared visual and Trade Analyzer presentation staging");
        assertTrue(bf827.contains("butler-app-bf835-league-intelligence-transform.ps1"));
    }

    @Test
    void managerSurfaceUsesExistingLeagueEvidenceWithoutNewModel() throws Exception {
        String transform = source("scripts/butler-app-bf835-league-intelligence-transform.ps1");

        for (String marker : new String[]{
                "League data needs attention",
                "League intelligence is ready",
                "League intelligence is incomplete",
                "League landscape",
                "Authorized franchise leaders",
                "What changed",
                "What deserves attention",
                "Next steps",
                "Manual technical command",
                "will not fill gaps with inferred data",
                "does not create a new ranking model"
        }) {
            assertTrue(transform.contains(marker), "missing BF-835 manager-facing marker " + marker);
        }

        assertTrue(transform.contains("$View.RankingsAvailable"));
        assertTrue(transform.contains("$View.MovementAvailable"));
        assertTrue(transform.contains("$View.RequiresAttention"));
        assertTrue(transform.contains("$View.CoreReady"));
    }

    @Test
    void rawIdentityAndCommandsAreSecondaryTechnicalDetails() throws Exception {
        String transform = source("scripts/butler-app-bf835-league-intelligence-transform.ps1");

        assertTrue(transform.contains("<details><summary>Franchise identity</summary>"));
        assertTrue(transform.contains("<details><summary>Manual technical command</summary>"));
        assertTrue(transform.contains("<details><summary>Source details</summary>"));
        assertTrue(transform.contains("Team ID $(ConvertTo-HtmlText $leader.TeamId)"));
        assertTrue(transform.contains("$(ConvertTo-HtmlText $action.Command)"));
    }

    @Test
    void transformRemainsReadOnlyAndFailClosed() throws Exception {
        String transform = source("scripts/butler-app-bf835-league-intelligence-transform.ps1");
        int safetyScan = transform.indexOf("$installedStart");
        assertTrue(safetyScan > 0, "BF-835 safety-scan boundary must remain present");
        String operational = transform.substring(0, safetyScan);

        for (String forbidden : new String[]{
                "Invoke-RestMethod",
                "Invoke-WebRequest",
                "https://api.sleeper.app",
                "Method = \"POST\"",
                "submitTransaction",
                "setFaab",
                "AutoFillLineupOptimizer"
        }) {
            assertFalse(operational.contains(forbidden),
                    "BF-835 operational transform must not introduce provider/write behavior: " + forbidden);
        }

        assertTrue(transform.contains("generated staged core failed PowerShell parse"));
        assertTrue(transform.contains("System.Management.Automation.Language.Parser"));
        assertTrue(transform.contains("League Intelligence presentation introduced provider, optimizer, FAAB, or write behavior"));
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
        throw new IOException("BF-835 test could not locate " + relativePath);
    }
}
