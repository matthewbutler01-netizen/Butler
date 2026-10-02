package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueManagerOrientationBf996Test {

    @Test
    void leagueHubSeparatesOwnedTeamWorkflowFromNeutralLeagueBoard() throws Exception {
        String transform = source("scripts/butler-app-bf996-league-manager-orientation-transform.ps1");

        assertTrue(transform.contains("Manager orientation"));
        assertTrue(transform.contains("Your team and the league have different jobs here"));
        assertTrue(transform.contains("Your franchise"));
        assertTrue(transform.contains("href=\"/team\">Open My Team</a>"));
        assertTrue(transform.contains("League teams"));
        assertTrue(transform.contains("The franchise board is league-neutral."));
        assertTrue(transform.contains("does not guess that every displayed team is an opponent"));
        assertTrue(transform.contains("id=\"league-franchise-board\""));
        assertTrue(transform.contains("neutral league snapshot and can include your own franchise"));
    }

    @Test
    void exactFranchiseActionsRemainBoundToTheDisplayedTeam() throws Exception {
        String transform = source("scripts/butler-app-bf996-league-manager-orientation-transform.ps1");

        assertTrue(transform.contains(
            "$leaderHrefId = [System.Uri]::EscapeDataString([string]$leader.TeamId)"));
        assertTrue(transform.contains(
            "href=\"/franchise?id=$leaderHrefId\">Scout franchise</a>"));
        assertTrue(transform.contains(
            "href=\"/trade?opponent=$leaderHrefId\">Open Trade Analyzer</a>"));
        assertTrue(transform.contains(
            "Each displayed card keeps its exact team identity for Scout franchise and Trade Analyzer."));
    }

    @Test
    void stagingRunsAfterBf994AsFinalLeaguePresentationPass() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf994 = staging.indexOf("& $bf994Transform -CorePath $stagedCore");
        int bf996 = staging.indexOf("& $bf996Transform -CorePath $stagedCore");

        assertTrue(bf994 >= 0, "BF-994 staging marker missing");
        assertTrue(bf996 > bf994, "BF-996 must run after BF-994 final My Team scanability");
        assertTrue(staging.contains("butler-app-bf996-league-manager-orientation-transform.ps1"));
    }

    @Test
    void transformAddsNoIdentityReadProviderCallOrWritePath() throws Exception {
        String transform = source("scripts/butler-app-bf996-league-manager-orientation-transform.ps1");

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
                "BF-996 operational transform introduced read/write marker " + forbidden);
        }

        assertTrue(transform.contains("generated staged core"));
        assertTrue(transform.contains("League manager orientation introduced provider, backend-read, optimizer, write, or open-redirect behavior"));
    }

    @Test
    void transformRemainsAsciiOnly() throws Exception {
        String transform = source("scripts/butler-app-bf996-league-manager-orientation-transform.ps1");
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
        throw new IOException("BF-996 test could not locate " + relativePath);
    }
}
