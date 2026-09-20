package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPlayerSearchBf882Test {

    @Test
    void emptySearchRendersWithoutInvokingBackendSearch() throws Exception {
        String transform = source("scripts/butler-app-bf882-player-search-transform.ps1");
        String route = hereString(transform, "$searchRoute = @'", "'@\n$core = $core.Insert");

        int emptyGate = route.indexOf("if ([string]::IsNullOrWhiteSpace($query))");
        int invoke = route.indexOf("league player-search $LeagueId $query");

        assertTrue(emptyGate >= 0);
        assertTrue(invoke > emptyGate);
        assertTrue(route.contains("ConvertTo-PlayerSearchHtml -Query '' -View $null"));
    }

    @Test
    void searchUsesSafeNormalizedQueryAndExistingReadOnlyCli() throws Exception {
        String transform = source("scripts/butler-app-bf882-player-search-transform.ps1");

        assertTrue(transform.contains("query must be 80 characters or fewer"));
        assertTrue(transform.contains("query contains unsupported characters"));
        assertTrue(transform.contains("^[A-Za-z0-9 ._''-]+$"));
        assertTrue(transform.contains("Invoke-ButlerReadOnly -Arguments \"league player-search $LeagueId $query\""));
        assertTrue(transform.contains("player-search response does not match the exact normalized query"));
    }

    @Test
    void resultsLinkOnlyThroughExactButlerPlayerIdsAndStayUnranked() throws Exception {
        String transform = source("scripts/butler-app-bf882-player-search-transform.ps1");

        assertTrue(transform.contains("$hrefId = [System.Uri]::EscapeDataString([string]$player.PlayerId)"));
        assertTrue(transform.contains("href=`\"/player?id=$hrefId`\""));
        assertTrue(transform.contains("NOT A RANKING"));
        assertTrue(transform.contains("Persisted value is descriptive evidence, not a ranking"));
        assertTrue(transform.contains("Fantasy-team names are context only and do not cause matches"));
        assertTrue(transform.contains("does not search free agents"));
    }

    @Test
    void playerSearchIsSecondaryAndDoesNotAddAnEighthPrimaryNavDestination() throws Exception {
        String transform = source("scripts/butler-app-bf882-player-search-transform.ps1");

        assertTrue(transform.contains("href=\"/players\">Find a player</a>"));
        assertTrue(transform.contains("href=\"/players\">Find another player</a>"));
        assertFalse(transform.contains("function Get-AppNav {"));
        assertFalse(transform.contains("Get-AppNav -Active 'players'"));
    }

    @Test
    void stagingRunsAfterMatchupPolishAndBeforeDashboardHostedTransforms() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf881 = staging.indexOf("& $bf881CoreTransform -CorePath $stagedCore");
        int bf882 = staging.indexOf("& $bf882CoreTransform -CorePath $stagedCore");
        int dashboardVisual = staging.indexOf("& $bf837DashboardTransform -DashboardPath $DashboardPath");

        assertTrue(bf881 >= 0);
        assertTrue(bf882 > bf881);
        assertTrue(dashboardVisual > bf882);
        assertTrue(staging.contains("butler-app-bf882-player-search-transform.ps1"));
    }

    @Test
    void transformSourceIsAsciiOnly() throws Exception {
        String transform = source("scripts/butler-app-bf882-player-search-transform.ps1");
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(transform));
    }

    private static String hereString(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        assertTrue(start >= 0, "start marker missing: " + startMarker);
        start += startMarker.length();
        int end = text.indexOf(endMarker, start);
        assertTrue(end > start, "end marker missing: " + endMarker);
        return text.substring(start, end);
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
        throw new IOException("BF-882 test could not locate " + relativePath);
    }
}
