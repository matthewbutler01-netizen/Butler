package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerFastLaneNavigationBf921Test {

    @Test
    void fastLaneReusesOneCleanWorktreeAtExactRemoteSha() throws Exception {
        String verifier = source("scripts/butler-fastlane-verify.ps1");
        String wrapper = source("scripts/butler-fastlane-verify.cmd");

        assertTrue(verifier.contains("C:\\ButlerDev\\fastlane"));
        assertTrue(verifier.contains("'check-ref-format', '--branch', $Branch"));
        assertTrue(verifier.contains("refs/remotes/origin/$Branch"));
        assertTrue(verifier.contains("'worktree', 'list', '--porcelain'"));
        assertTrue(verifier.contains("'status', '--porcelain=v1', '--untracked-files=all'"));
        assertTrue(verifier.contains("'worktree', 'add', '--detach'"));
        assertTrue(verifier.contains("'checkout', '--detach', $targetSha"));
        assertTrue(verifier.contains("Fast Lane HEAD mismatch"));
        assertTrue(verifier.contains(":bet:bet-cli:installDist"));
        assertTrue(verifier.contains("butler-manager-journey-acceptance.cmd"));
        assertTrue(verifier.contains("BUTLER FASTLANE: PASS"));
        assertTrue(verifier.contains("BUTLER FASTLANE: BLOCKED"));
        assertFalse(verifier.contains("worktree prune"));
        assertFalse(verifier.contains("'worktree', 'prune'"));

        assertTrue(wrapper.contains("butler-fastlane-verify.ps1"));
        assertTrue(wrapper.contains("%*"));
    }

    @Test
    void playerDiscoveryAndCompareLinkExactTeamsToFranchiseScout() throws Exception {
        String compare = source("scripts/butler-app-bf906-player-compare-transform.ps1");

        assertTrue(compare.contains(
                "$teamHrefId = [System.Uri]::EscapeDataString([string]$Player.TeamId)"));
        assertTrue(compare.contains(
                "href=\"/franchise?id=$teamHrefId\">Scout franchise</a>"));
        assertTrue(compare.contains(
                "$([System.Uri]::EscapeDataString([string]$player.OwnerTeamId))"));
        assertTrue(compare.contains("Scout franchise</a></div></article>"));
    }

    @Test
    void loadedTradeOpponentLinksBackToExactFranchiseScout() throws Exception {
        String trade = source("scripts/butler-trade-lab.ps1");
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(trade.contains(
                "$opponentTeamHrefId = [System.Uri]::EscapeDataString([string]$Opponent.TeamId)"));
        assertTrue(trade.contains("/franchise?id=$opponentTeamHrefId"));
        assertTrue(trade.contains("Scout franchise"));
        assertTrue(trade.contains("$opponentScoutAction</section>"));

        assertTrue(journey.contains(
                "BF-921 FAILED: loaded Trade Analyzer did not link back to the exact Franchise Scout."));
        assertTrue(journey.contains("$tradeScoutHref -cne $franchiseHref"));
        assertTrue(journey.contains("Trade Analyzer Franchise Scout"));
    }

    @Test
    void newWindowsScriptsRemainAsciiOnly() throws Exception {
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(
                source("scripts/butler-fastlane-verify.ps1")));
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(
                source("scripts/butler-fastlane-verify.cmd")));
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
        throw new IOException("BF-921 test could not locate " + relativePath);
    }
}
