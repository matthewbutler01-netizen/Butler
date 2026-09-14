package io.butler.bet.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseInitializationLockTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentInitializersShareOnePerDatabaseInitializationGate() throws Exception {
        Path databasePath = tempDir.resolve("butler.db");
        var executor = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                tasks.add(() -> {
                    new Database(databasePath).initialize();
                    return null;
                });
            }

            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertTrue(Files.isRegularFile(databasePath));
        assertTrue(Files.isRegularFile(tempDir.resolve("butler.db.init.lock")));

        try (var connection = new Database(databasePath).openConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery(
                 "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='leagues'")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }
}
