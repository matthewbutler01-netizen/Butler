package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerFranchiseScoutActionHierarchyBf997Test {

    @Test
    void scoutMakesSelectedFranchiseAndPrimaryTradeStepExplicit() throws Exception {
        String transform = source("scripts/butler-app-bf997-franchise-scout-action-hierarchy-transform.ps1");

        assertTrue(transform.contains("$teamNameHtml = ConvertTo-HtmlText ([string]$View.TeamName)"));
        assertTrue(transform.contains("You are scouting <strong>$teamNameHtml</strong>."));
        assertTrue(transform.contains("<span>Selected franchise</span><strong>$teamNameHtml</strong>"));
        assertTrue(transform.contains("Trade Analyzer keeps this exact team as the selected partner through review."));
        assertTrue(transform.contains("Primary next step"));
        assertTrue(transform.contains("Build the exact deal"));
        assertTrue(transform.contains("href=\"/trade?opponent=$teamHrefId\">Open Trade Analyzer</a>"));
    }

    @Test
    void scoutPreservesSecondaryLeagueAndPlayerTools() throws Exception {
        String transform = source("scripts/butler-app-bf997-franchise-scout-action-hierarchy-transform.ps1");

        assertTrue(transform.contains("Continue scouting"));
        assertTrue(transform.contains("href=\"/league\">Back to League</a>"));
        assertTrue(transform.contains("href=\"/players\">Find a player</a>"));
        assertTrue(transform.contains("href=\"/compare\">Compare players</a>"));
        assertTrue(transform.contains("View franchise evidence"));
    }

    @Test
    void stagingRunsAfterLeagueOrientation() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf996 = staging.indexOf("& $bf996Transform -CorePath $stagedCore");
        int bf997 = staging.indexOf("& $bf997Transform -CorePath $stagedCore");

        assertTrue(bf996 >= 0, "BF-996 staging marker missing");
        assertTrue(bf997 > bf996, "BF-997 must run after final League manager orientation");
        assertTrue(staging.contains("butler-app-bf997-franchise-scout-action-hierarchy-transform.ps1"));
    }

    @Test
    void transformAddsNoReadWriteOrRecommendationBehavior() throws Exception {
        String transform = source("scripts/butler-app-bf997-franchise-scout-action-hierarchy-transform.ps1");

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
                "BF-997 operational transform introduced forbidden behavior " + forbidden);
        }

        assertTrue(transform.contains(
            "Franchise Scout action hierarchy introduced provider, backend-read, optimizer, write, or open-redirect behavior"));
    }

    @Test
    void transformRemainsAsciiOnly() throws Exception {
        String transform = source("scripts/butler-app-bf997-franchise-scout-action-hierarchy-transform.ps1");
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
        throw new IOException("BF-997 test could not locate " + relativePath);
    }
}
