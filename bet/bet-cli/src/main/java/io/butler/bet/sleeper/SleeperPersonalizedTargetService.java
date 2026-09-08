package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PersonalizedSleeperTargetRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** BF-621 through BF-623 exact requesting-user Sleeper account+league+roster binding and verification. */
public final class SleeperPersonalizedTargetService {
    public static final String BF621_POLICY_ID =
        "sleeper-personal-target-discovery-v1-exact-username-user-league-roster-read-only";
    public static final String BF622_POLICY_ID =
        "sleeper-personal-target-binding-v1-explicit-exact-user-league-roster-cas-verified";
    public static final String BF623_POLICY_ID =
        "sleeper-personal-target-gate-v1-bound-user-league-roster-live-reverified-fail-closed";
    public static final int TARGET_SEASON = 2026;

    private final Database database;
    private final PersonalizedSleeperTargetRepository targets;
    private final Source source;
    private final ObjectMapper mapper = new ObjectMapper();

    public SleeperPersonalizedTargetService(Database database) {
        this(database, new PersonalizedSleeperTargetRepository(database), new LiveSource());
    }

    SleeperPersonalizedTargetService(
        Database database,
        PersonalizedSleeperTargetRepository targets,
        Source source) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.targets = Objects.requireNonNull(targets, "targets must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
    }

    public DiscoveryReport discover(String username, String selectedSleeperLeagueId)
        throws IOException, InterruptedException {
        String requestedUsername = requireText(username, "username");
        String selectedLeagueId = requireText(selectedSleeperLeagueId, "selectedSleeperLeagueId");
        UserObservation user = parseUser(source.user(requestedUsername));
        List<LeagueObservation> currentLeagues = parseLeagues(source.userLeagues(user.userId(), TARGET_SEASON));
        List<LeagueObservation> selectedMatches = currentLeagues.stream()
            .filter(value -> selectedLeagueId.equals(value.leagueId()))
            .toList();
        if (selectedMatches.size() != 1) {
            throw new IllegalStateException(
                "BF-621 BLOCKED: selected Sleeper league is not exactly present in requesting user's 2026 league list: "
                    + selectedLeagueId);
        }
        LeagueObservation selected = selectedMatches.get(0);
        LeagueObservation directLeague = parseLeague(source.league(selectedLeagueId));
        if (!selected.equals(directLeague)) {
            throw new IllegalStateException("BF-621 BLOCKED: user-league list and direct league observation disagree");
        }

        List<RosterObservation> rosters = parseRosters(source.rosters(selectedLeagueId));
        List<RosterObservation> memberships = rosters.stream()
            .filter(value -> user.userId().equals(value.ownerId()) || value.coOwnerIds().contains(user.userId()))
            .toList();
        if (memberships.size() != 1) {
            throw new IllegalStateException(
                "BF-621 BLOCKED: requesting user resolves to " + memberships.size()
                    + " current roster memberships instead of exactly one");
        }
        RosterObservation roster = memberships.get(0);
        ProviderLeagueUser leagueUser = parseLeagueUsers(source.users(selectedLeagueId)).stream()
            .filter(value -> user.userId().equals(value.userId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "BF-621 BLOCKED: requesting user is absent from selected league users surface"));

        return new DiscoveryReport(
            BF621_POLICY_ID,
            user.username(),
            user.userId(),
            user.displayName(),
            List.copyOf(currentLeagues),
            selected.leagueId(),
            selected.name(),
            selected.season(),
            selected.status(),
            roster.rosterId(),
            user.userId().equals(roster.ownerId()) ? MembershipRole.OWNER : MembershipRole.CO_OWNER,
            leagueUser.displayName(),
            leagueUser.teamName(),
            roster.playerCount(),
            DiscoveryState.EXACT_USER_LEAGUE_ROSTER_DISCOVERED);
    }

    public BindReport bind(String butlerLeagueId, String username, String selectedSleeperLeagueId)
        throws SQLException, IOException, InterruptedException {
        String leagueId = requireText(butlerLeagueId, "butlerLeagueId");
        DiscoveryReport discovery = discover(username, selectedSleeperLeagueId);
        var butlerLeague = new LeagueRepository(database).findById(leagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + leagueId));
        if (!discovery.sleeperLeagueId().equals(butlerLeague.getExternalId())) {
            throw new IllegalStateException(
                "BF-622 BLOCKED: Butler league is not linked to the discovered personal Sleeper league; linked="
                    + butlerLeague.getExternalId() + " discovered=" + discovery.sleeperLeagueId());
        }
        if (!Integer.valueOf(TARGET_SEASON).equals(butlerLeague.getSeason())) {
            throw new IllegalStateException(
                "BF-622 BLOCKED: Butler league season is not current 2026 after provider relink: "
                    + butlerLeague.getSeason());
        }
        var desired = new PersonalizedSleeperTargetRepository.Target(
            leagueId,
            discovery.sleeperUsername(),
            discovery.sleeperUserId(),
            discovery.sleeperLeagueId(),
            discovery.rosterId(),
            discovery.leagueName(),
            discovery.season(),
            discovery.providerStatus(),
            Instant.now());
        var state = targets.bindIfAbsentOrExact(desired);
        var persisted = targets.findByButlerLeagueId(leagueId)
            .orElseThrow(() -> new IllegalStateException("BF-622 BLOCKED: personalized target vanished after bind"));
        if (!persisted.sleeperUserId().equals(discovery.sleeperUserId())
            || !persisted.sleeperLeagueId().equals(discovery.sleeperLeagueId())
            || persisted.rosterId() != discovery.rosterId()) {
            throw new IllegalStateException("BF-622 BLOCKED: persisted target does not match exact discovery proof");
        }
        return new BindReport(BF622_POLICY_ID, persisted, state);
    }

    public VerifiedTarget verifyBoundTarget(String butlerLeagueId)
        throws SQLException, IOException, InterruptedException {
        String leagueId = requireText(butlerLeagueId, "butlerLeagueId");
        var bound = targets.findByButlerLeagueId(leagueId)
            .orElseThrow(() -> new IllegalStateException(
                "BF-623 BLOCKED: no personalized Sleeper target is bound for Butler league " + leagueId));
        DiscoveryReport live = discover(bound.sleeperUsername(), bound.sleeperLeagueId());
        if (!bound.sleeperUserId().equals(live.sleeperUserId())) {
            throw new IllegalStateException("BF-623 BLOCKED: bound username now resolves to a different Sleeper user id");
        }
        if (bound.rosterId() != live.rosterId()) {
            throw new IllegalStateException(
                "BF-623 BLOCKED: bound requesting-user roster changed; bound=" + bound.rosterId()
                    + " live=" + live.rosterId());
        }
        if (bound.season() != live.season() || bound.season() != TARGET_SEASON) {
            throw new IllegalStateException("BF-623 BLOCKED: bound/live provider season is not exact 2026");
        }
        if (!bound.providerStatus().equals(live.providerStatus())) {
            throw new IllegalStateException(
                "BF-623 BLOCKED: provider status drifted from bound target; bound=" + bound.providerStatus()
                    + " live=" + live.providerStatus());
        }
        if (!"in_season".equals(live.providerStatus())) {
            throw new IllegalStateException("BF-623 BLOCKED: personalized recommendation target is not in_season");
        }
        var butlerLeague = new LeagueRepository(database).findById(leagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + leagueId));
        if (!bound.sleeperLeagueId().equals(butlerLeague.getExternalId())) {
            throw new IllegalStateException(
                "BF-623 BLOCKED: Butler provider linkage drifted from personalized target; linked="
                    + butlerLeague.getExternalId() + " bound=" + bound.sleeperLeagueId());
        }
        if (!Integer.valueOf(TARGET_SEASON).equals(butlerLeague.getSeason())) {
            throw new IllegalStateException("BF-623 BLOCKED: Butler league season drifted from personalized 2026 target");
        }
        return new VerifiedTarget(
            BF623_POLICY_ID,
            leagueId,
            live.sleeperUsername(),
            live.sleeperUserId(),
            live.sleeperLeagueId(),
            live.leagueName(),
            live.providerStatus(),
            live.rosterId(),
            live.membershipRole(),
            live.leagueDisplayName(),
            live.teamName(),
            VerificationState.BOUND_TARGET_LIVE_VERIFIED);
    }

    private UserObservation parseUser(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "user payload"));
        if (root == null || !root.isObject()) throw new IllegalStateException("Sleeper user payload must be an object");
        return new UserObservation(
            requireText(text(root.get("username")), "provider username"),
            requireText(text(root.get("user_id")), "provider user_id"),
            optional(text(root.get("display_name"))));
    }

    private LeagueObservation parseLeague(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "league payload"));
        if (root == null || !root.isObject()) throw new IllegalStateException("Sleeper league payload must be an object");
        return parseLeagueNode(root);
    }

    private List<LeagueObservation> parseLeagues(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "user leagues payload"));
        if (root == null || !root.isArray()) throw new IllegalStateException("Sleeper user leagues payload must be an array");
        List<LeagueObservation> result = new ArrayList<>();
        for (JsonNode value : root) result.add(parseLeagueNode(value));
        return List.copyOf(result);
    }

    private static LeagueObservation parseLeagueNode(JsonNode node) {
        return new LeagueObservation(
            requireText(text(node.get("league_id")), "provider league_id"),
            requireText(text(node.get("name")), "provider league name"),
            parseSeason(node.get("season")),
            requireText(text(node.get("status")), "provider league status"));
    }

    private List<RosterObservation> parseRosters(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "rosters payload"));
        if (root == null || !root.isArray()) throw new IllegalStateException("Sleeper rosters payload must be an array");
        List<RosterObservation> result = new ArrayList<>();
        for (JsonNode node : root) {
            int rosterId = node.path("roster_id").asInt(0);
            if (rosterId <= 0) throw new IllegalStateException("Sleeper roster_id must be positive");
            String ownerId = optional(text(node.get("owner_id")));
            List<String> coOwners = new ArrayList<>();
            JsonNode coOwnerNode = node.get("co_owners");
            if (coOwnerNode != null && coOwnerNode.isArray()) {
                for (JsonNode value : coOwnerNode) {
                    String id = optional(text(value));
                    if (id != null) coOwners.add(id);
                }
            }
            JsonNode players = node.get("players");
            int playerCount = players != null && players.isArray() ? players.size() : 0;
            result.add(new RosterObservation(rosterId, ownerId, List.copyOf(coOwners), playerCount));
        }
        return List.copyOf(result);
    }

    private List<ProviderLeagueUser> parseLeagueUsers(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "league users payload"));
        if (root == null || !root.isArray()) throw new IllegalStateException("Sleeper league users payload must be an array");
        List<ProviderLeagueUser> result = new ArrayList<>();
        for (JsonNode node : root) {
            String userId = requireText(text(node.get("user_id")), "league user_id");
            String displayName = optional(text(node.get("display_name")));
            String teamName = null;
            JsonNode metadata = node.get("metadata");
            if (metadata != null && metadata.isObject()) teamName = optional(text(metadata.get("team_name")));
            result.add(new ProviderLeagueUser(userId, displayName, teamName));
        }
        return List.copyOf(result);
    }

    private static int parseSeason(JsonNode node) {
        String raw = node == null || node.isNull() ? null : node.asText(null);
        try {
            int season = Integer.parseInt(requireText(raw, "provider season"));
            if (season < 1999 || season > 2100) throw new NumberFormatException();
            return season;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid provider season: " + raw);
        }
    }

    interface Source {
        String user(String usernameOrId) throws IOException, InterruptedException;
        String userLeagues(String userId, int season) throws IOException, InterruptedException;
        String league(String sleeperLeagueId) throws IOException, InterruptedException;
        String rosters(String sleeperLeagueId) throws IOException, InterruptedException;
        String users(String sleeperLeagueId) throws IOException, InterruptedException;
    }

    private static final class LiveSource implements Source {
        private final SleeperClient client = new SleeperClient();

        @Override public String user(String usernameOrId) throws IOException, InterruptedException {
            return client.getUser(usernameOrId);
        }
        @Override public String userLeagues(String userId, int season) throws IOException, InterruptedException {
            return client.getUserLeagues(userId, Integer.toString(season));
        }
        @Override public String league(String sleeperLeagueId) throws IOException, InterruptedException {
            return client.getLeague(sleeperLeagueId);
        }
        @Override public String rosters(String sleeperLeagueId) throws IOException, InterruptedException {
            return client.getLeagueRosters(sleeperLeagueId);
        }
        @Override public String users(String sleeperLeagueId) throws IOException, InterruptedException {
            return client.getLeagueUsers(sleeperLeagueId);
        }
    }

    public enum MembershipRole { OWNER, CO_OWNER }
    public enum DiscoveryState { EXACT_USER_LEAGUE_ROSTER_DISCOVERED }
    public enum VerificationState { BOUND_TARGET_LIVE_VERIFIED }

    public record LeagueObservation(String leagueId, String name, int season, String status) {
        public LeagueObservation {
            leagueId = requireText(leagueId, "leagueId");
            name = requireText(name, "name");
            status = requireText(status, "status");
        }
    }

    public record DiscoveryReport(
        String policyId,
        String sleeperUsername,
        String sleeperUserId,
        String accountDisplayName,
        List<LeagueObservation> currentSeasonLeagues,
        String sleeperLeagueId,
        String leagueName,
        int season,
        String providerStatus,
        int rosterId,
        MembershipRole membershipRole,
        String leagueDisplayName,
        String teamName,
        int playerCount,
        DiscoveryState state) {
        public DiscoveryReport {
            if (!BF621_POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-621 policyId");
            sleeperUsername = requireText(sleeperUsername, "sleeperUsername");
            sleeperUserId = requireText(sleeperUserId, "sleeperUserId");
            accountDisplayName = optional(accountDisplayName);
            currentSeasonLeagues = List.copyOf(Objects.requireNonNull(currentSeasonLeagues, "currentSeasonLeagues must not be null"));
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            leagueName = requireText(leagueName, "leagueName");
            providerStatus = requireText(providerStatus, "providerStatus");
            if (season != TARGET_SEASON) throw new IllegalArgumentException("season must be 2026");
            if (rosterId <= 0) throw new IllegalArgumentException("rosterId must be positive");
            Objects.requireNonNull(membershipRole, "membershipRole must not be null");
            leagueDisplayName = optional(leagueDisplayName);
            teamName = optional(teamName);
            if (playerCount < 0) throw new IllegalArgumentException("playerCount must not be negative");
            Objects.requireNonNull(state, "state must not be null");
        }
    }

    public record BindReport(
        String policyId,
        PersonalizedSleeperTargetRepository.Target target,
        PersonalizedSleeperTargetRepository.BindState state) {
        public BindReport {
            if (!BF622_POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-622 policyId");
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(state, "state must not be null");
        }
    }

    public record VerifiedTarget(
        String policyId,
        String butlerLeagueId,
        String sleeperUsername,
        String sleeperUserId,
        String sleeperLeagueId,
        String leagueName,
        String providerStatus,
        int rosterId,
        MembershipRole membershipRole,
        String displayName,
        String teamName,
        VerificationState state) {
        public VerifiedTarget {
            if (!BF623_POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-623 policyId");
            butlerLeagueId = requireText(butlerLeagueId, "butlerLeagueId");
            sleeperUsername = requireText(sleeperUsername, "sleeperUsername");
            sleeperUserId = requireText(sleeperUserId, "sleeperUserId");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            leagueName = requireText(leagueName, "leagueName");
            providerStatus = requireText(providerStatus, "providerStatus");
            if (rosterId <= 0) throw new IllegalArgumentException("rosterId must be positive");
            Objects.requireNonNull(membershipRole, "membershipRole must not be null");
            displayName = optional(displayName);
            teamName = optional(teamName);
            Objects.requireNonNull(state, "state must not be null");
        }
    }

    private record UserObservation(String username, String userId, String displayName) {
        private UserObservation {
            username = requireText(username, "username");
            userId = requireText(userId, "userId");
            displayName = optional(displayName);
        }
    }
    private record RosterObservation(int rosterId, String ownerId, List<String> coOwnerIds, int playerCount) {
        private RosterObservation {
            if (rosterId <= 0) throw new IllegalArgumentException("rosterId must be positive");
            ownerId = optional(ownerId);
            coOwnerIds = List.copyOf(coOwnerIds);
            if (playerCount < 0) throw new IllegalArgumentException("playerCount must not be negative");
        }
    }
    private record ProviderLeagueUser(String userId, String displayName, String teamName) {
        private ProviderLeagueUser {
            userId = requireText(userId, "userId");
            displayName = optional(displayName);
            teamName = optional(teamName);
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }
    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
