package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerCompactManagerEvidenceBf938Test {

    @Test
    void batchTargetsFinalDashboardCompareAndFranchiseSurfaces() throws Exception {
        String transform = source("scripts/butler-bf938-compact-manager-evidence-transform.ps1");

        assertTrue(transform.contains("View other priorities"));
        assertTrue(transform.contains("View player evidence"));
        assertTrue(transform.contains("View franchise evidence"));
        assertTrue(transform.contains("final Player Compare evidence/action order"));
        assertTrue(transform.contains("final Franchise Scout evidence boundary"));
        assertTrue(transform.contains("final Dashboard other-priority queue boundary"));
    }

    @Test
    void compareKeepsManagerActionsAndEvidenceAvailable() throws Exception {
        String transform = source("scripts/butler-bf938-compact-manager-evidence-transform.ps1");

        assertTrue(transform.contains("Per-game production"));
        assertTrue(transform.contains("Compare with another "));
        assertTrue(transform.contains("View Player Detail"));
        assertTrue(transform.contains("Scout franchise"));
        assertTrue(transform.contains("Supporting evidence"));
    }

    @Test
    void franchiseKeepsSnapshotActionsAndDeepEvidenceAvailable() throws Exception {
        String transform = source("scripts/butler-bf938-compact-manager-evidence-transform.ps1");

        assertTrue(transform.contains("Evidence quality"));
        assertTrue(transform.contains("Value concentration"));
        assertTrue(transform.contains("Positional evidence"));
        assertTrue(transform.contains("Scout this franchise"));
        assertTrue(transform.contains("Open Trade Analyzer"));
    }

    @Test
    void stagingRunsAfterDashboardDecisionCenter() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf907 = staging.indexOf("& $bf907Transform -DashboardPath $DashboardPath");
        int bf938 = staging.indexOf("& $bf938Transform -DashboardPath $DashboardPath -CorePath $stagedCore");
        int bf857 = staging.indexOf("# BF-857: diagnostic-only inner-core timing");

        assertTrue(bf907 >= 0);
        assertTrue(bf938 > bf907);
        assertTrue(bf857 > bf938);
    }

    @Test
    void managerJourneyRequiresAllThreeDisclosures() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("View other priorities"));
        assertTrue(journey.contains("View player evidence"));
        assertTrue(journey.contains("View franchise evidence"));
    }

    @Test
    void transformRemainsAsciiAndPresentationOnly() throws Exception {
        String transform = source("scripts/butler-bf938-compact-manager-evidence-transform.ps1");

        assertTrue(StandardCharsets.US_ASCII.newEncoder().canEncode(transform));
        for (String forbidden : new String[]{
                "Invoke-RestMethod",
                "Invoke-WebRequest",
                "https://api.sleeper.app",
                "Method = \"POST\"",
                "submitTransaction",
                "setFaab"
        }) {
            assertFalse(transform.contains(forbidden), "BF-938 introduced forbidden operational marker " + forbidden);
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
        throw new IOException("BF-938 test could not locate " + relativePath);
    }
}
