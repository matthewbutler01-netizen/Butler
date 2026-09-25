package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLineupSwapCompareBf941Test {

    @Test
    void changedAutoFillSlotsExposeExactPlayerCompareLoop() throws Exception {
        String transform = source("scripts/butler-app-bf941-lineup-swap-compare-transform.ps1");

        assertTrue(transform.contains("function Get-LineupSwapCompareHref"));
        assertTrue(transform.contains("if (-not $Assignment.Changed"));
        assertTrue(transform.contains("$Roster.Players"));
        assertTrue(transform.contains("$_.SleeperId -ceq [string]$Assignment.CurrentId"));
        assertTrue(transform.contains("$_.SleeperId -ceq [string]$Assignment.RecommendedId"));
        assertTrue(transform.contains("ButlerPlayerId"));
        assertTrue(transform.contains("/compare?left=$leftHref&right=$rightHref"));
        assertTrue(transform.contains("Compare this swap"));
    }

    @Test
    void mappingFailsClosedForMissingAmbiguousOrUnsafeIdentity() throws Exception {
        String transform = source("scripts/butler-app-bf941-lineup-swap-compare-transform.ps1");

        assertTrue(transform.contains("$currentMatches.Count -ne 1"));
        assertTrue(transform.contains("$recommendedMatches.Count -ne 1"));
        assertTrue(transform.contains("$playerId -ceq 'none'"));
        assertTrue(transform.contains("$playerId.Length -gt 128"));
        assertTrue(transform.contains("$playerId -notmatch '^[A-Za-z0-9._:-]+$'"));
        assertTrue(transform.contains("if ($left -ceq $right) { return '' }"));
    }

    @Test
    void myTeamSuppliesVerifiedRosterButSharedRendererKeepsRosterOptional() throws Exception {
        String transform = source("scripts/butler-app-bf941-lineup-swap-compare-transform.ps1");

        assertTrue(transform.contains("$Roster = $null"));
        assertTrue(transform.contains("ConvertTo-AutoFillHtml -AutoFill $AutoFill -Roster $Roster"));
        assertFalse(transform.contains("ConvertTo-MatchupAutoFillHtml -AutoFill $AutoFill -Roster"));
    }

    @Test
    void stagingRunsAfterBf940AndBeforeDiagnosticTiming() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf940 = staging.indexOf("& $bf940Transform -DashboardPath $DashboardPath -CorePath $stagedCore");
        int bf941 = staging.indexOf("& $bf941Transform -CorePath $stagedCore");
        int bf857 = staging.indexOf("# BF-857: diagnostic-only inner-core timing");

        assertTrue(bf940 >= 0, "BF-940 staging marker missing");
        assertTrue(bf941 > bf940, "BF-941 must run after BF-940");
        assertTrue(bf857 > bf941, "BF-857 timing must remain after BF-941");
    }

    @Test
    void featureAddsNavigationOnlyAndNoNewReadOrWritePath() throws Exception {
        String transform = source("scripts/butler-app-bf941-lineup-swap-compare-transform.ps1");

        assertFalse(transform.contains("Invoke-ButlerReadOnlyTask"));
        assertFalse(transform.contains("Invoke-Bf742DashboardWorkerRead"));
        assertTrue(transform.contains("lineup swap compare introduced forbidden behavior"));
        assertTrue(transform.contains("'Invoke-RestMethod'"));
        assertTrue(transform.contains("'Invoke-WebRequest'"));
        assertTrue(transform.contains("'Method = \"POST\"'"));
        assertTrue(transform.contains("'submitTransaction'"));
        assertTrue(transform.contains("'setFaab'"));
    }

    @Test
    void transformRemainsAscii() throws Exception {
        String transform = source("scripts/butler-app-bf941-lineup-swap-compare-transform.ps1");
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
        throw new IOException("BF-941 test could not locate " + relativePath);
    }
}
