package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLineupSlotPlacementDecisionCountBf1010Test {

    @Test
    void slotPlacementsBecomeSupportingContextWhenTheyDoNotEqualManagerMoves() throws Exception {
        String transform = source("scripts/butler-app-bf1010-lineup-slot-placement-decision-count-transform.ps1");

        assertTrue(transform.contains("$aggregateSlotPlacements = $slotChangeCount -gt 0 -and $slotChangeCount -ne $managerMoveCount"));
        assertTrue(transform.contains("Optimizer slot placement evidence"));
        assertTrue(transform.contains("not separate decisions"));
        assertTrue(transform.contains("Supporting lineup evidence"));
        assertTrue(transform.contains("slot-placement comparison collapse"));
        assertTrue(transform.contains("slot-placement-review"));
        assertTrue(transform.contains("Slot placement evidence:"));
    }

    @Test
    void bf953ChainsTheFinalDecisionCountRepair() throws Exception {
        String transform = source("scripts/butler-app-bf953-lineup-hold-expert-merge-transform.ps1");

        assertTrue(transform.contains("butler-app-bf1010-lineup-slot-placement-decision-count-transform.ps1"));
        assertTrue(transform.contains("& $bf1010Transform -CorePath $CorePath"));
    }

    @Test
    void acceptanceCoversRealUseZeroMoveAndOneMoveMismatch() throws Exception {
        String acceptance = source("scripts/butler-bf1010-lineup-slot-placement-decision-count-acceptance.ps1");

        assertTrue(acceptance.contains(">4 ITEMS</span>"));
        assertTrue(acceptance.contains(">5 ITEMS</span>"));
        assertTrue(acceptance.contains("Emeka Egbuka"));
        assertTrue(acceptance.contains("Jauan Jennings"));
        assertTrue(acceptance.contains("Drake Maye"));
    }

    @Test
    void presentationCloseoutIncludesBf1010() throws Exception {
        String closeout = source("scripts/butler-presentation-closeout-acceptance.ps1");
        assertTrue(closeout.contains("Id = 'BF-1010'"));
        assertTrue(closeout.contains("butler-bf1010-lineup-slot-placement-decision-count-acceptance.ps1"));
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
        throw new IOException("BF-1010 test could not locate " + relativePath);
    }
}
