package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PersonalizedSleeperTargetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperTransportPrewarmDiagnosticBf745Test {
    @TempDir Path tempDir;

    @Test
    void oneDiscardedWarmupPrecedesOneCompleteUnchangedBf623Pass() throws Exception {
        Database database = boundDatabase();
        var repository = new PersonalizedSleeperTargetRepository(database);
        var source = new CountingSource();
        AtomicInteger warmups = new AtomicInteger();

        var measurement = SleeperTransportPrewarmDiagnostic.measure(
            database,
            repository,
            source,
            warmups::incrementAndGet,
            "butler-hardcore");

        assertEquals(1, warmups.get());
        assertEquals(1, source.userCalls);
        assertEquals(1, source.userLeaguesCalls);
        assertEquals(1, source.leagueCalls);
        assertEquals(1, source.rostersCalls);
        assertEquals(1, source.usersCalls);
        measurement.stageTiming().requireExactSinglePass();

        String marker = SleeperTransportPrewarmDiagnostic.formatMarker(measurement);
        assertTrue(marker.matches(
            "^===BUTLER_TRANSPORT_PREWARM_TIMING:warmup_ms=\\d+;user_ms=\\d+;"
                + "user_leagues_ms=\\d+;league_ms=\\d+;rosters_ms=\\d+;users_ms=\\d+;"
                + "provider_sum_ms=\\d+;verify_wall_ms=\\d+;residual_ms=\\d+===$"));
    }

    @Test
    void sourceBoundaryUsesOnlyStateNflForWarmupAndLeavesProductionVerificationSerial() throws Exception {
        String diagnostic = source(
            "bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperTransportPrewarmDiagnostic.java");
        String service = source(
            "bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperPersonalizedTargetService.java");
        String script = source("scripts/butler-transport-prewarm-diagnostic.ps1");

        assertTrue(diagnostic.contains("client.getNflState();"));
        assertEquals(1, occurrences(diagnostic, "client.getNflState();"));
        assertTrue(diagnostic.contains("new SleeperPersonalizedTargetStageDiagnostic.TimingSource(source)"));
        assertTrue(diagnostic.contains("service.verifyBoundTarget(leagueId.trim())"));
        assertFalse(diagnostic.contains("CompletableFuture"));
        assertFalse(diagnostic.contains("newFixedThreadPool"));

        assertTrue(service.contains("DiscoveryReport live = discover(bound.sleeperUsername(), bound.sleeperLeagueId());"));
        assertFalse(service.contains("CompletableFuture"));
        assertFalse(service.contains("newFixedThreadPool"));

        assertTrue(script.contains("app-league.txt"));
        assertTrue(script.contains("[Guid]::TryParse($leagueId, [ref]$parsedLeagueId)"));
        assertTrue(script.contains("build\\install\\bet-cli\\lib"));
        assertTrue(script.contains("io.butler.bet.sleeper.SleeperTransportPrewarmDiagnostic"));
        assertTrue(script.contains("===BUTLER_TRANSPORT_PREWARM_TIMING:"));
        assertTrue(script.contains("exactly one discarded read-only state/nfl prewarm"));
        assertTrue(script.contains("/refresh excluded"));
        assertFalse(script.contains("production-refresh"));
    }

    private Database boundDatabase() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "INSERT INTO leagues(id, external_id, name, season) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, "butler-hardcore");
            statement.setString(2, "1312110516008677376");
            statement.setString(3, "Hard(CORE)-Dynasty");
            statement.setInt(4, 2026);
            statement.executeUpdate();
        }
        var repository = new PersonalizedSleeperTargetRepository(database);
        repository.bindIfAbsentOrExact(new PersonalizedSleeperTargetRepository.Target(
            "butler-hardcore",
            "mbutler0624",
            "1051699472830525440",
            "1312110516008677376",
            6,
            "Hard(CORE)-Dynasty",
            2026,
            "in_season",
            Instant.parse("2026-09-14T00:00:00Z")));
        return database;
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
        throw new IOException("BF-745 test could not locate " + relativePath);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static final class CountingSource implements SleeperPersonalizedTargetService.Source {
        private int userCalls;
        private int userLeaguesCalls;
        private int leagueCalls;
        private int rostersCalls;
        private int usersCalls;

        @Override public String user(String usernameOrId) {
            userCalls++;
            return "{\"username\":\"mbutler0624\",\"user_id\":\"1051699472830525440\","
                + "\"display_name\":\"mbutler0624\"}";
        }

        @Override public String userLeagues(String userId, int season) {
            userLeaguesCalls++;
            return "[{\"league_id\":\"1312110516008677376\",\"name\":\"Hard(CORE)-Dynasty\","
                + "\"season\":\"2026\",\"status\":\"in_season\"}]";
        }

        @Override public String league(String sleeperLeagueId) {
            leagueCalls++;
            return "{\"league_id\":\"1312110516008677376\",\"name\":\"Hard(CORE)-Dynasty\","
                + "\"season\":\"2026\",\"status\":\"in_season\"}";
        }

        @Override public String rosters(String sleeperLeagueId) {
            rostersCalls++;
            return "[{\"roster_id\":6,\"owner_id\":\"1051699472830525440\",\"co_owners\":[],"
                + "\"players\":[\"10222\",\"10236\",\"11564\"]}]";
        }

        @Override public String users(String sleeperLeagueId) {
            usersCalls++;
            return "[{\"user_id\":\"1051699472830525440\",\"display_name\":\"mbutler0624\","
                + "\"metadata\":{\"team_name\":\"nuke the whales\"}}]";
        }
    }
}
