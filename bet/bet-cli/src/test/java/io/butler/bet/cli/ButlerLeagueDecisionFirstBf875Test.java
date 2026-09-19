package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueDecisionFirstBf875Test {

    @Test
    void leagueNextStepsFollowStatusBeforeSupportingContext() throws Exception {
        String transform = source("scripts/butler-app-bf835-league-intelligence-transform.ps1");

        int status = transform.indexOf("<div class=\"eyebrow\">League status</div>");
        int nextSteps = transform.indexOf("<div class=\"eyebrow\">What deserves attention</div>");
        int landscape = transform.indexOf("<div class=\"eyebrow\">League landscape</div>");
        int movement = transform.indexOf("<div class=\"eyebrow\">What changed</div>");

        assertTrue(status >= 0, "League status panel must remain present");
        assertTrue(nextSteps > status, "Next steps must follow League status");
        assertTrue(landscape > nextSteps, "League landscape must become supporting context after Next steps");
        assertTrue(movement > landscape, "What changed must remain after League landscape");
    }

    @Test
    void existingGovernedLeagueGuidanceAndEvidenceRemainUnchanged() throws Exception {
        String transform = source("scripts/butler-app-bf835-league-intelligence-transform.ps1");
        String core = source("scripts/butler-app-shell-core-single.ps1");

        for (String marker : new String[]{
                "Butler's existing deterministic league-health guidance",
                "$actionsHtml",
                "League landscape",
                "Authorized franchise leaders",
                "What changed",
                "Value movement",
                "Manual technical command",
                "Source details",
                "Franchise identity"
        }) {
            assertTrue(transform.contains(marker), "missing preserved League marker " + marker);
        }

        for (String gate : new String[]{
                "$View.RankingsAvailable",
                "$View.MovementAvailable",
                "$View.RequiresAttention",
                "$View.CoreReady"
        }) {
            assertTrue(core.contains(gate), "missing preserved governed League gate " + gate);
        }
    }

    @Test
    void reorderDoesNotIntroduceProviderWriteOrExecutionBehavior() throws Exception {
        String transform = source("scripts/butler-app-bf835-league-intelligence-transform.ps1");
        int safetyScan = transform.indexOf("$installedStart");
        assertTrue(safetyScan > 0, "BF-835 safety boundary must remain present");
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
                    "League presentation must not introduce provider/write behavior: " + forbidden);
        }

        assertTrue(transform.contains("never executed by this page"));
        assertTrue(transform.contains("does not create a new ranking model"));
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
        throw new IOException("BF-875 test could not locate " + relativePath);
    }
}
