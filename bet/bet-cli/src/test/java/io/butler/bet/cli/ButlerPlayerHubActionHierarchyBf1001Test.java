package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPlayerHubActionHierarchyBf1001Test {

    @Test
    void playerHubPromotesExactPlayerCompareInsteadOfGenericMatchup() throws Exception {
        String transform = source("scripts/butler-app-bf1001-player-hub-action-hierarchy-transform.ps1");

        assertTrue(transform.contains("Primary player action"));
        assertTrue(transform.contains("Compare this exact player"));
        assertTrue(transform.contains(
            "class=\"btn btn-primary\" href=\"/compare?left=$hrefId\">Compare this player</a>"));
        assertTrue(transform.contains(
            "class=\"btn btn-secondary\" href=\"/matchup\">Review Matchup</a>"));
    }

    @Test
    void playerHubPreservesAllExistingDecisionShortcuts() throws Exception {
        String transform = source("scripts/butler-app-bf1001-player-hub-action-hierarchy-transform.ps1");

        assertTrue(transform.contains("href=\"/players?q=$positionHref\">Find more $(ConvertTo-HtmlText $View.Position)</a>"));
        assertTrue(transform.contains("$waiverAction"));
        assertTrue(transform.contains("href=\"/franchise?id=$teamHrefId\">Scout franchise</a>"));
        assertTrue(transform.contains("href=\"/trade\">Open Trade Analyzer</a>"));
    }

    @Test
    void stagingRunsAfterPlayerCompareCloseout() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf1000 = staging.indexOf("& $bf1000Transform -CorePath $stagedCore");
        int bf1001 = staging.indexOf("& $bf1001Transform -CorePath $stagedCore");

        assertTrue(bf1000 >= 0, "BF-1000 staging marker missing");
        assertTrue(bf1001 > bf1000, "BF-1001 must run after Player Compare closeout");
        assertTrue(staging.contains("butler-app-bf1001-player-hub-action-hierarchy-transform.ps1"));
    }

    @Test
    void transformAddsNoReadWriteOrOptimizerBehavior() throws Exception {
        String transform = source("scripts/butler-app-bf1001-player-hub-action-hierarchy-transform.ps1");
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
                "BF-1001 operational transform introduced forbidden behavior " + forbidden);
        }
    }

    @Test
    void transformRemainsAsciiOnly() throws Exception {
        String transform = source("scripts/butler-app-bf1001-player-hub-action-hierarchy-transform.ps1");
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
        throw new IOException("BF-1001 test could not locate " + relativePath);
    }
}
