package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLeagueMovementPulseBf935Test {

    @Test
    void leaguePulseUsesOnlyExistingMoverEvidence() throws Exception {
        String transform = source("scripts/butler-app-bf935-league-movement-pulse-transform.ps1");

        assertTrue(transform.contains("$movementRiserCount = 0"));
        assertTrue(transform.contains("$movementFallerCount = 0"));
        assertTrue(transform.contains("foreach ($movementItem in @($View.Movers))"));
        assertTrue(transform.contains("StartsWith(\"+\", [System.StringComparison]::Ordinal)"));
        assertTrue(transform.contains("StartsWith(\"-\", [System.StringComparison]::Ordinal)"));
        assertTrue(transform.contains("[string](@($View.Movers).Count)"));
        assertTrue(transform.contains("$View.MovementCoverage"));
    }

    @Test
    void leaguePulseMakesMovementProgressivelyDisclosed() throws Exception {
        String transform = source("scripts/butler-app-bf935-league-movement-pulse-transform.ps1");

        assertTrue(transform.contains("League pulse"));
        assertTrue(transform.contains("Movement at a glance"));
        assertTrue(transform.contains("Risers"));
        assertTrue(transform.contains("Fallers"));
        assertTrue(transform.contains("Tracked movers"));
        assertTrue(transform.contains("View movement details"));
        assertTrue(transform.contains("Descriptive movement only."));
        assertTrue(transform.contains(".league-pulse-grid{display:grid"));
    }

    @Test
    void exactFranchiseActionsRemainPresent() throws Exception {
        String transform = source("scripts/butler-app-bf935-league-movement-pulse-transform.ps1");

        assertTrue(transform.contains("href=\"/franchise?id=$leaderHrefId\">Scout franchise</a>"));
        assertTrue(transform.contains("href=\"/trade?opponent=$leaderHrefId\">Open Trade Analyzer</a>"));
    }

    @Test
    void stagingRunsPulseAfterExactTradeContext() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf927 = staging.indexOf("& $bf927CoreTransform -CorePath $stagedCore");
        int bf935 = staging.indexOf("& $bf935CoreTransform -CorePath $stagedCore");
        int bf929 = staging.indexOf("& $bf929CoreTransform -CorePath $stagedCore");

        assertTrue(bf927 >= 0 && bf935 > bf927 && bf929 > bf935);
    }

    @Test
    void managerJourneyRequiresLeaguePulse() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("League pulse"));
        assertTrue(journey.contains("Movement at a glance"));
        assertTrue(journey.contains("Risers"));
        assertTrue(journey.contains("Fallers"));
        assertTrue(journey.contains("Tracked movers"));
        assertTrue(journey.contains("View movement details"));
    }

    @Test
    void transformKeepsReadOnlySafetyGuard() throws Exception {
        String transform = source("scripts/butler-app-bf935-league-movement-pulse-transform.ps1");

        assertTrue(transform.contains("League movement pulse introduced provider, backend-read, optimizer, or write behavior."));
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
        throw new IOException("BF-935 test could not locate " + relativePath);
    }
}
