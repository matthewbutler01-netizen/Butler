package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWaiverCandidateManagerFirstBf931Test {

    @Test
    void candidateDetailUsesManagerFirstProgressiveDisclosure() throws Exception {
        String transform = source("scripts/butler-dashboard-bf931-waiver-candidate-manager-first-transform.ps1");

        assertTrue(transform.contains("Waiver candidate"));
        assertTrue(transform.contains("What this means"));
        assertTrue(transform.contains("Evidence snapshot"));
        assertTrue(transform.contains("Why this player is here"));
        assertTrue(transform.contains("<details><summary>Evidence and audit details</summary>"));
        assertTrue(transform.contains("Candidate Sleeper ID:"));
        assertTrue(transform.contains("Back to Waiver Board"));
        assertTrue(transform.contains(
                "$(ConvertTo-HtmlText $Candidate.Position) &middot; NFL $(ConvertTo-HtmlText $Candidate.Team)</p>"));
        assertFalse(transform.contains("<details open><summary>Evidence and audit details</summary>"));
    }

    @Test
    void stagingRunsAfterWaiverHistoryAndBeforeMobileAuthority() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf925 = staging.indexOf("& $bf925DashboardTransform -DashboardPath $DashboardPath");
        int bf931 = staging.indexOf("& $bf931DashboardTransform -DashboardPath $DashboardPath");
        int bf898 = staging.indexOf("& $bf898Transform");

        assertTrue(bf925 >= 0, "BF-925 staging marker missing");
        assertTrue(bf931 > bf925, "BF-931 must run after waiver/history loop");
        assertTrue(bf898 > bf931, "BF-898 mobile polish must remain after BF-931");
        assertTrue(staging.contains("butler-dashboard-bf931-waiver-candidate-manager-first-transform.ps1"));
    }

    @Test
    void managerJourneyExercisesExactCandidateDetailAndFirstScan() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("/waivers/candidate/[0-9]+"));
        assertTrue(journey.contains("View governed details</a>"));
        assertTrue(journey.contains("Waiver Candidate Detail first scan"));
        assertTrue(journey.contains("Candidate Sleeper ID:"));
        assertTrue(journey.contains("Candidate-supported comparators:"));
        assertTrue(journey.contains("Raw Sleeper league / roster:"));
        assertTrue(journey.contains("Write-Pass -Label 'Waiver Candidate Detail'"));
    }

    @Test
    void transformRemainsPresentationOnlyReadOnlyAndAscii() throws Exception {
        String transform = source("scripts/butler-dashboard-bf931-waiver-candidate-manager-first-transform.ps1");

        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("Invoke-WebRequest"));
        assertFalse(transform.contains("https://api.sleeper.app"));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("submitTransaction"));
        assertFalse(transform.contains("setFaab"));
        assertTrue(transform.contains("READ ONLY &middot; EXACT ID ONLY."));
        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(transform));
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
        throw new IOException("BF-931 test could not locate " + relativePath);
    }
}
