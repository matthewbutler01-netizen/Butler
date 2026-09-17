package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerEditorialVisualRefreshBf829Test {

    @Test
    void bf827StagesBf829AfterGovernedRosterIntelligence() throws Exception {
        String bf827 = source("scripts/butler-app-bf827-roster-intelligence-transform.ps1");

        int rosterWrite = bf827.indexOf("[System.IO.File]::WriteAllText($CorePath");
        int visualStage = bf827.indexOf("& $bf829Transform -CorePath $CorePath");

        assertTrue(rosterWrite >= 0);
        assertTrue(visualStage > rosterWrite);
        assertTrue(bf827.contains("butler-app-bf829-editorial-visual-transform.ps1"));
    }

    @Test
    void transformReplacesOnlyTheSharedCssFunctionWithEditorialDesignTokens() throws Exception {
        String transform = source("scripts/butler-app-bf829-editorial-visual-transform.ps1");

        assertTrue(transform.contains("-StartMarker 'function Get-AppCss {'"));
        assertTrue(transform.contains("-NextMarker 'function Get-AppNav {'"));
        assertTrue(transform.contains("--paper:#f8f4ec"));
        assertTrue(transform.contains("--accent:#9b3f28"));
        assertTrue(transform.contains("font-family:Georgia,'Times New Roman',serif"));
        assertTrue(transform.contains(".nav a.active:after"));
        assertTrue(transform.contains(".recommendation-panel{border-top:5px solid var(--accent)"));
        assertTrue(transform.contains("border-radius:2px"));
        assertTrue(transform.contains("@media(max-width:760px)"));
    }

    @Test
    void transformKeepsExistingUiContractsAndCannotIntroduceProviderOrWriteBehavior() throws Exception {
        String transform = source("scripts/butler-app-bf829-editorial-visual-transform.ps1");

        for (String existingClass : new String[]{
                ".panel", ".hero-panel", ".recommendation-panel", ".manager-metrics",
                ".roster-board", ".lineup-board", ".status", ".btn-primary",
                ".callout-danger", ".technical"
        }) {
            assertTrue(transform.contains(existingClass), "missing existing CSS contract " + existingClass);
        }

        assertFalse(transform.contains("$env:"));
        assertFalse(transform.contains("https://api.sleeper"));
        assertFalse(transform.contains("Invoke-WebRequest"));
        assertFalse(transform.contains("Start-Process"));
        assertFalse(transform.contains("Set-Content"));
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
        throw new IOException("BF-829 test could not locate " + relativePath);
    }
}
