package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerFirstScanDisclosureBatchBf936Test {

    @Test
    void batchTargetsThreeExistingManagerSurfaces() throws Exception {
        String transform = source("scripts/butler-bf936-first-scan-disclosure-transform.ps1");

        assertTrue(transform.contains("View opponent context"));
        assertTrue(transform.contains("View player evidence"));
        assertTrue(transform.contains("Review authorized players"));
        assertTrue(transform.contains("first-scan progressive disclosure"));
    }

    @Test
    void existingWorkflowActionsRemainExplicitlyPreserved() throws Exception {
        String transform = source("scripts/butler-bf936-first-scan-disclosure-transform.ps1");

        assertTrue(transform.contains("Scout opponent"));
        assertTrue(transform.contains("Trade with opponent"));
        assertTrue(transform.contains("What do you want to decide?"));
        assertTrue(transform.contains("Compare this player"));
        assertTrue(transform.contains("Scout franchise"));
        assertTrue(transform.contains("Open Trade Analyzer"));
        assertTrue(transform.contains("Check Waiver Board"));
        assertTrue(transform.contains("Position focus"));
        assertTrue(transform.contains("Players Butler authorized for review"));
    }

    @Test
    void stagingRunsAfterFocusedWaiversBeforeMobilePolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf932 = staging.indexOf("& $bf932DashboardTransform -DashboardPath $DashboardPath");
        int bf936 = staging.indexOf("& $bf936Transform -DashboardPath $DashboardPath");
        int bf898 = staging.indexOf("$bf898Transform = Join-Path");

        assertTrue(bf932 >= 0 && bf936 > bf932 && bf898 > bf936);
    }

    @Test
    void managerJourneyChecksEachDisclosure() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("Matchup first-scan disclosure"));
        assertTrue(journey.contains("Player Detail first-scan disclosure"));
        assertTrue(journey.contains("Waiver Board first-scan disclosure"));
    }

    @Test
    void transformRemainsAsciiAndReadOnlyGuarded() throws Exception {
        String transform = source("scripts/butler-bf936-first-scan-disclosure-transform.ps1");

        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(transform));
        assertTrue(transform.contains("BF-936 BLOCKED"));
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
        throw new IOException("BF-936 test could not locate " + relativePath);
    }
}
