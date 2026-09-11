package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf677RefreshEligibilityTest {

    @Test
    void dashboardRefreshLinkUsesOnlyExistingGovernedTechnicalFields() throws Exception {
        String refresh = script("scripts/butler-decision-refresh.ps1");
        String eligibility = eligibilitySection(refresh);

        assertTrue(eligibility.contains("function Get-DecisionRefreshTechnicalField"));
        assertTrue(eligibility.contains("[regex]::Matches($Html, $pattern)"));
        assertTrue(eligibility.contains("if ($matches.Count -ne 1) { return $null }"));
        assertTrue(eligibility.contains("HtmlDecode"));
        assertTrue(eligibility.contains("-Label 'Decision state:'"));
        assertTrue(eligibility.contains("-Label 'BF-629:'"));
        assertTrue(eligibility.contains("-Label 'BF-631:'"));

        assertFalse(eligibility.contains("Invoke-ButlerReadOnly"));
        assertFalse(eligibility.contains("sleeperLiveWaiverLatestGovernedDecisionSummary"));
        assertFalse(eligibility.contains("Ready to act"));
        assertFalse(eligibility.contains("Refresh recommended"));
        assertFalse(eligibility.contains("No governed transaction"));
    }

    @Test
    void exactNoTransactionStateExposesRefreshControl() throws Exception {
        String eligibility = eligibilitySection(script("scripts/butler-decision-refresh.ps1"));

        assertTrue(eligibility.contains("$decisionState -ceq 'NO_TRANSACTION_TO_ACT_ON'"));
        assertTrue(eligibility.contains("$bf629State -ceq 'NO_TRANSACTION_TO_REVALIDATE'"));
        assertTrue(eligibility.contains("$bf631State -ceq 'LATEST_EVIDENCE_LINEAGE_VERIFIED'"));
        assertTrue(eligibility.contains("$eligible = $true"));
        assertTrue(eligibility.contains("href=\"/refresh\">Check for a new decision"));
    }

    @Test
    void warningStateAlsoRequiresValidatedBf636TechnicalContract() throws Exception {
        String eligibility = eligibilitySection(script("scripts/butler-decision-refresh.ps1"));

        assertTrue(eligibility.contains("$decisionState -ceq 'CURRENT_REFRESH_RECOMMENDED'"));
        assertTrue(eligibility.contains("$bf629State -ceq 'LIVE_ACTIONABLE_VERIFIED'"));
        assertTrue(eligibility.contains("$bf631State -ceq 'LATEST_EVIDENCE_LINEAGE_VERIFIED'"));
        assertTrue(eligibility.contains("-Label 'BF-636 plan state:'"));
        assertTrue(eligibility.contains("-Label 'BF-636 plan policy:'"));
        assertTrue(eligibility.contains("-Label 'Governed step count:'"));
        assertTrue(eligibility.contains("$planState -ceq 'MANUAL_REFRESH_PLAN_READY'"));
        assertTrue(eligibility.contains("sleeper-live-waiver-manual-refresh-plan-v1-bf635-explicit-operator-only-no-execution"));
        assertTrue(eligibility.contains("$stepCount -ceq '9'"));
    }

    @Test
    void missingDuplicateUnknownOrIneligibleStateOmitsLinkWithoutBlockingDashboard() throws Exception {
        String eligibility = eligibilitySection(script("scripts/butler-decision-refresh.ps1"));

        assertTrue(eligibility.contains("if ($matches.Count -ne 1) { return $null }"));
        assertTrue(eligibility.contains("$eligible = $false"));
        assertTrue(eligibility.contains("if (-not $eligible) { return $Html }"));
        assertTrue(eligibility.contains("return $Html"));

        assertFalse(eligibility.contains("$decisionState -ceq 'CURRENT_AND_ACTIONABLE'"));
        assertFalse(eligibility.contains("$decisionState -ceq 'STALE_DO_NOT_ACT'"));
        assertFalse(eligibility.contains("$decisionState -ceq 'TRANSACTION_PENDING_DO_NOT_DUPLICATE'"));
        assertFalse(eligibility.contains("$decisionState -ceq 'TRANSACTION_ALREADY_COMPLETE'"));
    }

    @Test
    void bf676PostAuthorizationRemainsSeparateAndAuthoritative() throws Exception {
        String refresh = script("scripts/butler-decision-refresh.ps1");
        String shell = script("scripts/butler-app-shell.ps1");
        String worker = script("scripts/butler-app-request-worker.ps1");
        String runner = script("scripts/sleeper-live-waiver-no-transaction-refresh.ps1");

        assertTrue(refresh.contains("BF-676 POST preflight"));
        assertTrue(shell.contains("[hashtable]::Synchronized(@{ Token = $decisionRefreshToken })"));
        assertTrue(worker.contains("$SubmittedToken -cne [string]$State.Token"));
        assertTrue(worker.contains("$State.Token = New-DecisionRefreshToken"));
        assertTrue(worker.contains("Invoke-DecisionRefreshRunner -LeagueId $LeagueId -RunnerPath $DecisionRefreshRunner"));
        assertTrue(runner.contains("$decisionState -ceq 'NO_TRANSACTION_TO_ACT_ON'"));
        assertTrue(runner.contains("$decisionState -ceq 'CURRENT_REFRESH_RECOMMENDED'"));
        assertTrue(runner.contains("Assert-Bf676WarningRefreshPlan -Text $preflight"));
        assertFalse(runner.contains("create_transaction"));
        assertFalse(runner.contains("submitTransaction"));
    }

    @Test
    void bf677FilesRemainAsciiOnly() throws Exception {
        assertAscii(script("scripts/butler-decision-refresh.ps1"));
        assertAscii(script("scripts/butler-app-shell.ps1"));
        assertAscii(script("scripts/butler-app-request-worker.ps1"));
    }

    private static String eligibilitySection(String refresh) {
        int start = refresh.indexOf("function Get-DecisionRefreshTechnicalField");
        int end = refresh.indexOf("function Get-DecisionRefreshConfirmationHtml");
        assertTrue(start >= 0 && end > start, "BF-677 eligibility section is missing");
        return refresh.substring(start, end);
    }

    private static void assertAscii(String text) {
        byte[] encoded = text.getBytes(StandardCharsets.US_ASCII);
        assertEquals(text, new String(encoded, StandardCharsets.US_ASCII));
    }

    private static String script(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.US_ASCII);
            }
            current = current.getParent();
        }
        throw new IOException("BF-677 test could not locate " + relativePath);
    }
}
