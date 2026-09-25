package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTeamPositionInventoryBf934Test {

    @Test
    void myTeamCountsOnlyExistingRosterPlayersByCorePosition() throws Exception {
        String transform = source("scripts/butler-app-bf934-team-position-inventory-transform.ps1");

        assertTrue(transform.contains("$qbRosterCount = @($Roster.Players | Where-Object"));
        assertTrue(transform.contains("$rbRosterCount = @($Roster.Players | Where-Object"));
        assertTrue(transform.contains("$wrRosterCount = @($Roster.Players | Where-Object"));
        assertTrue(transform.contains("$teRosterCount = @($Roster.Players | Where-Object"));
        assertTrue(transform.contains("[string]$_.Position -ceq \"QB\""));
        assertTrue(transform.contains("[string]$_.Position -ceq \"RB\""));
        assertTrue(transform.contains("[string]$_.Position -ceq \"WR\""));
        assertTrue(transform.contains("[string]$_.Position -ceq \"TE\""));
    }

    @Test
    void rosterHubRendersCompactDescriptiveInventory() throws Exception {
        String transform = source("scripts/butler-app-bf934-team-position-inventory-transform.ps1");

        assertTrue(transform.contains("aria-label=\"Core position inventory\""));
        assertTrue(transform.contains("<span>QB</span><strong>$qbRosterCount</strong>"));
        assertTrue(transform.contains("<span>RB</span><strong>$rbRosterCount</strong>"));
        assertTrue(transform.contains("<span>WR</span><strong>$wrRosterCount</strong>"));
        assertTrue(transform.contains("<span>TE</span><strong>$teRosterCount</strong>"));
        assertTrue(transform.contains("Roster inventory only."));
        assertTrue(transform.contains("positional-pressure cards remain the interpretation layer."));
        assertTrue(transform.contains(".roster-inventory{display:grid"));
    }

    @Test
    void stagingKeepsInventoryAfterPositionWorkflowsBeforeRecoveryPolish() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf932 = staging.indexOf("& $bf932CoreTransform -CorePath $stagedCore");
        int bf934 = staging.indexOf("& $bf934CoreTransform -CorePath $stagedCore");
        int bf884 = staging.indexOf("& $bf884CoreTransform -CorePath $stagedCore");

        assertTrue(bf932 >= 0 && bf934 > bf932 && bf884 > bf934);
    }

    @Test
    void managerJourneyRequiresRosterInventory() throws Exception {
        String journey = source("scripts/butler-manager-journey-acceptance.ps1");

        assertTrue(journey.contains("My Team roster inventory"));
        assertTrue(journey.contains("aria-label=\"Core position inventory\""));
        assertTrue(journey.contains("<span>QB</span>"));
        assertTrue(journey.contains("<span>RB</span>"));
        assertTrue(journey.contains("<span>WR</span>"));
        assertTrue(journey.contains("<span>TE</span>"));
    }

    @Test
    void bf934RemainsReadOnlyPresentationOnly() throws Exception {
        String transform = source("scripts/butler-app-bf934-team-position-inventory-transform.ps1");

        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("Invoke-WebRequest"));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("submitTransaction"));
        assertFalse(transform.contains("setFaab"));
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
        throw new IOException("BF-934 test could not locate " + relativePath);
    }
}
