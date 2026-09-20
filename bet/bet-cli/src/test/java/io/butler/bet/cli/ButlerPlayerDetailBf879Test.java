package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPlayerDetailBf879Test {

    @Test
    void onlyExactCanonicalMyTeamPlayersReceivePlayerDetailLinks() throws Exception {
        String transform = source("scripts/butler-app-bf879-player-detail-transform.ps1");

        assertTrue(transform.contains("[string]$Player.Mapping -ceq 'EXACT_CANONICAL'"));
        assertTrue(transform.contains("href=\"/player?id=$hrefId\""));
        assertTrue(transform.contains("return ConvertTo-HtmlText $Player.Name"));
        assertTrue(transform.contains("expected exactly three My Team player-name render sites"));
        assertFalse(transform.contains("UNMAPPED_CANONICAL' -and"));
    }

    @Test
    void playerRouteRequiresOneExactIdAndUsesOnlyReadOnlyEvidenceCli() throws Exception {
        String transform = source("scripts/butler-app-bf879-player-detail-transform.ps1");
        String route = hereString(transform, "$playerRoute = @'", "'@\n\n$core = $core.Insert");

        assertTrue(route.contains("if ($path -eq \"/player\")"));
        assertTrue(route.contains("Get-PlayerDetailRequestId -RequestTarget $parts[1]"));
        assertTrue(route.contains("league player-detail $LeagueId $playerId"));
        assertTrue(route.contains("$playerDetail.PlayerId -cne $playerId"));
        assertTrue(route.contains("$playerDetail.LeagueId -cne $LeagueId"));

        assertFalse(route.contains("Invoke-RestMethod"));
        assertFalse(route.contains("Invoke-WebRequest"));
        assertFalse(route.contains("Method = \"POST\""));
        assertFalse(route.contains("player-score"));
        assertFalse(route.contains("refresh"));
        assertFalse(route.contains("submitTransaction"));
    }

    @Test
    void missingAndZeroGameProductionRemainDistinctInManagerCopy() throws Exception {
        String transform = source("scripts/butler-app-bf879-player-detail-transform.ps1");

        assertTrue(transform.contains("ProductionSnapshot -ceq 'MISSING'"));
        assertTrue(transform.contains("No persisted production snapshot is available"));
        assertTrue(transform.contains("GamesPlayed -ceq '0'"));
        assertTrue(transform.contains("0 games played"));
        assertTrue(transform.contains("Per-game rates remain unavailable rather than being converted to zero"));
        assertTrue(transform.contains("Unavailable rates stay unavailable"));
    }

    @Test
    void supportingFlagsRemainContextOnlyAndTechnicalEvidenceIsDisclosed() throws Exception {
        String transform = source("scripts/butler-app-bf879-player-detail-transform.ps1");

        assertTrue(transform.contains("Optional governed flags are shown as context only."));
        assertTrue(transform.contains("They are not weighted into a player score or recommendation."));
        assertTrue(transform.contains("<summary>Evidence sources</summary>"));
        assertTrue(transform.contains("universal player score, grade, rank, buy/sell label"));
        assertTrue(transform.contains("start/sit recommendation, trade recommendation, waiver recommendation"));
    }

    @Test
    void stagingRunsPlayerDetailAfterFinalMyTeamPolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf872 = staging.indexOf("& $bf872CoreTransform -CorePath $stagedCore");
        int bf879 = staging.indexOf("& $bf879CoreTransform -CorePath $stagedCore");
        int dashboardVisual = staging.indexOf("& $bf837DashboardTransform -DashboardPath $DashboardPath");

        assertTrue(bf872 >= 0);
        assertTrue(bf879 > bf872);
        assertTrue(dashboardVisual > bf879);
        assertTrue(staging.contains("butler-app-bf879-player-detail-transform.ps1"));
    }

    @Test
    void transformSourceIsAsciiOnly() throws Exception {
        String transform = source("scripts/butler-app-bf879-player-detail-transform.ps1");
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
        throw new IOException("BF-879 test could not locate " + relativePath);
    }
}
