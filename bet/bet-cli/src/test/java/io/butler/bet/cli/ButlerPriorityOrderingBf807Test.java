package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPriorityOrderingBf807Test {

    @Test
    void orderingPromotesAttentionThenReviewThenNeutral() throws Exception {
        String transform = source("scripts/butler-dashboard-bf807-priority-ordering-transform.ps1");

        assertTrue(transform.contains("foreach ($attentionGroup in @(\"attention\", \"review\", \"neutral\"))"));
        assertTrue(transform.contains("\"CURRENT_AND_ACTIONABLE\" { \"attention\" }"));
        assertTrue(transform.contains("\"CURRENT_REFRESH_RECOMMENDED\" { \"attention\" }"));
        assertTrue(transform.contains("\"STALE_DO_NOT_ACT\" { \"attention\" }"));
        assertTrue(transform.contains("$lineupAttentionGroup = if ($verification.RosterOk) { \"review\" } else { \"attention\" }"));
        assertTrue(transform.contains("$tradeAttentionGroup = \"neutral\""));
        assertTrue(transform.contains("$displayPriority = \"{0:D2}\" -f ($priorityIndex + 1)"));
        assertTrue(transform.contains("priority-card primary"));
    }

    @Test
    void neutralWaiverDoesNotReceiveAttentionBucket() throws Exception {
        String transform = source("scripts/butler-dashboard-bf807-priority-ordering-transform.ps1");

        assertFalse(transform.contains("\"NO_TRANSACTION_TO_ACT_ON\" { \"attention\" }"));
        assertFalse(transform.contains("\"TRANSACTION_ALREADY_COMPLETE\" { \"attention\" }"));
        assertTrue(transform.contains("default { \"neutral\" }"));
        assertTrue(transform.contains("Order reflects Butler's existing state, not a separate priority score."));
    }

    @Test
    void orderingStaysPresentationOnlyAndStagesAfterBf806() throws Exception {
        String transform = source("scripts/butler-dashboard-bf807-priority-ordering-transform.ps1");
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        assertFalse(transform.contains("FantasyProsWeeklyProjectionProvider"));
        assertFalse(transform.contains("AutoFillLineupOptimizer"));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("Invoke-RestMethod"));

        int bf806 = staging.indexOf("& $bf806Transform -DashboardPath $DashboardPath");
        int bf807 = staging.indexOf("& $bf807Transform -DashboardPath $DashboardPath");
        assertTrue(bf806 >= 0, "BF-806 staging must remain present");
        assertTrue(bf807 > bf806, "BF-807 must run after BF-806 derives the three manager signals");
        assertTrue(staging.contains("butler-dashboard-bf807-priority-ordering-transform.ps1"));
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
        throw new IOException("BF-807 test could not locate " + relativePath);
    }
}
