package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPlayerCompareBf906Test {

    @Test
    void playerCompareUsesExactTwoStepRosteredPlayerFlow() throws Exception {
        String transform = source("scripts/butler-app-bf906-player-compare-transform.ps1");

        assertTrue(transform.contains("function Get-PlayerCompareRequest"));
        assertTrue(transform.contains("Player Compare requires exact Butler player IDs"));
        assertTrue(transform.contains("Player Compare requires two different exact players"));
        assertTrue(transform.contains("league player-detail $LeagueId $($compareRequest.LeftPlayerId)"));
        assertTrue(transform.contains("league player-search $LeagueId $($compareRequest.Query)"));
        assertTrue(transform.contains(
            "league player-compare $LeagueId $($compareRequest.LeftPlayerId) $($compareRequest.RightPlayerId)"));
        assertTrue(transform.contains("response does not match the exact requested players"));
    }

    @Test
    void playerSearchAndDetailExposeSecondaryCompareActionsWithoutPrimaryNavChange() throws Exception {
        String transform = source("scripts/butler-app-bf906-player-compare-transform.ps1");

        assertTrue(transform.contains("/compare?left=$hrefId"));
        assertTrue(transform.contains("Compare this player"));
        assertTrue(transform.contains("/compare?left=$leftHref&right=$rightHref"));
        assertFalse(transform.contains("function Get-AppNav {"));
        assertFalse(transform.contains("Get-AppNav -Active 'compare'"));
        assertFalse(transform.contains("Player Compare</a></nav>"));
    }

    @Test
    void comparisonIsEvidenceOnlyAndProgressivelyDisclosesSupportingDetail() throws Exception {
        String transform = source("scripts/butler-app-bf906-player-compare-transform.ps1");

        assertTrue(transform.contains("NOT A RANKING"));
        assertTrue(transform.contains("Butler does not choose a winner"));
        assertTrue(transform.contains("Per-game production"));
        assertTrue(transform.contains("<details><summary>Supporting evidence</summary>"));
        assertTrue(transform.contains("Market value"));
        assertTrue(transform.contains("Age"));
        assertTrue(transform.contains("Games"));
        assertTrue(transform.contains("does not select a better player"));
        assertTrue(transform.contains("does not create a winner, score, grade, buy/sell label, or recommendation"));
    }

    @Test
    void compareRouteIsGetOnlyAndIntroducesNoProviderRefreshOrWritePath() throws Exception {
        String transform = source("scripts/butler-app-bf906-player-compare-transform.ps1");

        int guard = transform.indexOf("$installedStart");
        assertTrue(guard > 0, "BF-906 safety-scan boundary must remain present");
        String operational = transform.substring(0, guard);

        assertTrue(operational.contains("if ($path -eq \"/compare\")"));
        assertTrue(operational.contains("Invoke-ButlerReadOnly"));
        assertFalse(operational.contains("Invoke-RestMethod"));
        assertFalse(operational.contains("Invoke-WebRequest"));
        assertFalse(operational.contains("Method = \"POST\""));
        assertFalse(operational.contains("https://api.sleeper.app"));
        assertFalse(operational.contains("submitTransaction"));
        assertFalse(operational.contains("setFaab"));
    }

    @Test
    void playerCompareStagesAfterContextNavigationAndBeforeRecoveryPolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf883 = staging.indexOf("& $bf883CoreTransform -CorePath $stagedCore");
        int bf906 = staging.indexOf("& $bf906CoreTransform -CorePath $stagedCore");
        int bf884 = staging.indexOf("& $bf884CoreTransform -CorePath $stagedCore");

        assertTrue(bf883 >= 0);
        assertTrue(bf906 > bf883);
        assertTrue(bf884 > bf906);
        assertTrue(staging.contains("butler-app-bf906-player-compare-transform.ps1"));
    }

    @Test
    void playerCompareFailuresUseExistingManagerRecoveryPattern() throws Exception {
        String recovery = source("scripts/butler-app-bf884-manager-error-pages-transform.ps1");

        assertTrue(recovery.contains("Contract = 'Player Compare blocked page'"));
        assertTrue(recovery.contains("Player Compare unavailable"));
        assertTrue(recovery.contains("could not verify that player comparison safely"));
        assertTrue(recovery.contains("-Active \"league\""));
    }

    @Test
    void transformSourceIsAsciiOnlyAndWindowsLineEndingAgnostic() throws Exception {
        String transform = source("scripts/butler-app-bf906-player-compare-transform.ps1");
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(transform));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            }
            current = current.getParent();
        }
        throw new IOException("BF-906 test could not locate " + relativePath);
    }
}
