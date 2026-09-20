package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerFranchiseDetailBf880Test {

    @Test
    void leagueLeaderNamesLinkThroughExactExistingButlerTeamIds() throws Exception {
        String transform = source("scripts/butler-app-bf880-franchise-detail-transform.ps1");

        assertTrue(transform.contains("ConvertTo-FranchiseLeaderNameHtml"));
        assertTrue(transform.contains("$teamId = [string]$Leader.TeamId"));
        assertTrue(transform.contains("href=\"/franchise?id="));
        assertTrue(transform.contains("return $name"));
        assertTrue(transform.contains("expected exactly one governed League leader-name render site"));
    }

    @Test
    void franchiseRouteRequiresOneSafeExactTeamIdAndUsesOnlyReadOnlyEvidenceCli() throws Exception {
        String transform = source("scripts/butler-app-bf880-franchise-detail-transform.ps1");
        String route = hereString(transform, "$franchiseRoute = @'", "'@\n\n$core = $core.Insert");

        assertTrue(transform.contains("^[A-Za-z0-9._:-]+$"));
        assertTrue(route.contains("if ($path -eq \"/franchise\")"));
        assertTrue(route.contains("Get-FranchiseDetailRequestId -RequestTarget $parts[1]"));
        assertTrue(route.contains("league franchise-detail $LeagueId $teamId"));
        assertTrue(route.contains("$franchiseDetail.TeamId -cne $teamId"));
        assertTrue(route.contains("$franchiseDetail.LeagueId -cne $LeagueId"));

        assertFalse(route.contains("Invoke-RestMethod"));
        assertFalse(route.contains("Invoke-WebRequest"));
        assertFalse(route.contains("Method = \"POST\""));
        assertFalse(route.contains("submitTransaction"));
        assertFalse(route.contains("setFaab"));
    }

    @Test
    void managerCopyPreservesCoverageStalenessAndMissingness() throws Exception {
        String transform = source("scripts/butler-app-bf880-franchise-detail-transform.ps1");

        assertTrue(transform.contains("Coverage and missingness"));
        assertTrue(transform.contains("stale $(ConvertTo-HtmlText $View.AssetStale)"));
        assertTrue(transform.contains("missing $(ConvertTo-HtmlText $View.AssetMissing)"));
        assertTrue(transform.contains("stale $(ConvertTo-HtmlText $View.RosterStale)"));
        assertTrue(transform.contains("missing $(ConvertTo-HtmlText $View.RosterMissing)"));
        assertTrue(transform.contains("stale $(ConvertTo-HtmlText $View.DraftStale)"));
        assertTrue(transform.contains("missing $(ConvertTo-HtmlText $View.DraftMissing)"));
        assertTrue(transform.contains("does not fill evidence gaps with assumed values"));
    }

    @Test
    void positionalAndConcentrationEvidenceStayDescriptiveAndUnranked() throws Exception {
        String transform = source("scripts/butler-app-bf880-franchise-detail-transform.ps1");

        assertTrue(transform.contains("Concentration is descriptive context only"));
        assertTrue(transform.contains("Position cards are descriptive and alphabetic"));
        assertTrue(transform.contains("They are not ranked, weighted, or converted into a roster recommendation"));
        assertTrue(transform.contains("<summary>Evidence source</summary>"));
        assertTrue(transform.contains("does not create a new franchise ranking"));
        assertTrue(transform.contains("contender/rebuilder label"));
        assertTrue(transform.contains("manager grade"));
        assertTrue(transform.contains("strategy recommendation"));
    }

    @Test
    void stagingRunsFranchiseDetailAfterPlayerDetailAndBeforeDashboardHostedTransforms() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf879 = staging.indexOf("& $bf879CoreTransform -CorePath $stagedCore");
        int bf880 = staging.indexOf("& $bf880CoreTransform -CorePath $stagedCore");
        int dashboardVisual = staging.indexOf("& $bf837DashboardTransform -DashboardPath $DashboardPath");

        assertTrue(bf879 >= 0);
        assertTrue(bf880 > bf879);
        assertTrue(dashboardVisual > bf880);
        assertTrue(staging.contains("butler-app-bf880-franchise-detail-transform.ps1"));
    }

    @Test
    void transformSourceIsAsciiOnly() throws Exception {
        String transform = source("scripts/butler-app-bf880-franchise-detail-transform.ps1");
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
        throw new IOException("BF-880 test could not locate " + relativePath);
    }
}
