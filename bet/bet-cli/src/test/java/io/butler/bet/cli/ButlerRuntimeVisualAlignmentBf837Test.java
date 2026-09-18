package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerRuntimeVisualAlignmentBf837Test {

    @Test
    void bf837RunsAfterBf834AndChainedManagerTransformsReturn() throws Exception {
        String bf715 = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf834 = bf715.indexOf("& $bf834Transform -DashboardPath $DashboardPath");
        int core837 = bf715.indexOf("& $bf837CoreTransform -CorePath $stagedCore");
        int dashboard837 = bf715.indexOf("& $bf837DashboardTransform -DashboardPath $DashboardPath");

        assertTrue(bf834 >= 0, "BF-834 waiver surface must remain staged");
        assertTrue(core837 > bf834, "BF-837 staged-core alignment must run after BF-834 returns");
        assertTrue(dashboard837 > core837, "BF-837 dashboard-hosted alignment must be the final visual pass");
        assertTrue(bf715.contains("butler-app-bf837-manager-page-visual-transform.ps1"));
        assertTrue(bf715.contains("butler-dashboard-bf837-manager-page-visual-transform.ps1"));
    }

    @Test
    void dashboardHostedAlignmentOwnsWaiverAndSharedChrome() throws Exception {
        String transform = source("scripts/butler-dashboard-bf837-manager-page-visual-transform.ps1");

        for (String marker : new String[]{
                "BF-837 dashboard-hosted manager visual alignment",
                "BF-837 Waiver Board final visual override",
                "function Get-SharedCss {",
                "function ConvertTo-WaiverHtml {",
                "--bg:#F3F2EE",
                "--bg:#111315",
                "background-image:none",
                ".candidate-card",
                ".waiver-action-card",
                ".waiver-next"
        }) {
            assertTrue(transform.contains(marker), "BF-837 dashboard transform missing " + marker);
        }

        for (String forbidden : new String[]{
                "Invoke-RestMethod",
                "Invoke-WebRequest",
                "https://api.sleeper.app",
                "Method = \"POST\"",
                "submitTransaction",
                "setFaab",
                "AutoFillLineupOptimizer"
        }) {
            assertFalse(transform.contains(forbidden), "BF-837 dashboard visual pass introduced behavior marker " + forbidden);
        }
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
        throw new IOException("BF-837 runtime visual test could not locate " + relativePath);
    }
}
