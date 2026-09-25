package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWaiverRosterCompareBf940Test {

    @Test
    void waiverBoardExposesCandidateToRosterFlow() throws Exception {
        String transform = source("scripts/butler-dashboard-bf940-waiver-roster-compare-transform.ps1");

        assertTrue(transform.contains("Compare to roster"));
        assertTrue(transform.contains("function Get-WaiverRosterCompareRequest"));
        assertTrue(transform.contains("function Resolve-WaiverRosterPlayerById"));
        assertTrue(transform.contains("function ConvertTo-WaiverRosterCompareHtml"));
        assertTrue(transform.contains("Candidate selected"));
        assertTrue(transform.contains("Choose roster player"));
        assertTrue(transform.contains("Compare with this roster player"));
        assertTrue(transform.contains("Candidate vs roster context"));
        assertTrue(transform.contains("Compare another roster player"));
    }

    @Test
    void compareUsesExactAuthorizedCandidateAndVerifiedRosterPlayer() throws Exception {
        String transform = source("scripts/butler-dashboard-bf940-waiver-roster-compare-transform.ps1");

        assertTrue(transform.contains("Resolve-WaiverCandidateById -Bundle $Bundle -SleeperId $Request.CandidateId"));
        assertTrue(transform.contains("Resolve-WaiverRosterPlayerById -RosterContext $RosterContext -SleeperId $Request.RosterId"));
        assertTrue(transform.contains("is not in the current BF-616 authorized shortlist"));
        assertTrue(transform.contains("is not in the verified BF-610 target roster"));
        assertTrue(transform.contains("requires two different exact player ids"));
        assertTrue(transform.contains("Source order is preserved."));
    }

    @Test
    void compareRequiresMatchingWaiverAndRosterTargetIdentity() throws Exception {
        String transform = source("scripts/butler-dashboard-bf940-waiver-roster-compare-transform.ps1");

        assertTrue(transform.contains("Assert-WaiverRosterCompareTarget"));
        assertTrue(transform.contains("Get-WaiverTargetView -Bundle $Bundle"));
        assertTrue(transform.contains("Get-Bf623TargetView -Text $RosterContext -BoundaryName 'BF-940'"));
        assertTrue(transform.contains("SleeperLeagueId"));
        assertTrue(transform.contains("RosterId"));
        assertTrue(transform.contains("BF-616 waiver target and BF-610 roster target disagree"));
    }

    @Test
    void routeUsesOneExistingCombinedWaiverEvidenceBundle() throws Exception {
        String transform = source("scripts/butler-dashboard-bf940-waiver-roster-compare-transform.ps1");

        assertTrue(transform.contains("$waiverEvidence = Invoke-ButlerReadOnlyWaiverEvidenceBundle"));
        assertTrue(transform.contains("-Bundle $waiverEvidence.WaiverBoard -RosterContext $waiverEvidence.RosterContext"));
        assertFalse(transform.contains("Invoke-ButlerReadOnlyRosterContext"));
        assertFalse(transform.contains("Invoke-ButlerReadOnlySummary"));
    }

    @Test
    void appShellAllowsRosterCompareAndPreservesQuery() throws Exception {
        String transform = source("scripts/butler-dashboard-bf940-waiver-roster-compare-transform.ps1");

        assertTrue(transform.contains("$waiverRosterCompare = $path -eq \"/waivers/roster-compare\""));
        assertTrue(transform.contains("$path -eq \"/waivers/roster-compare\") { $parts[1] }"));
        assertTrue(transform.contains("Invoke-GovernedDashboardGet -InnerPort $innerPort -Path $dashboardRequestTarget"));
    }

    @Test
    void stagingRunsAfterWaiverCandidateCompare() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf939 = staging.indexOf("& $bf939Transform -DashboardPath $DashboardPath -CorePath $stagedCore");
        int bf940 = staging.indexOf("& $bf940Transform -DashboardPath $DashboardPath -CorePath $stagedCore");
        int bf857 = staging.indexOf("# BF-857: diagnostic-only inner-core timing");

        assertTrue(bf939 >= 0);
        assertTrue(bf940 > bf939);
        assertTrue(bf857 > bf940);
    }

    @Test
    void managerJourneyExercisesRealRosterCompareFlow() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("Waiver Roster Compare start"));
        assertTrue(journey.contains("Waiver Roster Compare result"));
        assertTrue(journey.contains("Compare with this roster player"));
        assertTrue(journey.contains("Candidate vs roster context"));
        assertTrue(journey.contains("VERIFIED ROSTER"));
        assertTrue(journey.contains("Write-Pass -Label 'Waiver Roster Compare'"));
    }

    @Test
    void installedFeatureRemainsAsciiGetOnlyAndReadOnly() throws Exception {
        String transform = source("scripts/butler-dashboard-bf940-waiver-roster-compare-transform.ps1");

        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(transform));
        int start = transform.indexOf("$compareFunctions = @'");
        int end = transform.indexOf("'@\n\n$text = $text.Insert", start);
        assertTrue(start >= 0 && end > start);
        String installedCompare = transform.substring(start, end);

        for (String forbidden : new String[]{
                "Invoke-RestMethod",
                "Invoke-WebRequest",
                "https://api.sleeper.app",
                "Method = \"POST\"",
                "submitTransaction",
                "setFaab"
        }) {
            assertFalse(installedCompare.contains(forbidden), "BF-940 introduced forbidden action " + forbidden);
        }

        assertTrue(installedCompare.contains("NOT A RANKING"));
        assertTrue(installedCompare.contains("does not create a winner"));
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
        throw new IOException("BF-940 test could not locate " + relativePath);
    }
}
