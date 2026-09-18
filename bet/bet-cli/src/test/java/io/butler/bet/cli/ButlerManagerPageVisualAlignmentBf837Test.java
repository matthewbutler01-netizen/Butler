package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerManagerPageVisualAlignmentBf837Test {

    @Test
    void stagedManagerTransformOwnsBf833FamilyTokens() throws Exception {
        String transform = source("scripts/butler-app-bf837-manager-page-visual-transform.ps1");

        for (String marker : new String[]{
                "BF-837 manager-page visual alignment",
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
                "--surface-2:#202426"
        }) {
            assertTrue(transform.contains(marker), "BF-837 staged visual transform missing " + marker);
        }

        assertTrue(transform.contains("family=Teko"),
                "BF-837 must explicitly identify and remove the legacy Teko import");
        assertTrue(transform.contains("$core = $core.Replace($oldImport, $newImport)"));
        assertTrue(transform.contains("legacy Teko font dependency remains"));
        assertTrue(transform.contains("Parser]::ParseFile($CorePath"));
    }

    @Test
    void outerTradeAndHistoryShellUsesSameVisualFamily() throws Exception {
        String host = source("scripts/butler-trade-lab-host.ps1");

        assertTrue(host.contains("--bg:#F3F2EE"));
        assertTrue(host.contains("--surface-2:#F7F6F2"));
        assertTrue(host.contains("--bg:#111315"));
        assertTrue(host.contains("--surface:#191C1E"));
        assertTrue(host.contains("background-image:none"));
        assertTrue(host.contains(".history-card{border-color:var(--line)!important"));
        assertTrue(host.contains(".detail-item{border-color:var(--line)!important"));
        assertFalse(host.contains("repeating-linear-gradient"));
        assertFalse(host.contains("'Teko'"));
    }

    @Test
    void bf831StagesAlignmentWithoutRestoringSourceMutation() throws Exception {
        String bf831 = source("scripts/butler-app-bf831-trade-analyzer-transform.ps1");

        assertTrue(bf831.contains("butler-app-bf837-manager-page-visual-transform.ps1"));
        assertTrue(bf831.contains("& $bf837Transform -CorePath $CorePath"));
        assertTrue(bf831.contains("BF-837 manager-page visual alignment"));
        assertFalse(bf831.contains("WriteAllText("));
        assertFalse(bf831.contains("repeating-linear-gradient"));
        assertFalse(bf831.contains("--radius:3px"));
    }

    @Test
    void visualAlignmentDoesNotIntroduceProviderOrWriteBehavior() throws Exception {
        String transform = source("scripts/butler-app-bf837-manager-page-visual-transform.ps1");
        String host = source("scripts/butler-trade-lab-host.ps1");

        for (String forbidden : new String[]{
                "Invoke-RestMethod",
                "Invoke-WebRequest",
                "https://api.sleeper.app",
                "Method = \"POST\"",
                "submitTransaction",
                "setFaab",
                "AutoFillLineupOptimizer"
        }) {
            assertFalse(transform.contains(forbidden), "BF-837 visual transform introduced behavior marker " + forbidden);
            assertFalse(host.contains(forbidden), "canonical visual host introduced behavior marker " + forbidden);
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
        throw new IOException("BF-837 test could not locate " + relativePath);
    }
}
