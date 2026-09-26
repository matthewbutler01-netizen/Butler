package io.butler.bet.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class ButlerSetupLeagueCheckTest {
    @TempDir Path root;
    private static final String LEAGUE = "11111111-2222-3333-4444-555555555555";

    @Test void verifiesMembershipWithoutChangingDatabaseOrCreatingSidecars() throws Exception {
        Path db = root.resolve("backup with spaces.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE leagues(id TEXT PRIMARY KEY)");
            statement.execute("INSERT INTO leagues VALUES('" + LEAGUE + "')");
        }
        byte[] before = Files.readAllBytes(db);
        ButlerSetupLeagueCheckCli.verify(db, LEAGUE);
        assertThrows(IllegalArgumentException.class, () -> ButlerSetupLeagueCheckCli.verify(db, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        assertArrayEquals(before, Files.readAllBytes(db));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test void missingDatabaseIsNeverCreated() {
        Path db = root.resolve("missing.db");
        assertThrows(IllegalArgumentException.class, () -> ButlerSetupLeagueCheckCli.verify(db, LEAGUE));
        assertFalse(Files.exists(db));
    }

    @Test void wrongSchemaIsNotMigrated() throws Exception {
        Path db = root.resolve("wrong.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) { statement.execute("CREATE TABLE other(id TEXT)"); }
        byte[] before = Files.readAllBytes(db);
        assertThrows(java.sql.SQLException.class, () -> ButlerSetupLeagueCheckCli.verify(db, LEAGUE));
        assertArrayEquals(before, Files.readAllBytes(db));
    }
}
