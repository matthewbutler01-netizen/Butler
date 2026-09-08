package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.domain.Player;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** BF-645 read-only exact live roster presentation behind the BF-623 personalized target gate. */
public final class SleeperPersonalizedCurrentRosterSummary {
    public static final String POLICY_ID =
        "sleeper-personalized-current-roster-summary-v1-bf623-exact-live-roster-read-only";

    private final Source source;
    private final PlayerLookup playerLookup;
    private final ObjectMapper mapper = new ObjectMapper();

    public SleeperPersonalizedCurrentRosterSummary(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        SleeperClient client = new SleeperClient();
        PlayerRepository players = new PlayerRepository(database);
        this.source = new Source() {
            @Override public String league(String sleeperLeagueId) throws IOException, InterruptedException {
                return client.getLeague(sleeperLeagueId);
            }
            @Override public String rosters(String sleeperLeagueId) throws IOException, InterruptedException {
                return client.getLeagueRosters(sleeperLeagueId);
            }
        };
        this.playerLookup = sleeperId -> players.findByExternalId(sleeperId)
            .map(SleeperPersonalizedCurrentRosterSummary::display)
            .orElse(null);
    }

    SleeperPersonalizedCurrentRosterSummary(Source source, PlayerLookup playerLookup) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup must not be null");
    }

    public RosterReport summarize(SleeperPersonalizedTargetService.VerifiedTarget target)
        throws IOException, InterruptedException, SQLException {
        validateTarget(target);

        JsonNode league = mapper.readTree(requireText(source.league(target.sleeperLeagueId()), "league payload"));
        if (league == null || !league.isObject()) {
            throw new IllegalStateException("BF-645 BLOCKED: Sleeper league payload must be an object");
        }
        String directLeagueId = requireText(text(league.get("league_id")), "provider league_id");
        if (!target.sleeperLeagueId().equals(directLeagueId)) {
            throw new IllegalStateException("BF-645 BLOCKED: direct Sleeper league identity does not match BF-623 target");
        }
        List<String> starterSlots = parseStarterSlots(league.get("roster_positions"));

        JsonNode rosters = mapper.readTree(requireText(source.rosters(target.sleeperLeagueId()), "rosters payload"));
        if (rosters == null || !rosters.isArray()) {
            throw new IllegalStateException("BF-645 BLOCKED: Sleeper rosters payload must be an array");
        }
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode roster : rosters) {
            if (roster.path("roster_id").asInt(0) == target.rosterId()) matches.add(roster);
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("BF-645 BLOCKED: BF-623 target roster resolves to " + matches.size()
                + " live roster records instead of exactly one");
        }
        JsonNode roster = matches.get(0);
        validateOwnership(target, roster);

        List<String> players = parseIdArray(roster.get("players"), "players", true);
        List<String> starters = parseIdArray(roster.get("starters"), "starters", false);
        List<String> reserve = parseIdArray(roster.get("reserve"), "reserve", false);
        List<String> taxi = parseIdArray(roster.get("taxi"), "taxi", false);
        validateMembership(players, starters, reserve, taxi);

        boolean lineupSlotsVerified = !starterSlots.isEmpty() && starterSlots.size() == starters.size();
        List<PlayerView> result = new ArrayList<>();
        for (String sleeperId : players) {
            int starterIndex = starters.indexOf(sleeperId);
            RosterRole role;
            if (starterIndex >= 0) role = RosterRole.STARTER;
            else if (reserve.contains(sleeperId)) role = RosterRole.RESERVE;
            else if (taxi.contains(sleeperId)) role = RosterRole.TAXI;
            else role = RosterRole.BENCH;

            PlayerDisplay mapped = playerLookup.find(sleeperId);
            MappingState mappingState = mapped == null ? MappingState.UNMAPPED : MappingState.EXACT_PERSISTED_ID_MAPPED;
            String lineupSlot = starterIndex >= 0 && lineupSlotsVerified ? starterSlots.get(starterIndex) : null;
            result.add(new PlayerView(
                sleeperId,
                role,
                starterIndex >= 0 ? starterIndex + 1 : null,
                lineupSlot,
                mappingState,
                mapped == null ? null : mapped.displayName(),
                mapped == null ? null : mapped.position(),
                mapped == null ? null : mapped.nflTeam()));
        }

        return new RosterReport(
            POLICY_ID,
            target.butlerLeagueId(),
            target.sleeperUserId(),
            target.sleeperLeagueId(),
            target.leagueName(),
            target.rosterId(),
            target.teamName(),
            Instant.now().toString(),
            lineupSlotsVerified ? LineupSlotState.STARTER_SLOTS_VERIFIED : LineupSlotState.STARTER_SLOTS_UNAVAILABLE,
            List.copyOf(result),
            RosterState.LIVE_CURRENT_ROSTER_VERIFIED);
    }

    private static void validateTarget(SleeperPersonalizedTargetService.VerifiedTarget target) {
        Objects.requireNonNull(target, "target must not be null");
        if (!SleeperPersonalizedTargetService.BF623_POLICY_ID.equals(target.policyId())
            || target.state() != SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED) {
            throw new IllegalStateException("BF-645 BLOCKED: target is not BF-623 live verified");
        }
    }

    private static void validateOwnership(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        JsonNode roster) {
        String ownerId = optional(text(roster.get("owner_id")));
        Set<String> coOwners = new HashSet<>(parseIdArray(roster.get("co_owners"), "co_owners", false));
        boolean exactOwner = target.sleeperUserId().equals(ownerId);
        boolean exactCoOwner = coOwners.contains(target.sleeperUserId());
        if (target.membershipRole() == SleeperPersonalizedTargetService.MembershipRole.OWNER && !exactOwner) {
            throw new IllegalStateException("BF-645 BLOCKED: live roster owner does not match BF-623 owner target");
        }
        if (target.membershipRole() == SleeperPersonalizedTargetService.MembershipRole.CO_OWNER && !exactCoOwner) {
            throw new IllegalStateException("BF-645 BLOCKED: live roster co-owner does not match BF-623 co-owner target");
        }
    }

    private static void validateMembership(
        List<String> players,
        List<String> starters,
        List<String> reserve,
        List<String> taxi) {
        Set<String> playerSet = Set.copyOf(players);
        for (String value : starters) requireMember(playerSet, value, "starter");
        for (String value : reserve) requireMember(playerSet, value, "reserve");
        for (String value : taxi) requireMember(playerSet, value, "taxi");

        Set<String> claimed = new HashSet<>();
        for (String value : starters) claimed.add(value);
        for (String value : reserve) {
            if (!claimed.add(value)) throw new IllegalStateException("BF-645 BLOCKED: player appears in overlapping starter/reserve role: " + value);
        }
        for (String value : taxi) {
            if (!claimed.add(value)) throw new IllegalStateException("BF-645 BLOCKED: player appears in overlapping starter/reserve/taxi role: " + value);
        }
    }

    private static void requireMember(Set<String> players, String value, String role) {
        if (!players.contains(value)) {
            throw new IllegalStateException("BF-645 BLOCKED: " + role + " identity is absent from live roster players: " + value);
        }
    }

    private static List<String> parseStarterSlots(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalStateException("BF-645 BLOCKED: roster_positions must be an array");
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            String slot = requireText(text(value), "roster position");
            if (!"BN".equals(slot)) result.add(slot);
        }
        return List.copyOf(result);
    }

    private static List<String> parseIdArray(JsonNode node, String field, boolean required) {
        if (node == null || node.isNull()) {
            if (required) throw new IllegalStateException("BF-645 BLOCKED: live roster " + field + " is missing");
            return List.of();
        }
        if (!node.isArray()) throw new IllegalStateException("BF-645 BLOCKED: live roster " + field + " must be an array");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (JsonNode value : node) {
            String id = requireText(text(value), "live roster " + field + " player id");
            if (!unique.add(id)) throw new IllegalStateException("BF-645 BLOCKED: duplicate identity in live roster " + field + ": " + id);
        }
        return List.copyOf(unique);
    }

    private static PlayerDisplay display(Player player) {
        return new PlayerDisplay(player.getExternalId(), player.getDisplayName(), player.getPosition(), player.getNflTeam());
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

    interface Source {
        String league(String sleeperLeagueId) throws IOException, InterruptedException;
        String rosters(String sleeperLeagueId) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface PlayerLookup {
        PlayerDisplay find(String sleeperPlayerId) throws SQLException;
    }

    public enum RosterRole { STARTER, BENCH, RESERVE, TAXI }
    public enum MappingState { EXACT_PERSISTED_ID_MAPPED, UNMAPPED }
    public enum LineupSlotState { STARTER_SLOTS_VERIFIED, STARTER_SLOTS_UNAVAILABLE }
    public enum RosterState { LIVE_CURRENT_ROSTER_VERIFIED }

    public record PlayerDisplay(String sleeperPlayerId, String displayName, String position, String nflTeam) {
        public PlayerDisplay {
            sleeperPlayerId = requireText(sleeperPlayerId, "sleeperPlayerId");
            displayName = requireText(displayName, "displayName");
            position = requireText(position, "position");
            nflTeam = optional(nflTeam);
        }
    }

    public record PlayerView(
        String sleeperPlayerId,
        RosterRole role,
        Integer starterOrdinal,
        String lineupSlot,
        MappingState mappingState,
        String displayName,
        String position,
        String nflTeam) {
        public PlayerView {
            sleeperPlayerId = requireText(sleeperPlayerId, "sleeperPlayerId");
            Objects.requireNonNull(role, "role must not be null");
            Objects.requireNonNull(mappingState, "mappingState must not be null");
            lineupSlot = optional(lineupSlot);
            displayName = optional(displayName);
            position = optional(position);
            nflTeam = optional(nflTeam);
            if (role == RosterRole.STARTER && (starterOrdinal == null || starterOrdinal <= 0)) {
                throw new IllegalArgumentException("starterOrdinal must be positive for STARTER");
            }
            if (role != RosterRole.STARTER && starterOrdinal != null) {
                throw new IllegalArgumentException("starterOrdinal must be null for non-starter");
            }
            if (mappingState == MappingState.EXACT_PERSISTED_ID_MAPPED && (displayName == null || position == null)) {
                throw new IllegalArgumentException("mapped player must have displayName and position");
            }
            if (mappingState == MappingState.UNMAPPED && (displayName != null || position != null || nflTeam != null)) {
                throw new IllegalArgumentException("unmapped player must not invent persisted metadata");
            }
        }
    }

    public record RosterReport(
        String policyId,
        String butlerLeagueId,
        String sleeperOwnerId,
        String sleeperLeagueId,
        String leagueName,
        int rosterId,
        String teamName,
        String observedAtUtc,
        LineupSlotState lineupSlotState,
        List<PlayerView> players,
        RosterState state) {
        public RosterReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-645 policyId");
            butlerLeagueId = requireText(butlerLeagueId, "butlerLeagueId");
            sleeperOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            leagueName = requireText(leagueName, "leagueName");
            if (rosterId <= 0) throw new IllegalArgumentException("rosterId must be positive");
            teamName = optional(teamName);
            observedAtUtc = requireText(observedAtUtc, "observedAtUtc");
            Objects.requireNonNull(lineupSlotState, "lineupSlotState must not be null");
            players = List.copyOf(Objects.requireNonNull(players, "players must not be null"));
            Objects.requireNonNull(state, "state must not be null");
        }
    }
}
