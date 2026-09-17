package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWaiverDecisionSurfaceBf834Test {

    @Test
    void bf715StagesWaiverDecisionSurfaceLast() throws Exception {
        String bf715 = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf813 = bf715.indexOf("& $bf813Transform -DashboardPath $DashboardPath");
        int bf834 = bf715.indexOf("& $bf834Transform -DashboardPath $DashboardPath");

        assertTrue(bf813 >= 0, "existing priority evidence staging must remain present");
        assertTrue(bf834 > bf813, "BF-834 must run after the existing dashboard/core staging chain returns");
        assertTrue(bf715.contains("butler-dashboard-bf834-waiver-decision-surface-transform.ps1"));
    }

    @Test
    void managerSurfaceCoversActionableRefreshAndNoMoveStates() throws Exception {
        String transform = source("scripts/butler-dashboard-bf834-waiver-decision-surface-transform.ps1");

        for (String marker : new String[]{
                "Review Butler's proven add/drop move",
                "Refresh before relying on this waiver move",
                "Waiver review complete; no move proven",
                "Waiver move already pending",
                "Do not act on the saved waiver move",
                "MOVE PROVEN",
                "NO MOVE",
                "What to do now",
                "Authorized review pool"
        }) {
            assertTrue(transform.contains(marker), "missing BF-834 manager state marker " + marker);
        }
    }

    @Test
    void exactGovernedPairIsPresentedWithoutInventingBoardRank() throws Exception {
        String transform = source("scripts/butler-dashboard-bf834-waiver-decision-surface-transform.ps1");

        assertTrue(transform.contains("if ($pair.Active)"));
        assertTrue(transform.contains("$pair.Add.Name"));
        assertTrue(transform.contains("$pair.Drop.Name"));
        assertTrue(transform.contains("Current governed ADD"));
        assertTrue(transform.contains("Paired audited DROP"));
        assertTrue(transform.contains("it is not inferred from board order"));
        assertTrue(transform.contains("NOT A RANKING."));
    }

    @Test
    void transformRemainsReadOnlyAndFailClosed() throws Exception {
        String transform = source("scripts/butler-dashboard-bf834-waiver-decision-surface-transform.ps1");
        int safetyScan = transform.indexOf("$installedWaiverStart");
        assertTrue(safetyScan > 0, "BF-834 safety scan boundary must remain present");
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
                    "BF-834 operational transform must not introduce provider/write behavior: " + forbidden);
        }

        assertTrue(transform.contains("READ ONLY &middot; MANAGER DECISION SUPPORT."));
        assertTrue(transform.contains("generated Dashboard failed PowerShell parse"));
        assertTrue(transform.contains("System.Management.Automation.Language.Parser"));
        assertTrue(transform.contains("Waiver decision presentation introduced provider, optimizer, FAAB, or write behavior"));
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
        throw new IOException("BF-834 test could not locate " + relativePath);
    }
}
