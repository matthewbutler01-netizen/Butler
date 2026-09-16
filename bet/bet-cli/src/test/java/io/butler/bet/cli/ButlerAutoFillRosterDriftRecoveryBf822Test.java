package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAutoFillRosterDriftRecoveryBf822Test {

    @Test
    void rosterMembershipDriftBecomesManagerFacingRecoveryInsteadOfRawFailure() throws Exception {
        String transform = source("scripts/butler-app-bf822-autofill-roster-drift-recovery-transform.ps1");

        assertTrue(transform.contains(
            "BF-610 BLOCKED: current roster membership drifted from BF-603/BF-602 frame"));
        assertTrue(transform.contains("Your roster changed"));
        assertTrue(transform.contains("REFRESH EVIDENCE"));
        assertTrue(transform.contains("Refresh Butler Evidence"));
        assertTrue(transform.contains("href=\"/refresh\""));
        assertTrue(transform.contains("Back to My Team"));
        assertTrue(transform.contains("Technical details"));
        assertTrue(transform.contains("explicit confirmation"));
        assertTrue(transform.contains("-StatusCode 409 -StatusText \"Conflict\""));
    }

    @Test
    void recoveryDoesNotHideWritesInsideTheAutoFillGet() throws Exception {
        String transform = source("scripts/butler-app-bf822-autofill-roster-drift-recovery-transform.ps1");

        assertFalse(transform.contains(":bet:bet-cli:sleeperLiveWaiverSnapshotSync"));
        assertFalse(transform.contains(":bet:bet-cli:sleeperLiveWaiverMarketAttentionSync"));
        assertFalse(transform.contains("Invoke-DecisionRefreshRunner"));
        assertFalse(transform.contains("& $gradle"));
        assertTrue(transform.contains("href=\"/refresh\""));
        assertTrue(transform.contains("does not refresh evidence automatically"));
    }

    @Test
    void unrelatedAutoFillFailuresKeepExistingFailClosedFallback() throws Exception {
        String transform = source("scripts/butler-app-bf822-autofill-roster-drift-recovery-transform.ps1");

        assertTrue(transform.contains("Butler AutoFill view blocked"));
        assertTrue(transform.contains("No Butler or Sleeper write was executed."));
        assertTrue(transform.contains("-StatusCode 500 -StatusText \"Internal Server Error\""));
    }

    @Test
    void bf610FailClosedRosterGuardRemainsIntact() throws Exception {
        String audit = source(
            "bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperLiveWaiverTargetRosterContextAudit.java");

        assertTrue(audit.contains("if (!currentRosteredIds.equals(frame.rosteredPlayerIds()))"));
        assertTrue(audit.contains(
            "BF-610 BLOCKED: current roster membership drifted from BF-603/BF-602 frame"));
        assertTrue(audit.contains("refresh BF-602/BF-603 and downstream live evidence"));
    }

    @Test
    void existingGovernedRefreshStillRequiresExplicitTokenGatedPost() throws Exception {
        String shell = source("scripts/butler-app-shell.ps1");

        assertTrue(shell.contains("if ($parts[0] -eq 'POST')"));
        assertTrue(shell.contains("if ($requestTarget -cne '/refresh')"));
        assertTrue(shell.contains("$submittedToken -cne $decisionRefreshToken"));
        assertTrue(shell.contains("$decisionRefreshToken = New-DecisionRefreshToken"));
        assertTrue(shell.contains("Invoke-DecisionRefreshRunner"));
    }

    @Test
    void bf818StagesRecoveryAfterLineupAdvisorAndBeforeManagerDashboardPass() throws Exception {
        String transform = source("scripts/butler-dashboard-bf818-waiver-advisor-transform.ps1");

        int write = transform.indexOf("[System.IO.File]::WriteAllText($DashboardPath");
        int bf822 = transform.indexOf("butler-app-bf822-autofill-roster-drift-recovery-transform.ps1");
        int bf819 = transform.indexOf("butler-dashboard-bf819-manager-proof-mode-transform.ps1");

        assertTrue(write >= 0 && bf822 > write && bf819 > bf822,
            "BF-822 must run after BF-818 writes the dashboard and before BF-819 final dashboard staging");
        assertTrue(transform.contains("'butler-app-shell-core-single.ps1'"));
    }

    @Test
    void bf822WindowsSourceIsAsciiOnly() throws Exception {
        String transform = source("scripts/butler-app-bf822-autofill-roster-drift-recovery-transform.ps1");
        byte[] encoded = transform.getBytes(StandardCharsets.US_ASCII);
        assertEquals(transform, new String(encoded, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-822 test could not locate " + relativePath);
    }
}
