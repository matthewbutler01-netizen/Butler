package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAutoFillCommandCenterBf808Test {

    @Test
    void explicitAutoFillPersistsOnlyManagerSafeSummary() throws Exception {
        String transform = source("scripts/butler-bf808-autofill-command-center-transform.ps1");

        assertTrue(transform.contains("Save-Bf808AutoFillSnapshot -AutoFill $autoFill -RosterView $rosterView"));
        assertTrue(transform.contains("Schema = 'BF-808-1'"));
        assertTrue(transform.contains("ChangedCount = [int]$changedCount"));
        assertTrue(transform.contains("GeneratedUtc = [DateTimeOffset]::UtcNow.ToString('o')"));
        assertTrue(transform.contains("$reason = $reason.Replace($providerKey, '[REDACTED]')"));
        assertTrue(transform.contains("provider credential detected in AutoFill snapshot payload"));
        assertFalse(transform.contains("SourceSurface ="));
        assertFalse(transform.contains("ApiKey ="));
        assertFalse(transform.contains("Authorization ="));
    }

    @Test
    void dashboardReusesSnapshotWithoutProviderOrSleeperCalls() throws Exception {
        String transform = source("scripts/butler-bf808-autofill-command-center-transform.ps1");

        assertTrue(transform.contains("Get-Bf808AutoFillSnapshot -LeagueKey ([string]$LeagueId)"));
        assertTrue(transform.contains("Latest AutoFill hit an evidence gap"));
        assertTrue(transform.contains("Latest AutoFill recommends $($lineupSnapshot.ChangedCount) lineup $changeWord"));
        assertTrue(transform.contains("Latest AutoFill found no lineup changes"));
        assertTrue(transform.contains("REFRESH AUTOFILL"));
        assertTrue(transform.contains("EVIDENCE GAP"));
        assertTrue(transform.contains("AUTOFILL READY"));
        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("Invoke-WebRequest"));
        assertFalse(transform.contains("Invoke-ButlerReadOnlyTask"));
        assertFalse(transform.contains("Method = \"POST\""));
    }

    @Test
    void staleOrMismatchedSnapshotCannotBecomeCurrentEvidence() throws Exception {
        String transform = source("scripts/butler-bf808-autofill-command-center-transform.ps1");

        assertTrue(transform.contains("$snapshotAgeHours -le 6.0"));
        assertTrue(transform.contains("$lineupSnapshot.TargetHuman -ceq [string]$target"));
        assertTrue(transform.contains("-or -not $verification.RosterOk"));
        assertTrue(transform.contains("$lineupSnapshotAttentionGroup = \"attention\""));
        assertTrue(transform.contains("$lineupSnapshotAttentionGroup = \"neutral\""));
    }

    @Test
    void bf808StagesAfterBf807AndKeepsMissingSnapshotFallback() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");
        String transform = source("scripts/butler-bf808-autofill-command-center-transform.ps1");

        int bf807 = staging.indexOf("& $bf807Transform -DashboardPath $DashboardPath");
        int bf808 = staging.indexOf("& $bf808Transform -CorePath $stagedCore -DashboardPath $DashboardPath");
        assertTrue(bf807 >= 0, "BF-807 staging must remain present");
        assertTrue(bf808 > bf807, "BF-808 must run after BF-807 has built the ordered queue");
        assertTrue(staging.contains("butler-bf808-autofill-command-center-transform.ps1"));
        assertTrue(transform.contains("$lineupSnapshotAttentionGroup = \"\""));
        assertTrue(transform.contains("elseif ($verification.RosterOk)"));
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
        throw new IOException("BF-808 test could not locate " + relativePath);
    }
}
