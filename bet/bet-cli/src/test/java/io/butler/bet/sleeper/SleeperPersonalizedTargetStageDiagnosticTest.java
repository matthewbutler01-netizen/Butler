package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PersonalizedSleeperTargetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Instant;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperPersonalizedTargetStageDiagnosticTest {
    @TempDir Path tempDir;

    @Test
    void timingSourceObservesExactlyOneExistingBf623ProviderCallPerStage() throws Exception {
        Database database = boundDatabase();
        var repository = new PersonalizedSleeperTargetRepository(database);
        var timingSource = new SleeperPersonalizedTargetStageDiagnostic.TimingSource(new FakeSource());
        var service = new SleeperPersonalizedTargetService(database, repository, timingSource);

        var verified = service.verifyBoundTarget("butler-hardcore");
        assertEquals(SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED, verified.state());

        var timing = timingSource.snapshot();
        timing.requireExactSinglePass();
        assertEquals(1, timing.userCalls());
        assertEquals(1, timing.userLeaguesCalls());
        assertEquals(1, timing.leagueCalls());
        assertEquals(1, timing.rostersCalls());
        assertEquals(1, timing.usersCalls());

        String marker = SleeperPersonalizedTargetStageDiagnostic.formatMarker(1, timing, timing.providerSumMs() + 7);
        assertTrue(marker.matches(
            "^===BUTLER_TARGET_STAGE_TIMING:sample=1;user_ms=\\d+;user_leagues_ms=\\d+;"
                + "league_ms=\\d+;rosters_ms=\\d+;users_ms=\\d+;provider_sum_ms=\\d+;"
                + "verify_wall_ms=\\d+;residual_ms=7===$"));
    }

    @Test
    void timingSourcePreservesProviderFailureAndDoesNotProduceAFalseVerifiedTarget() throws Exception {
        Database database = boundDatabase();
        var repository = new PersonalizedSleeperTargetRepository(database);
        SleeperPersonalizedTargetService.Source failing = new FakeSource() {
            @Override public String league(String sleeperLeagueId) {
                throw new IllegalStateException("provider league failure");
            }
        };
        var timingSource = new SleeperPersonalizedTargetStageDiagnostic.TimingSource(failing);
        var service = new SleeperPersonalizedTargetService(database, repository, timingSource);

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> service.verifyBoundTarget("butler-hardcore"));
        assertTrue(error.getMessage().contains("provider league failure"));
        assertEquals(1, timingSource.snapshot().leagueCalls());
    }

    @Test
    void sampleCountIsStrictlyBounded() {
        assertEquals(1, SleeperPersonalizedTargetStageDiagnostic.parseSamples("1"));
        assertEquals(3, SleeperPersonalizedTargetStageDiagnostic.parseSamples("3"));
        assertEquals(9, SleeperPersonalizedTargetStageDiagnostic.parseSamples("9"));
        assertThrows(IllegalArgumentException.class, () -> SleeperPersonalizedTargetStageDiagnostic.parseSamples("0"));
        assertThrows(IllegalArgumentException.class, () -> SleeperPersonalizedTargetStageDiagnostic.parseSamples("10"));
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

    private static class FakeSource implements SleeperPersonalizedTargetService.Source {
        @Override public String user(String usernameOrId) {
            return "{\"username\":\"mbutler0624\",\"user_id\":\"1051699472830525440\","
                + "\"display_name\":\"mbutler0624\"}";
        }

        @Override public String userLeagues(String requestedUserId, int season) {
            return "[{\"league_id\":\"1312110516008677376\",\"name\":\"Hard(CORE)-Dynasty\","
                + "\"season\":\"2026\",\"status\":\"in_season\"}]";
        }

        @Override public String league(String sleeperLeagueId) {
            return "{\"league_id\":\"1312110516008677376\",\"name\":\"Hard(CORE)-Dynasty\","
                + "\"season\":\"2026\",\"status\":\"in_season\"}";
        }

        @Override public String rosters(String sleeperLeagueId) {
            return "[{\"roster_id\":6,\"owner_id\":\"1051699472830525440\",\"co_owners\":[],"
                + "\"players\":[\"10222\",\"10236\",\"11564\"]}]";
        }

        @Override public String users(String sleeperLeagueId) {
            return "[{\"user_id\":\"1051699472830525440\",\"display_name\":\"mbutler0624\","
                + "\"metadata\":{\"team_name\":\"nuke the whales\"}}]";
        }
    }
}
