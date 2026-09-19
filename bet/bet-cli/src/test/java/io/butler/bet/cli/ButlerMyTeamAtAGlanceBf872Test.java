package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerMyTeamAtAGlanceBf872Test {

    @Test
    void firstScanKeepsFourGovernedDimensionsWithoutInlineEvidenceParagraphs() throws Exception {
        String transform = source("scripts/butler-app-bf872-my-team-at-a-glance-transform.ps1");
        String metrics = hereString(transform, "$metricsNew = @'", "'@\n$teamBlock");

        for (String label : new String[] {
            "Franchise rank",
            "Roster strength",
            "Team direction",
            "Draft capital"
        }) {
            assertTrue(metrics.contains("<span class=\"metric-label\">" + label + "</span>"));
        }

        String firstScan = metrics.substring(0, metrics.indexOf("<details class=\"roster-intelligence-details\">"));
        assertFalse(firstScan.contains("$rankEvidence"));
        assertFalse(firstScan.contains("$strengthEvidence"));
        assertFalse(firstScan.contains("$postureEvidence"));
        assertFalse(firstScan.contains("$capitalEvidence"));
    }

    @Test
    void disclosurePreservesAllExistingRosterEvidence() throws Exception {
        String transform = source("scripts/butler-app-bf872-my-team-at-a-glance-transform.ps1");
        String metrics = hereString(transform, "$metricsNew = @'", "'@\n$teamBlock");

        assertTrue(metrics.contains("How Butler reads this roster"));
        assertTrue(metrics.contains("$(ConvertTo-HtmlText $rankEvidence)"));
        assertTrue(metrics.contains("$(ConvertTo-HtmlText $strengthEvidence)"));
        assertTrue(metrics.contains("$(ConvertTo-HtmlText $postureEvidence)"));
        assertTrue(metrics.contains("$(ConvertTo-HtmlText $capitalEvidence)"));
        assertTrue(metrics.contains("Franchise value $(ConvertTo-HtmlText $Context.Total)"));
        assertTrue(metrics.contains("player value $(ConvertTo-HtmlText $Context.Players)"));
        assertTrue(metrics.contains("pick value $(ConvertTo-HtmlText $Context.Picks)"));
    }

    @Test
    void passiveMyTeamOffersExplicitMatchupAndLineupActionsOnly() throws Exception {
        String transform = source("scripts/butler-app-bf872-my-team-at-a-glance-transform.ps1");

        assertTrue(transform.contains("href=\"/matchup\">Review Matchup</a>"));
        assertTrue(transform.contains("href=\"/matchup/autofill\">Review Lineup</a>"));
        assertTrue(transform.contains(
            "Lineup projections are requested only after you choose Review Lineup. My Team remains passive and read only."));

        int safetyScan = transform.indexOf("$installedTeam -match");
        assertTrue(safetyScan > 0);
        String operational = transform.substring(0, safetyScan);
        assertFalse(operational.contains("Invoke-RestMethod"));
        assertFalse(operational.contains("Invoke-WebRequest"));
        assertFalse(operational.contains("AutoFillLineupOptimizer"));
        assertFalse(operational.contains("Method = \"POST\""));
        assertFalse(operational.contains("submitTransaction"));
        assertFalse(operational.contains("setFaab"));
    }

    @Test
    void stagingRunsAfterWeeklyMatchupAndPersistentWorkerCoreWork() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf840 = staging.indexOf("& $bf840Transform -CorePath $stagedCore");
        int bf852 = staging.indexOf("& $bf852Transform -CorePath $stagedCore -DashboardPath $DashboardPath");
        int bf872 = staging.indexOf("& $bf872CoreTransform -CorePath $stagedCore");
        int dashboardVisual = staging.indexOf("& $bf837DashboardTransform -DashboardPath $DashboardPath");

        assertTrue(bf840 >= 0);
        assertTrue(bf872 > bf840);
        assertTrue(bf852 < 0 || bf872 > bf852);
        assertTrue(dashboardVisual > bf872);
        assertTrue(staging.contains("butler-app-bf872-my-team-at-a-glance-transform.ps1"));
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
        throw new IOException("BF-872 test could not locate " + relativePath);
    }
}
