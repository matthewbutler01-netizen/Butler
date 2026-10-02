package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPlayerCompareCompletedHierarchyBf1000Test {

    @Test
    void completedComparePromotesStartingDifferentPair() throws Exception {
        String transform = source("scripts/butler-app-bf1000-player-compare-completed-hierarchy-transform.ps1");

        assertTrue(transform.contains("Completed comparison"));
        assertTrue(transform.contains("Two exact rostered players are loaded side by side."));
        assertTrue(transform.contains(
            "<a class=\"btn btn-primary\" href=\"/players\">Compare different players</a>"));
        assertTrue(transform.contains(
            "<a class=\"btn btn-secondary\" href=\"$swapHref\">Swap sides</a>"));
    }

    @Test
    void exactSwapAndCardContinuationRemainAvailable() throws Exception {
        String transform = source("scripts/butler-app-bf1000-player-compare-completed-hierarchy-transform.ps1");
        String loop = source("scripts/butler-app-bf923-player-compare-loop-transform.ps1");
        String waivers = source("scripts/butler-app-bf964-player-compare-position-waivers-transform.ps1");

        assertTrue(loop.contains("$swapHref = \"/compare?left=$rightHref&right=$leftHref$swapSuffix\""));
        assertTrue(loop.contains("Compare with another $(ConvertTo-HtmlText $Player.Position)"));
        assertTrue(waivers.contains("$waiverAction"));
        assertTrue(transform.contains("Butler does not choose a winner"));
    }

    @Test
    void stagingRunsAfterPlayerSearchCloseout() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf999 = staging.indexOf("& $bf999Transform -CorePath $stagedCore");
        int bf1000 = staging.indexOf("& $bf1000Transform -CorePath $stagedCore");

        assertTrue(bf999 >= 0, "BF-999 staging marker missing");
        assertTrue(bf1000 > bf999, "BF-1000 must run after Player Search closeout");
        assertTrue(staging.contains("butler-app-bf1000-player-compare-completed-hierarchy-transform.ps1"));
    }

    @Test
    void transformAddsNoReadWriteOrOptimizerBehavior() throws Exception {
        String transform = source("scripts/butler-app-bf1000-player-compare-completed-hierarchy-transform.ps1");
        int safetyScan = transform.indexOf("$surface -match");
        assertTrue(safetyScan > 0);
        String operational = transform.substring(0, safetyScan);

        for (String forbidden : new String[] {
            "Invoke-RestMethod",
            "Invoke-WebRequest",
            "Invoke-ButlerReadOnly",
            "Invoke-Bf742DashboardWorkerRead",
            "https://api.sleeper.app",
            "Method = \"POST\"",
            "submitTransaction",
            "setFaab",
            "AutoFillLineupOptimizer",
            "returnUrl",
            "redirectUrl",
            "javascript:"
        }) {
            assertFalse(operational.contains(forbidden),
                "BF-1000 operational transform introduced forbidden behavior " + forbidden);
        }
    }

    @Test
    void transformRemainsAsciiOnly() throws Exception {
        String transform = source("scripts/butler-app-bf1000-player-compare-completed-hierarchy-transform.ps1");
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(transform));
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
        throw new IOException("BF-1000 test could not locate " + relativePath);
    }
}
