package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardVisualRefinementBf833Test {

    @Test
    void bf832StagesRefinementAfterCommandCenterVisuals() throws Exception {
        String bf832 = source("scripts/butler-dashboard-bf832-command-center-visual-transform.ps1");

        int write = bf832.indexOf("WriteAllText($DashboardPath");
        int bf833 = bf832.indexOf("& $bf833Transform -DashboardPath $DashboardPath");

        assertTrue(write >= 0, "BF-832 staged dashboard write must remain present");
        assertTrue(bf833 > write, "BF-833 must run after BF-832 installs its dashboard visual contract");
        assertTrue(bf832.contains("butler-dashboard-bf833-visual-language-refinement-transform.ps1"));
    }

    @Test
    void refinementRemovesCondensedGreenHeavyPresentation() throws Exception {
        String transform = source("scripts/butler-dashboard-bf833-visual-language-refinement-transform.ps1");

        for (String marker : new String[]{
                "BF-833 final Dashboard visual refinement",
                "--font-display:'Inter'",
                "--bg:#F3F2EE",
                "--bg:#111315",
                "background-image:none",
                ".dashboard-command-center .priority-card.primary",
                ".dashboard-command-center .command-title",
                ".dashboard-command-center .nav a.active",
                "--surface:#191C1E",
                "--surface-2:#202426"
        }) {
            assertTrue(transform.contains(marker), "missing BF-833 visual marker " + marker);
        }

        assertFalse(transform.contains("font-display:'Teko'"), "BF-833 must not reintroduce condensed display typography");
        assertFalse(transform.contains("repeating-linear-gradient"), "BF-833 must not reintroduce the field-line background");
    }

    @Test
    void queueAndDecisionDetailsForceNeutralSurfaces() throws Exception {
        String transform = source("scripts/butler-dashboard-bf833-visual-language-refinement-transform.ps1");

        for (String marker : new String[]{
                "background:var(--surface-2)!important",
                "background:var(--surface)!important",
                ".dashboard-command-center .priority-card .status",
                ".dashboard-command-center .priority-index",
                ".dashboard-command-center .priority-type",
                ".dashboard-command-center .priority-copy",
                ".dashboard-command-center details",
                "background:#171A1C!important",
                "background:#1D2123!important"
        }) {
            assertTrue(transform.contains(marker), "missing BF-833 queue-neutralization marker " + marker);
        }
    }

    @Test
    void refinementRemainsPresentationOnlyAndFailClosed() throws Exception {
        String transform = source("scripts/butler-dashboard-bf833-visual-language-refinement-transform.ps1");
        int safetyScan = transform.indexOf("$dashboardStart");
        assertTrue(safetyScan > 0, "BF-833 dashboard safety boundary must remain present");
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
                    "BF-833 operational transform must not introduce provider/write behavior: " + forbidden);
        }

        assertTrue(transform.contains("generated Dashboard failed PowerShell parse"));
        assertTrue(transform.contains("System.Management.Automation.Language.Parser"));
        assertTrue(transform.contains("visual refinement introduced provider, optimizer, FAAB, or write behavior"));
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
        throw new IOException("BF-833 test could not locate " + relativePath);
    }
}
