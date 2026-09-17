package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerCommandCenterVisualAlignmentBf830Test {

    @Test
    void transformUsesUploadedPrototypePaletteTypographyAndFootballGeometry() throws Exception {
        String transform = source("scripts/butler-app-bf829-editorial-visual-transform.ps1");

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
                "--font-body:'Inter'",
                "--radius:3px",
                "background-image:repeating-linear-gradient",
                ".nav a.active{color:var(--turf);border-bottom-color:var(--turf)",
                "@media(prefers-color-scheme:dark)"
        }) {
            assertTrue(transform.contains(marker), "missing uploaded-prototype marker " + marker);
        }
    }

    @Test
    void managerComponentsUsePrototypeLikeCompactTreatmentsWithoutChangingContracts() throws Exception {
        String transform = source("scripts/butler-app-bf829-editorial-visual-transform.ps1");

        assertTrue(transform.contains(".brand h1{font-family:var(--font-display);font-weight:600;font-size:42px"));
        assertTrue(transform.contains(".panel{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius)"));
        assertTrue(transform.contains(".btn-primary{background:var(--turf);color:#fff"));
        assertTrue(transform.contains(".position-chip{padding:1px 7px;border-radius:3px;background:var(--turf);color:#fff"));
        assertTrue(transform.contains(".player-row:hover{background:var(--surface-2)"));
        assertTrue(transform.contains(".lineup-row.changed{background:color-mix(in srgb,var(--gold) 10%,var(--surface));border-left:3px solid var(--gold)"));
    }

    @Test
    void prototypeAlignmentRemainsPresentationOnly() throws Exception {
        String transform = source("scripts/butler-app-bf829-editorial-visual-transform.ps1");

        assertTrue(transform.contains("BF-830 BLOCKED: command-center visual alignment introduced provider, API, or write behavior."));
        assertFalse(transform.contains("$env:"));
        assertFalse(transform.contains("https://api.sleeper.app"));
        assertFalse(transform.contains("Invoke-RestMethod "));
        assertFalse(transform.contains("Invoke-WebRequest "));
        assertFalse(transform.contains("Method = \"POST\""));
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
        throw new IOException("BF-830 test could not locate " + relativePath);
    }
}
