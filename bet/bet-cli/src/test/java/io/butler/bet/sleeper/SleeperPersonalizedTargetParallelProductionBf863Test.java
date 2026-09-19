package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PersonalizedSleeperTargetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperPersonalizedTargetParallelProductionBf863Test {
    @TempDir Path tempDir;

    @Test
    void productionParallelMatchesSerialReferenceExactly() throws Exception {
        Database database = boundDatabase();
        var targets = new PersonalizedSleeperTargetRepository(database);
        var source = new StableSource(false);
        var service = new SleeperPersonalizedTargetService(database, targets, source);

        var production = service.verifyBoundTarget("butler-hardcore");
        var serial = service.verifyBoundTargetSerialDiagnostic(
            "butler-hardcore",
            SleeperPersonalizedTargetService.ProviderStageObserver.NO_OP);

        assertEquals(serial, production);
        assertEquals(SleeperPersonalizedTargetService.BF623_POLICY_ID, production.policyId());
        assertEquals(SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED, production.state());
    }

    @Test
    void productionParallelDoesNotCacheLiveVerification() throws Exception {
        Database database = boundDatabase();
        var targets = new PersonalizedSleeperTargetRepository(database);
        var source = new StableSource(false);
        var service = new SleeperPersonalizedTargetService(database, targets, source);

        service.verifyBoundTarget("butler-hardcore");
        service.verifyBoundTarget("butler-hardcore");

        assertEquals(2, source.userCalls.get());
        assertEquals(2, source.userLeaguesCalls.get());
        assertEquals(2, source.leagueCalls.get());
        assertEquals(2, source.rostersCalls.get());
        assertEquals(2, source.usersCalls.get());
    }

    @Test
    void productionParallelProviderFailureRemainsFailClosed() throws Exception {
        Database database = boundDatabase();
        var targets = new PersonalizedSleeperTargetRepository(database);
        var source = new StableSource(true);
        var service = new SleeperPersonalizedTargetService(database, targets, source);

        IOException failure = assertThrows(
            IOException.class,
            () -> service.verifyBoundTarget("butler-hardcore"));

        assertTrue(failure.getMessage().contains("BF-863 synthetic league failure"));
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

    private static final class StableSource implements SleeperPersonalizedTargetService.Source {
        private final boolean failLeague;
        private final AtomicInteger userCalls = new AtomicInteger();
        private final AtomicInteger userLeaguesCalls = new AtomicInteger();
        private final AtomicInteger leagueCalls = new AtomicInteger();
        private final AtomicInteger rostersCalls = new AtomicInteger();
        private final AtomicInteger usersCalls = new AtomicInteger();

        private StableSource(boolean failLeague) {
            this.failLeague = failLeague;
        }

        @Override public String user(String usernameOrId) {
            userCalls.incrementAndGet();
            return "{\"username\":\"mbutler0624\",\"user_id\":\"1051699472830525440\","
                + "\"display_name\":\"mbutler0624\"}";
        }

        @Override public String userLeagues(String userId, int season) {
            userLeaguesCalls.incrementAndGet();
            return "[{\"league_id\":\"1312110516008677376\",\"name\":\"Hard(CORE)-Dynasty\","
                + "\"season\":\"2026\",\"status\":\"in_season\"}]";
        }

        @Override public String league(String sleeperLeagueId) throws IOException {
            leagueCalls.incrementAndGet();
            if (failLeague) throw new IOException("BF-863 synthetic league failure");
            return "{\"league_id\":\"1312110516008677376\",\"name\":\"Hard(CORE)-Dynasty\","
                + "\"season\":\"2026\",\"status\":\"in_season\"}";
        }

        @Override public String rosters(String sleeperLeagueId) {
            rostersCalls.incrementAndGet();
            return "[{\"roster_id\":6,\"owner_id\":\"1051699472830525440\",\"co_owners\":[],"
                + "\"players\":[\"10222\",\"10236\",\"11564\"]}]";
        }

        @Override public String users(String sleeperLeagueId) {
            usersCalls.incrementAndGet();
            return "[{\"user_id\":\"1051699472830525440\",\"display_name\":\"mbutler0624\","
                + "\"metadata\":{\"team_name\":\"nuke the whales\"}}]";
        }
    }
}
