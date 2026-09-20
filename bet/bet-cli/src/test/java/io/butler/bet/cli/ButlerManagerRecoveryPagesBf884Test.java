package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerManagerRecoveryPagesBf884Test {

    @Test
    void recoveryPolishStagesAfterContextNavigation() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf883 = staging.indexOf("& $bf883CoreTransform -CorePath $stagedCore");
        int bf884 = staging.indexOf("& $bf884CoreTransform -CorePath $stagedCore");

        assertTrue(bf883 >= 0, "BF-883 contextual navigation staging must remain present");
        assertTrue(bf884 > bf883, "BF-884 recovery polish must stage after BF-883");
        assertTrue(staging.contains("butler-app-bf884-manager-error-pages-transform.ps1"));
    }

    @Test
    void managerRecoveryPageUsesFixedInternalActionsAndProgressiveDetails() throws Exception {
        String transform = source("scripts/butler-app-bf884-manager-error-pages-transform.ps1");

        assertTrue(transform.contains("function New-ManagerRecoveryPageHtml"));
        assertTrue(transform.contains("Butler recovery"));
        assertTrue(transform.contains("Technical details"));
        assertTrue(transform.contains("SAFE RECOVERY."));
        assertTrue(transform.contains("href=\"/\">Dashboard</a>"));
        assertTrue(transform.contains("href=\"/team\">My Team</a>"));
        assertTrue(transform.contains("href=\"/league\">League</a>"));

        assertFalse(transform.contains("returnUrl"));
        assertFalse(transform.contains("redirectUrl"));
        assertFalse(transform.contains("javascript:"));
        assertFalse(transform.contains("window.history"));
    }

    @Test
    void blockedSurfacesRouteThroughRecoveryRenderer() throws Exception {
        String transform = source("scripts/butler-app-bf884-manager-error-pages-transform.ps1");

        for (String marker : new String[]{
                "League view unavailable",
                "My Team unavailable",
                "Butler could not complete this view",
                "Player Detail unavailable",
                "Franchise Detail unavailable",
                "Player Search unavailable",
                "Weekly Matchup unavailable"
        }) {
            assertTrue(transform.contains(marker), "BF-884 missing manager recovery copy for " + marker);
        }

        assertTrue(transform.contains("-Detail $_.Exception.Message"));
        assertTrue(transform.contains("STOPPED SAFELY"));
    }

    @Test
    void unknownRouteRemains404AndBecomesHtmlRecoveryPage() throws Exception {
        String transform = source("scripts/butler-app-bf884-manager-error-pages-transform.ps1");

        assertTrue(transform.contains("Page not found"));
        assertTrue(transform.contains("-StatusCode 404 -StatusText \"Not Found\" -ContentType \"text/html; charset=utf-8\""));
        assertFalse(transform.contains("-StatusCode 200 -StatusText \"Not Found\""));
    }

    @Test
    void recoveryHelperAddsNoProviderRetryOrWriteBehavior() throws Exception {
        String transform = source("scripts/butler-app-bf884-manager-error-pages-transform.ps1");
        int start = transform.indexOf("$helper = @'");
        int end = transform.indexOf("$marker = 'function ConvertTo-LeagueHtml {'", start);
        assertTrue(start >= 0 && end > start, "BF-884 helper boundary must remain present");
        String helper = transform.substring(start, end);

        assertFalse(helper.contains("Invoke-RestMethod"));
        assertFalse(helper.contains("Invoke-WebRequest"));
        assertFalse(helper.contains("https://api.sleeper.app"));
        assertFalse(helper.contains("Method = \"POST\""));
        assertFalse(helper.contains("submitTransaction"));
        assertFalse(helper.contains("setFaab"));
        assertFalse(helper.contains("Start-Process"));
        assertTrue(transform.contains("System.Management.Automation.Language.Parser"));
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
        throw new IOException("BF-884 test could not locate " + relativePath);
    }
}
