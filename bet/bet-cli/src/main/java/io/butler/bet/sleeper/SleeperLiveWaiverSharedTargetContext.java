package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.PersonalizedSleeperTargetRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;

/**
 * BF-850 one-execution live Sleeper snapshot shared by BF-623 target verification and
 * BF-610 target-roster audit. Each provider surface is fetched once, then both existing
 * validators consume the exact same raw observations.
 */
public final class SleeperLiveWaiverSharedTargetContext {
    private final Database database;
    private final PersonalizedSleeperTargetRepository targets;
    private final SnapshotLoader loader;

    public SleeperLiveWaiverSharedTargetContext(Database database) {
        this(database, new PersonalizedSleeperTargetRepository(database), new LiveSnapshotLoader());
    }

    SleeperLiveWaiverSharedTargetContext(
        Database database,
        PersonalizedSleeperTargetRepository targets,
        SnapshotLoader loader) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.targets = Objects.requireNonNull(targets, "targets must not be null");
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
    }

    public ResolvedContext resolve(String butlerLeagueId)
        throws SQLException, IOException, InterruptedException {
        String leagueId = requireText(butlerLeagueId, "butlerLeagueId");
        var bound = targets.findByButlerLeagueId(leagueId)
            .orElseThrow(() -> new IllegalStateException(
                "BF-850 BLOCKED: no personalized Sleeper target is bound for Butler league " + leagueId));

        SnapshotSource snapshot = Objects.requireNonNull(
            loader.load(bound.sleeperUsername(), bound.sleeperLeagueId()),
            "BF-850 shared snapshot must not be null");

        var target = new SleeperPersonalizedTargetService(database, targets, snapshot)
            .verifyBoundTarget(leagueId);
        var rosterContext = new SleeperLiveWaiverTargetRosterContextAudit(database, snapshot)
            .audit(leagueId, target.sleeperUserId());

        if (!target.sleeperLeagueId().equals(rosterContext.sleeperLeagueId())
            || target.rosterId() != rosterContext.rosterId()
            || !target.sleeperUserId().equals(rosterContext.sleeperOwnerId())) {
            throw new IllegalStateException(
                "BF-850 BLOCKED: shared BF-623 and BF-610 target identities do not reconcile");
        }
        return new ResolvedContext(target, rosterContext);
    }

    interface SnapshotLoader {
        SnapshotSource load(String sleeperUsername, String sleeperLeagueId)
            throws IOException, InterruptedException;
    }

    static final class SnapshotSource
        implements SleeperPersonalizedTargetService.Source, SleeperLiveWaiverTargetRosterContextAudit.Source {
        private final String username;
        private final String userId;
        private final String sleeperLeagueId;
        private final String userJson;
        private final String userLeaguesJson;
        private final String leagueJson;
        private final String rostersJson;
        private final String usersJson;

        SnapshotSource(
            String username,
            String userId,
            String sleeperLeagueId,
            String userJson,
            String userLeaguesJson,
            String leagueJson,
            String rostersJson,
            String usersJson) {
            this.username = requireText(username, "username");
            this.userId = requireText(userId, "userId");
            this.sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            this.userJson = requireText(userJson, "userJson");
            this.userLeaguesJson = requireText(userLeaguesJson, "userLeaguesJson");
            this.leagueJson = requireText(leagueJson, "leagueJson");
            this.rostersJson = requireText(rostersJson, "rostersJson");
            this.usersJson = requireText(usersJson, "usersJson");
        }

        @Override
        public String user(String usernameOrId) {
            String requested = requireText(usernameOrId, "usernameOrId");
            if (!username.equalsIgnoreCase(requested) && !userId.equals(requested)) {
                throw new IllegalStateException("BF-850 BLOCKED: shared user snapshot requested for a different identity");
            }
            return userJson;
        }

        @Override
        public String userLeagues(String requestedUserId, int season) {
            if (!userId.equals(requireText(requestedUserId, "requestedUserId"))
                || season != SleeperPersonalizedTargetService.TARGET_SEASON) {
                throw new IllegalStateException("BF-850 BLOCKED: shared user-leagues snapshot requested for a different frame");
            }
            return userLeaguesJson;
        }

        @Override
        public String league(String requestedLeagueId) {
            requireLeague(requestedLeagueId);
            return leagueJson;
        }

        @Override
        public String rosters(String requestedLeagueId) {
            requireLeague(requestedLeagueId);
            return rostersJson;
        }

        @Override
        public String users(String requestedLeagueId) {
            requireLeague(requestedLeagueId);
            return usersJson;
        }

        private void requireLeague(String requestedLeagueId) {
            if (!sleeperLeagueId.equals(requireText(requestedLeagueId, "requestedLeagueId"))) {
                throw new IllegalStateException("BF-850 BLOCKED: shared league snapshot requested for a different league");
            }
        }
    }

    static final class LiveSnapshotLoader implements SnapshotLoader {
        private final SleeperClient client = new SleeperClient();
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public SnapshotSource load(String sleeperUsername, String sleeperLeagueId)
            throws IOException, InterruptedException {
            String username = requireText(sleeperUsername, "sleeperUsername");
            String leagueId = requireText(sleeperLeagueId, "sleeperLeagueId");

            String userJson = client.getUser(username);
            JsonNode user = mapper.readTree(requireText(userJson, "user payload"));
            String userId = user == null || !user.isObject()
                ? null
                : text(user.get("user_id"));
            userId = requireText(userId, "provider user_id");

            String userLeaguesJson = client.getUserLeagues(
                userId, Integer.toString(SleeperPersonalizedTargetService.TARGET_SEASON));
            String leagueJson = client.getLeague(leagueId);
            String rostersJson = client.getLeagueRosters(leagueId);
            String usersJson = client.getLeagueUsers(leagueId);

            return new SnapshotSource(
                username,
                userId,
                leagueId,
                userJson,
                userLeaguesJson,
                leagueJson,
                rostersJson,
                usersJson);
        }
    }

    public record ResolvedContext(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport rosterContext) {
        public ResolvedContext {
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(rosterContext, "rosterContext must not be null");
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
