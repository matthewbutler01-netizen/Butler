package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerContextNavigationBf883Test {

    @Test
    void contextualNavigationStagesAfterPlayerSearch() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf882 = staging.indexOf("& $bf882CoreTransform -CorePath $stagedCore");
        int bf883 = staging.indexOf("& $bf883CoreTransform -CorePath $stagedCore");

        assertTrue(bf882 >= 0, "BF-882 Player Search staging must remain present");
        assertTrue(bf883 > bf882, "BF-883 contextual navigation must stage after BF-882");
        assertTrue(staging.contains("butler-app-bf883-context-navigation-transform.ps1"));
    }

    @Test
    void playerSearchUsesFixedContextMarkerAndSafeReturnActions() throws Exception {
        String transform = source("scripts/butler-app-bf883-context-navigation-transform.ps1");

        assertTrue(transform.contains("/player?id=$hrefId&from=players"));
        assertTrue(transform.contains("Back to My Team"));
        assertTrue(transform.contains("Back to League"));
        assertTrue(transform.contains("Back to Player Search"));
        assertTrue(transform.contains("(?:\\?|&)from=players(?:&|$)"));

        assertFalse(transform.contains("returnUrl"));
        assertFalse(transform.contains("redirectUrl"));
        assertFalse(transform.contains("javascript:"));
        assertFalse(transform.contains("window.history"));
    }

    @Test
    void primaryNavigationIsNotModified() throws Exception {
        String transform = source("scripts/butler-app-bf883-context-navigation-transform.ps1");

        assertFalse(transform.contains("function Get-AppNav"));
        assertFalse(transform.contains("href=\"/players\">Player Search</a></nav>"));
    }

    @Test
    void franchiseDetailRetainsSafeLeagueReturn() throws Exception {
        String franchise = source("scripts/butler-app-bf880-franchise-detail-transform.ps1");

        assertTrue(franchise.contains("href=\"/league\">Back to League</a>"));
    }

    @Test
    void overlayAddsNoProviderOrWriteBehavior() throws Exception {
        String transform = source("scripts/butler-app-bf883-context-navigation-transform.ps1");

        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("Invoke-WebRequest"));
        assertFalse(transform.contains("https://api.sleeper.app"));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("submitTransaction"));
        assertFalse(transform.contains("setFaab"));
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
        throw new IOException("BF-883 test could not locate " + relativePath);
    }
}
