package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLineupAvailabilityEvidenceBf825Test {

    @Test
    void bf817StagesBf825AfterRecoveryAndBeforeSiblingWaiverAdvisor() throws Exception {
        String bf817 = source("scripts/butler-app-bf817-lineup-advisor-transform.ps1");

        int bf823 = bf817.indexOf("& $bf823Transform -CorePath $CorePath");
        int bf825 = bf817.indexOf("& $bf825Transform -CorePath $CorePath");
        int bf818 = bf817.indexOf("& $bf818Transform -DashboardPath $stagedDashboard");

        assertTrue(bf823 >= 0);
        assertTrue(bf825 > bf823);
        assertTrue(bf818 > bf825);
        assertTrue(bf817.contains("butler-app-bf825-lineup-availability-transform.ps1"));
    }

    @Test
    void transformCarriesExplicitAvailabilityEvidenceIntoReadyLineupView() throws Exception {
        String transform = source("scripts/butler-app-bf825-lineup-availability-transform.ps1");

        assertTrue(transform.contains("Availability exclusions:"));
        assertTrue(transform.contains("AvailabilityExclusions"));
        assertTrue(transform.contains("status=(?<status>.*?)"));
        assertTrue(transform.contains("injury_status=(?<injury>.*?)"));
        assertTrue(transform.contains("Availability evidence"));
        assertTrue(transform.contains("exact current Sleeper status evidence"));
        assertTrue(transform.contains("no zero projection was invented"));
    }

    @Test
    void transformRemainsPresentationOnlyAndReadOnly() throws Exception {
        String transform = source("scripts/butler-app-bf825-lineup-availability-transform.ps1");

        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("AutoFillLineupOptimizer"));
        assertFalse(transform.contains("SleeperClient"));
        assertFalse(transform.contains("$env:"));
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
        throw new IOException("BF-825 test could not locate " + relativePath);
    }
}
