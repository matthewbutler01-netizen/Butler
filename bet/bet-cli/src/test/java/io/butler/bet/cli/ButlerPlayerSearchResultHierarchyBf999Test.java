package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPlayerSearchResultHierarchyBf999Test {

    @Test
    void searchResultsPromoteExactPlayerDetailAsPrimaryDrillDown() throws Exception {
        String transform = source("scripts/butler-app-bf999-player-search-result-hierarchy-transform.ps1");

        assertTrue(transform.contains("Primary drill-down"));
        assertTrue(transform.contains("Open this exact player"));
        assertTrue(transform.contains("Player Detail keeps the exact player ID and returns to this search query."));
        assertTrue(transform.contains(
            "class=`\"btn btn-primary`\" href=`\"/player?id=$hrefId&from=players&q=$searchReturnHref`\">View Player Detail</a>"));
    }

    @Test
    void searchResultsKeepCompareScoutAndWaiverToolsSecondary() throws Exception {
        String transform = source("scripts/butler-app-bf999-player-search-result-hierarchy-transform.ps1");

        assertTrue(transform.contains("player-search-secondary-actions"));
        assertTrue(transform.contains("href=`\"/compare?left=$hrefId`\">Compare this player</a>"));
        assertTrue(transform.contains("Scout franchise"));
        assertTrue(transform.contains("$resultWaiverAction"));
        assertTrue(transform.contains("Quick position searches"));
        assertTrue(transform.contains("Free agents remain on Waiver Board."));
    }

    @Test
    void stagingRunsAfterFranchiseScoutCloseout() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf997 = staging.indexOf("& $bf997Transform -CorePath $stagedCore");
        int bf999 = staging.indexOf("& $bf999Transform -CorePath $stagedCore");

        assertTrue(bf997 >= 0, "BF-997 staging marker missing");
        assertTrue(bf999 > bf997, "BF-999 must run after Franchise Scout closeout");
        assertTrue(staging.contains("butler-app-bf999-player-search-result-hierarchy-transform.ps1"));
    }

    @Test
    void transformAddsNoReadWriteOrOptimizerBehavior() throws Exception {
        String transform = source("scripts/butler-app-bf999-player-search-result-hierarchy-transform.ps1");

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
                "BF-999 operational transform introduced forbidden behavior " + forbidden);
        }
    }

    @Test
    void transformRemainsAsciiOnly() throws Exception {
        String transform = source("scripts/butler-app-bf999-player-search-result-hierarchy-transform.ps1");
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
        throw new IOException("BF-999 test could not locate " + relativePath);
    }
}
