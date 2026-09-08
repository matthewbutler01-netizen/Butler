package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PersonalizedSleeperTargetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperPersonalizedTargetServiceTest {
    @TempDir Path tempDir;

    @Test
    void discoversBindsAndLiveVerifiesExactRequestingUserLeagueRoster() throws Exception {
        Database database = database("1312110516008677376");
        FakeSource source = new FakeSource();
        var service = new SleeperPersonalizedTargetService(
            database, new PersonalizedSleeperTargetRepository(database), source);

        var discovery = service.discover("mbutler0624", "1312110516008677376");
        assertEquals("1051699472830525440", discovery.sleeperUserId());
        assertEquals(6, discovery.rosterId());
        assertEquals("nuke the whales", discovery.teamName());
        assertEquals(SleeperPersonalizedTargetService.MembershipRole.OWNER, discovery.membershipRole());

        var binding = service.bind("butler-hardcore", "mbutler0624", "1312110516008677376");
        assertEquals(PersonalizedSleeperTargetRepository.BindState.BOUND_VERIFIED, binding.state());

        var verified = service.verifyBoundTarget("butler-hardcore");
        assertEquals("mbutler0624", verified.sleeperUsername());
        assertEquals("1051699472830525440", verified.sleeperUserId());
        assertEquals("1312110516008677376", verified.sleeperLeagueId());
        assertEquals(6, verified.rosterId());
        assertEquals(SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED, verified.state());
    }

    @Test
    void validForeignBestOfWestLeagueIsRejectedBecauseItIsNotUsersLeague() throws Exception {
        Database database = database("1312522074199162880");
        FakeSource source = new FakeSource();
        var service = new SleeperPersonalizedTargetService(
            database, new PersonalizedSleeperTargetRepository(database), source);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            service.discover("mbutler0624", "1312522074199162880"));
        assertTrue(error.getMessage().contains("not exactly present in requesting user's 2026 league list"));
    }

    @Test
    void bindBlocksWhenButlerLeagueStillPointsAtDifferentProviderLeague() throws Exception {
        Database database = database("1180243166513750016");
        FakeSource source = new FakeSource();
        var service = new SleeperPersonalizedTargetService(
            database, new PersonalizedSleeperTargetRepository(database), source);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            service.bind("butler-hardcore", "mbutler0624", "1312110516008677376"));
        assertTrue(error.getMessage().contains("Butler league is not linked to the discovered personal Sleeper league"));
    }

    @Test
    void verificationBlocksIfBoundUsernameDriftsToDifferentUserId() throws Exception {
        Database database = database("1312110516008677376");
        FakeSource source = new FakeSource();
        var repository = new PersonalizedSleeperTargetRepository(database);
        var service = new SleeperPersonalizedTargetService(database, repository, source);
        service.bind("butler-hardcore", "mbutler0624", "1312110516008677376");

        source.userId = "999999";
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            service.verifyBoundTarget("butler-hardcore"));
        assertTrue(error.getMessage().contains("bound username now resolves to a different Sleeper user id"));
    }

    private Database database(String externalId) throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "INSERT INTO leagues(id, external_id, name, season) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, "butler-hardcore");
            statement.setString(2, externalId);
            statement.setString(3, "Hard(CORE)-Dynasty");
            statement.setInt(4, 2026);
            statement.executeUpdate();
        }
        return database;
    }

    private static final class FakeSource implements SleeperPersonalizedTargetService.Source {
        private String userId = "1051699472830525440";

        @Override public String user(String usernameOrId) {
            return "{\"username\":\"mbutler0624\",\"user_id\":\"" + userId
                + "\",\"display_name\":\"mbutler0624\"}";
        }

        @Override public String userLeagues(String requestedUserId, int season) {
            return "[{\"league_id\":\"1312110516008677376\",\"name\":\"Hard(CORE)-Dynasty\","
                + "\"season\":\"2026\",\"status\":\"in_season\"}]";
        }

        @Override public String league(String sleeperLeagueId) {
            if ("1312522074199162880".equals(sleeperLeagueId)) {
                return "{\"league_id\":\"1312522074199162880\",\"name\":\"The Best of the West\","
                    + "\"season\":\"2026\",\"status\":\"in_season\"}";
            }
            return "{\"league_id\":\"1312110516008677376\",\"name\":\"Hard(CORE)-Dynasty\","
                + "\"season\":\"2026\",\"status\":\"in_season\"}";
        }

        @Override public String rosters(String sleeperLeagueId) {
            if ("1312522074199162880".equals(sleeperLeagueId)) {
                return "[{\"roster_id\":1,\"owner_id\":\"1051614367491526656\",\"players\":[\"12048\"]}]";
            }
            return "[{\"roster_id\":6,\"owner_id\":\"" + userId + "\","
                + "\"co_owners\":[],\"players\":[\"10222\",\"10236\",\"11564\"]}]";
        }

        @Override public String users(String sleeperLeagueId) {
            return "[{\"user_id\":\"" + userId + "\",\"display_name\":\"mbutler0624\","
                + "\"metadata\":{\"team_name\":\"nuke the whales\"}}]";
        }
    }
}
