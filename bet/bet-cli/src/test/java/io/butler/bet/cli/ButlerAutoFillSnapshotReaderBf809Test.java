package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAutoFillSnapshotReaderBf809Test {

    @Test
    void readerFallsBackToFreshLocalSnapshotDiscoveryAndStableTargetIdentity() throws Exception {
        String transform = source("scripts/butler-dashboard-bf809-autofill-snapshot-reader-transform.ps1");

        assertTrue(transform.contains("Get-ChildItem -LiteralPath $directory -Filter '*.json'"));
        assertTrue(transform.contains("Sort-Object LastWriteTimeUtc -Descending"));
        assertTrue(transform.contains("ConvertTo-Bf809AutoFillTargetKey"));
        assertTrue(transform.contains("$leagueMatches -or $targetMatches"));
        assertTrue(transform.contains("Get-Bf809AutoFillSnapshot -LeagueKey ([string]$LeagueId) -TargetHuman ([string]$target)"));
        assertTrue(transform.contains("$snapshotTargetMatches = -not [string]::IsNullOrWhiteSpace($currentTargetKey)"));
    }

    @Test
    void readerRemainsProviderFreeAndReadOnly() throws Exception {
        String transform = source("scripts/butler-dashboard-bf809-autofill-snapshot-reader-transform.ps1");

        assertFalse(transform.contains("FantasyProsWeeklyProjectionProvider"));
        assertFalse(transform.contains("Invoke-RestMethod"));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("BUTLER_FANTASYPROS_API_KEY"));
        assertFalse(transform.contains("AutoFillLineupOptimizer"));
    }

    @Test
    void stagingRunsBf809OnlyAfterBf808InstallsSnapshotContract() throws Exception {
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf808 = staging.indexOf("& $bf808Transform -CorePath $stagedCore -DashboardPath $DashboardPath");
        int bf809 = staging.indexOf("& $bf809Transform -DashboardPath $DashboardPath");
        assertTrue(bf808 >= 0, "BF-808 staging must remain present");
        assertTrue(bf809 > bf808, "BF-809 must run after BF-808 installs the snapshot reader");
        assertTrue(staging.contains("butler-dashboard-bf809-autofill-snapshot-reader-transform.ps1"));
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
        throw new IOException("BF-809 test could not locate " + relativePath);
    }
}
