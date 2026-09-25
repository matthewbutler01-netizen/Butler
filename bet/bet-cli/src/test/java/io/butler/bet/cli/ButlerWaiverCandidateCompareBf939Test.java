package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWaiverCandidateCompareBf939Test {

    @Test
    void waiverBoardExposesCompareEntryAndExactIdFlow() throws Exception {
        String transform = source("scripts/butler-dashboard-bf939-waiver-candidate-compare-transform.ps1");

        assertTrue(transform.contains("Compare candidate"));
        assertTrue(transform.contains("function Get-WaiverCompareRequest"));
        assertTrue(transform.contains("function ConvertTo-WaiverCompareHtml"));
        assertTrue(transform.contains("First candidate selected"));
        assertTrue(transform.contains("Choose second candidate"));
        assertTrue(transform.contains("Compare with this candidate"));
        assertTrue(transform.contains("Side-by-side neutral waiver evidence"));
        assertTrue(transform.contains("Swap sides"));
    }

    @Test
    void compareUsesOnlyCurrentAuthorizedBf616Candidates() throws Exception {
        String transform = source("scripts/butler-dashboard-bf939-waiver-candidate-compare-transform.ps1");

        assertTrue(transform.contains("Get-WaiverCandidates -Bundle $Bundle"));
        assertTrue(transform.contains("Get-WaiverAuthorizedCounts -Bundle $Bundle"));
        assertTrue(transform.contains("Resolve-WaiverCandidateById -Bundle $Bundle -SleeperId $Request.LeftId"));
        assertTrue(transform.contains("Resolve-WaiverCandidateById -Bundle $Bundle -SleeperId $Request.RightId"));
        assertTrue(transform.contains("is not in the current BF-616 authorized shortlist"));
        assertTrue(transform.contains("requires two different exact candidate ids"));
        assertTrue(transform.contains("NOT A RANKING"));
    }

    @Test
    void appShellAllowsCompareAndPreservesItsQueryString() throws Exception {
        String transform = source("scripts/butler-dashboard-bf939-waiver-candidate-compare-transform.ps1");

        assertTrue(transform.contains("$waiverCompare = $path -eq \"/waivers/compare\""));
        assertTrue(transform.contains("$path -eq \"/waivers/compare\") { $parts[1] }"));
        assertTrue(transform.contains("Invoke-GovernedDashboardGet -InnerPort $innerPort -Path $dashboardRequestTarget"));
    }

    @Test
    void dashboardRouteUsesOnlyExistingWaiverBoardRead() throws Exception {
        String transform = source("scripts/butler-dashboard-bf939-waiver-candidate-compare-transform.ps1");

        assertTrue(transform.contains("$waiverBundle = Invoke-ButlerReadOnlyWaiverBoard"));
        assertTrue(transform.contains("$html = ConvertTo-WaiverCompareHtml -Bundle $waiverBundle -Request $compareRequest"));
        assertFalse(transform.contains("Invoke-ButlerReadOnlySummary"));
        assertFalse(transform.contains("Invoke-ButlerReadOnlyRosterContext"));
    }

    @Test
    void stagingRunsAfterFinalPresentationPolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf938 = staging.indexOf("& $bf938Transform -DashboardPath $DashboardPath -CorePath $stagedCore");
        int bf939 = staging.indexOf("& $bf939Transform -DashboardPath $DashboardPath -CorePath $stagedCore");
        int bf857 = staging.indexOf("# BF-857: diagnostic-only inner-core timing");

        assertTrue(bf938 >= 0);
        assertTrue(bf939 > bf938);
        assertTrue(bf857 > bf939);
    }

    @Test
    void managerJourneyExercisesCompareWhenEvidenceAllows() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("Waiver Candidate Compare start"));
        assertTrue(journey.contains("Waiver Candidate Compare result"));
        assertTrue(journey.contains("Compare with this candidate"));
        assertTrue(journey.contains("Side-by-side neutral waiver evidence"));
        assertTrue(journey.contains("Write-Pass -Label 'Waiver Candidate Compare'"));
        assertTrue(journey.contains("current BF-616 review pool has no second authorized candidate"));
    }

    @Test
    void featureRemainsAsciiGetOnlyAndReadOnly() throws Exception {
        String transform = source("scripts/butler-dashboard-bf939-waiver-candidate-compare-transform.ps1");

        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(transform));
        for (String forbidden : new String[]{
                "Invoke-RestMethod",
                "Invoke-WebRequest",
                "https://api.sleeper.app",
                "Method = \"POST\"",
                "submitTransaction",
                "setFaab"
        }) {
            assertFalse(transform.contains(forbidden), "BF-939 introduced forbidden action " + forbidden);
        }
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
        throw new IOException("BF-939 test could not locate " + relativePath);
    }
}
