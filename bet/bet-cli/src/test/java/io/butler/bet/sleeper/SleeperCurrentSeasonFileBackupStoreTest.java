package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperCurrentSeasonFileBackupStoreTest {
    @TempDir Path tempDir;

    @Test
    void createsCleanBackupAndRestoreReplacesDatabaseAndRemovesSqliteSidecars() throws Exception {
        Path database = tempDir.resolve("butler.db");
        Files.writeString(database, "before");
        Clock clock = Clock.fixed(Instant.ofEpochMilli(123456789L), ZoneOffset.UTC);
        var store = new SleeperCurrentSeasonRosterBootstrap.FileBackupStore(database, clock);

        var backup = store.create();
        Path backupPath = Path.of(backup.displayPath());
        assertTrue(Files.isRegularFile(backupPath));
        assertEquals("before", Files.readString(backupPath));

        Files.writeString(database, "after");
        Files.writeString(Path.of(database + "-journal"), "journal");
        Files.writeString(Path.of(database + "-wal"), "wal");
        Files.writeString(Path.of(database + "-shm"), "shm");

        store.restore(backup);

        assertEquals("before", Files.readString(database));
        assertFalse(Files.exists(Path.of(database + "-journal")));
        assertFalse(Files.exists(Path.of(database + "-wal")));
        assertFalse(Files.exists(Path.of(database + "-shm")));
        assertTrue(Files.isRegularFile(backupPath));
    }
}
