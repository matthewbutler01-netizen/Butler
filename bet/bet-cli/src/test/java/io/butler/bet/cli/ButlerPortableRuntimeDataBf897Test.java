package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPortableRuntimeDataBf897Test {

    @Test
    void backupIsOfflineHashVerifiedAndExplicitlyPrivate() throws Exception {
        String backup = source("scripts/butler-runtime-data-backup.ps1");

        assertTrue(backup.contains("running-port-*.txt"));
        assertTrue(backup.contains("@('-wal', '-shm', '-journal')"));
        assertTrue(backup.contains("SQLite format 3`0"));
        assertTrue(backup.contains("$sourceHashBefore = (Get-FileHash"));
        assertTrue(backup.contains("$sourceHashAfter = (Get-FileHash"));
        assertTrue(backup.contains("$stagedHash = (Get-FileHash"));
        assertTrue(backup.contains("BUTLER_RUNTIME_DATA_BACKUP_V1"));
        assertTrue(backup.contains("PRIVATE_RUNTIME_DATA_BACKUP_CONTAINS_USER_DATA"));
        assertTrue(backup.contains("app-league.txt"));
        assertTrue(backup.contains("BF-897 RUNTIME DATA BACKUP: PASS"));
        assertFalse(backup.contains("Invoke-RestMethod"));
        assertFalse(backup.contains("Invoke-WebRequest"));
    }

    @Test
    void restoreIsVerifiedFreshHostOnlyAndNeverSilentlyOverwrites() throws Exception {
        String restore = source("scripts/butler-runtime-data-restore.ps1");

        assertTrue(restore.contains("backup checksum sidecar"));
        assertTrue(restore.contains("BUTLER_RUNTIME_DATA_BACKUP_V1"));
        assertTrue(restore.contains("PRIVATE_RUNTIME_DATA_BACKUP_CONTAINS_USER_DATA"));
        assertTrue(restore.contains("backup database hash does not match its checksum and manifest"));
        assertTrue(restore.contains("SQLite format 3`0"));
        assertTrue(restore.contains("fresh-host restore will not overwrite it"));
        assertTrue(restore.contains("target machine already has a different Butler league selection"));
        assertTrue(restore.contains("FRESH_HOST_ONLY; VERIFIED_PRIVATE_RUNTIME_DATA; NEVER_OVERWRITE_EXISTING_GOVERNED_DATABASE"));
        assertFalse(restore.contains("Remove-Item -LiteralPath $targetDatabase"));
    }

    @Test
    void windowsCiExercisesSyntheticRoundTripAndPackageCarriesTools() throws Exception {
        String acceptance = source("scripts/butler-runtime-data-backup-acceptance.ps1");
        String workflow = source(".github/workflows/windows-powershell-parse.yml");
        String builder = source("scripts/butler-runtime-release-bundle.ps1");

        assertTrue(acceptance.contains("BF-897 RUNTIME DATA BACKUP/RESTORE ACCEPTANCE: PASS"));
        assertTrue(acceptance.contains("fresh-host restore did not refuse to overwrite"));
        assertTrue(acceptance.contains("backup did not refuse a source database with a SQLite sidecar"));
        assertTrue(workflow.contains("butler-runtime-data-backup-acceptance.ps1"));
        assertTrue(builder.contains("'scripts/butler-runtime-data-backup.ps1'"));
        assertTrue(builder.contains("'scripts/butler-runtime-data-restore.ps1'"));
        assertTrue(builder.contains("$name -ceq 'butler.db'"));
    }

    @Test
    void readmeKeepsPrivateTransferSeparateFromPublicReleaseEvidence() throws Exception {
        String readme = source("README.md");

        assertTrue(readme.contains("### Portable private runtime-data backup and fresh-host restore"));
        assertTrue(readme.contains("%LOCALAPPDATA%\\Butler\\backups"));
        assertTrue(readme.contains("do not upload it to GitHub or attach it to a public Butler release"));
        assertTrue(readme.contains("fresh-host only"));
        assertTrue(readme.contains("never part of BF-773 runtime releases, BF-777 verification records, or BF-787 release-evidence archives"));
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
        throw new IOException("BF-897 test could not locate " + relativePath);
    }
}
