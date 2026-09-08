package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Read-only BF-601 proof of the complete active Sleeper identity universe minus exact live roster membership. */
public final class SleeperLiveWaiverUniverseAudit {
    public static final String POLICY_ID =
        "sleeper-live-waiver-universe-v1-active-source-exact-roster-subtraction-read-only";
    public static final String ACTIVE_PLAYER_SOURCE = "players/nfl?active=true";
    private static final int EXAMPLE_LIMIT = 20;

    private final Database database;
    private final FrameSource source;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public SleeperLiveWaiverUniverseAudit(Database database) {
        this(database, new LiveSource(), Clock.systemUTC());
    }

    SleeperLiveWaiverUniverseAudit(Database database, FrameSource source, Clock clock) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public AuditReport audit(String leagueId) throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        CachedFrameSource frame = new CachedFrameSource(source);

        var live = new SleeperLiveSeasonOperationalReadinessAudit(database, frame, clock).audit(normalizedLeagueId);
        List<String> blockers = new ArrayList<>();
        if (live.currentRosterContext().state() != SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.READY) {
            blockers.add("BF-598 current-roster context is " + live.currentRosterContext().state());
            live.currentRosterContext().blockers().forEach(value -> blockers.add("BF-598 roster: " + value));
        }
        if (live.lineupContextPrerequisites().state() != SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.READY) {
            blockers.add("BF-598 lineup prerequisites are " + live.lineupContextPrerequisites().state());
        }
        if (live.tradeContextPrerequisites().state() != SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.READY) {
            blockers.add("BF-598 trade prerequisites are " + live.tradeContextPrerequisites().state());
        }

        RosterIdentityFrame rosterFrame = parseRosterFrame(frame.rosters(live.sleeperLeagueId()));
        if (!rosterFrame.duplicatePlayerIds().isEmpty()) {
            blockers.add("Current provider roster surface contains duplicate player identities across rosters: "
                + rosterFrame.duplicatePlayerIds());
        }

        Map<String, ActivePlayerObservation> activePlayers = parseActivePlayers(frame.activePlayers());
        if (activePlayers.isEmpty()) blockers.add("Sleeper active-player source returned no identities");

        Set<String> activeIds = new TreeSet<>(activePlayers.keySet());
        Set<String> currentRosterIds = rosterFrame.playerIds();
        Set<String> activeRosteredIds = intersection(currentRosterIds, activeIds);
        Set<String> rosteredAbsentFromActive = difference(currentRosterIds, activeIds);
        Set<String> freeAgentIds = difference(activeIds, currentRosterIds);
        Set<String> freeAgentRosterOverlap = intersection(freeAgentIds, currentRosterIds);

        if (!freeAgentRosterOverlap.isEmpty()) {
            blockers.add("Derived free-agent inventory overlaps current roster identities: " + freeAgentRosterOverlap);
        }
        if (activeIds.size() != activeRosteredIds.size() + freeAgentIds.size()) {
            blockers.add("Active identity reconciliation failed: active=" + activeIds.size()
                + " rostered-active=" + activeRosteredIds.size() + " free-agent=" + freeAgentIds.size());
        }

        Set<String> persistedPlayerIds = new TreeSet<>();
        new PlayerRepository(database).findAll().forEach(player -> {
            if (player.getExternalId() != null && !player.getExternalId().isBlank()) {
                persistedPlayerIds.add(player.getExternalId().trim());
            }
        });
        Set<String> mappedFreeAgents = intersection(freeAgentIds, persistedPlayerIds);
        Set<String> unmappedFreeAgents = difference(freeAgentIds, persistedPlayerIds);

        int freeAgentsWithPosition = 0;
        int freeAgentsWithFantasyPositions = 0;
        int freeAgentsWithTeam = 0;
        int freeAgentsWithStatus = 0;
        for (String id : freeAgentIds) {
            ActivePlayerObservation observation = activePlayers.get(id);
            if (usable(observation.position())) freeAgentsWithPosition++;
            if (!observation.fantasyPositions().isEmpty()) freeAgentsWithFantasyPositions++;
            if (usable(observation.team())) freeAgentsWithTeam++;
            if (usable(observation.status())) freeAgentsWithStatus++;
        }

        List<PlayerExample> freeAgentExamples = freeAgentIds.stream()
            .limit(EXAMPLE_LIMIT)
            .map(id -> example(activePlayers.get(id)))
            .toList();
        List<PlayerExample> unmappedFreeAgentExamples = unmappedFreeAgents.stream()
            .limit(EXAMPLE_LIMIT)
            .map(id -> example(activePlayers.get(id)))
            .toList();

        AuditState state = blockers.isEmpty() ? AuditState.READY : AuditState.BLOCKED;
        return new AuditReport(
            POLICY_ID,
            ACTIVE_PLAYER_SOURCE,
            normalizedLeagueId,
            live.leagueName(),
            live.sleeperLeagueId(),
            live.providerSeason(),
            live.providerStatus(),
            live.providerLeg(),
            live.providerRosterCount(),
            currentRosterIds.size(),
            activeIds.size(),
            activeRosteredIds.size(),
            List.copyOf(rosteredAbsentFromActive),
            freeAgentIds.size(),
            mappedFreeAgents.size(),
            unmappedFreeAgents.size(),
            unmappedFreeAgentExamples,
            freeAgentsWithPosition,
            freeAgentsWithFantasyPositions,
            freeAgentsWithTeam,
            freeAgentsWithStatus,
            freeAgentExamples,
            state,
            List.copyOf(blockers),
            Instant.now(clock));
    }

    private RosterIdentityFrame parseRosterFrame(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "rosters payload"));
        if (root == null || !root.isArray()) throw new IllegalStateException("Sleeper rosters payload must be an array");
        Set<String> ids = new TreeSet<>();
        Set<String> duplicates = new TreeSet<>();
        Map<String, Integer> firstRosterByPlayer = new TreeMap<>();
        for (JsonNode roster : root) {
            int rosterId = roster.path("roster_id").asInt(0);
            if (rosterId <= 0) throw new IllegalStateException("Current roster payload contains invalid roster_id");
            JsonNode players = roster.get("players");
            if (players == null || !players.isArray()) {
                throw new IllegalStateException("Current roster " + rosterId + " has no players array");
            }
            for (JsonNode value : players) {
                String id = text(value);
                if (!usable(id) || "0".equals(id.trim())) continue;
                String normalized = id.trim();
                Integer priorRoster = firstRosterByPlayer.putIfAbsent(normalized, rosterId);
                if (priorRoster != null && priorRoster != rosterId) duplicates.add(normalized);
                ids.add(normalized);
            }
        }
        return new RosterIdentityFrame(Set.copyOf(ids), List.copyOf(duplicates));
    }

    private Map<String, ActivePlayerObservation> parseActivePlayers(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "active-player payload"));
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Sleeper active-player payload must be an object");
        }
        Map<String, ActivePlayerObservation> result = new TreeMap<>();
        var fields = root.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String id = requireText(entry.getKey(), "active player id");
            JsonNode node = entry.getValue();
            if (node == null || !node.isObject()) {
                throw new IllegalStateException("Sleeper active player " + id + " payload must be an object");
            }
            String name = text(node.get("full_name"));
            if (!usable(name)) {
                String first = text(node.get("first_name"));
                String last = text(node.get("last_name"));
                String combined = ((first == null ? "" : first.trim()) + " " + (last == null ? "" : last.trim())).trim();
                name = combined.isBlank() ? id : combined;
            }
            List<String> fantasyPositions = stringArray(node.get("fantasy_positions"));
            ActivePlayerObservation previous = result.put(id, new ActivePlayerObservation(
                id,
                name,
                text(node.get("position")),
                text(node.get("team")),
                text(node.get("status")),
                fantasyPositions));
            if (previous != null) throw new IllegalStateException("Duplicate active player id: " + id);
        }
        return Map.copyOf(result);
    }

    private static PlayerExample example(ActivePlayerObservation value) {
        return new PlayerExample(value.id(), value.name(), value.position(), value.team(), value.status(), value.fantasyPositions());
    }

    private static List<String> stringArray(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalStateException("fantasy_positions must be an array when present");
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            String text = text(value);
            if (usable(text)) result.add(text.trim());
        }
        return List.copyOf(result);
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.retainAll(right);
        return result;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }

    private static boolean usable(String value) {
        return value != null && !value.isBlank();
    }

    private static String requireText(String value, String field) {
        if (!usable(value)) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    interface FrameSource extends SleeperLiveSeasonOperationalReadinessAudit.Source {
        String activePlayers() throws IOException, InterruptedException;
    }

    private static final class LiveSource implements FrameSource {
        private final SleeperClient client = new SleeperClient();
        @Override public String league(String sleeperLeagueId) throws IOException, InterruptedException { return client.getLeague(sleeperLeagueId); }
        @Override public String rosters(String sleeperLeagueId) throws IOException, InterruptedException { return client.getLeagueRosters(sleeperLeagueId); }
        @Override public String users(String sleeperLeagueId) throws IOException, InterruptedException { return client.getLeagueUsers(sleeperLeagueId); }
        @Override public String activePlayers() throws IOException, InterruptedException { return client.getNflActivePlayers(); }
    }

    private static final class CachedFrameSource implements FrameSource {
        private final FrameSource delegate;
        private final Map<String, String> leagues = new LinkedHashMap<>();
        private final Map<String, String> rosters = new LinkedHashMap<>();
        private final Map<String, String> users = new LinkedHashMap<>();
        private String activePlayers;

        private CachedFrameSource(FrameSource delegate) { this.delegate = delegate; }

        @Override public String league(String sleeperLeagueId) throws IOException, InterruptedException {
            String cached = leagues.get(sleeperLeagueId);
            if (cached != null) return cached;
            String value = delegate.league(sleeperLeagueId);
            leagues.put(sleeperLeagueId, value);
            return value;
        }

        @Override public String rosters(String sleeperLeagueId) throws IOException, InterruptedException {
            String cached = rosters.get(sleeperLeagueId);
            if (cached != null) return cached;
            String value = delegate.rosters(sleeperLeagueId);
            rosters.put(sleeperLeagueId, value);
            return value;
        }

        @Override public String users(String sleeperLeagueId) throws IOException, InterruptedException {
            String cached = users.get(sleeperLeagueId);
            if (cached != null) return cached;
            String value = delegate.users(sleeperLeagueId);
            users.put(sleeperLeagueId, value);
            return value;
        }

        @Override public String activePlayers() throws IOException, InterruptedException {
            if (activePlayers == null) activePlayers = delegate.activePlayers();
            return activePlayers;
        }
    }

    public enum AuditState { READY, BLOCKED }

    public record PlayerExample(
        String playerId,
        String name,
        String position,
        String team,
        String status,
        List<String> fantasyPositions) {
        public PlayerExample { fantasyPositions = List.copyOf(fantasyPositions); }
    }

    public record AuditReport(
        String policyId,
        String activePlayerSource,
        String leagueId,
        String leagueName,
        String sleeperLeagueId,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        int providerRosterCount,
        int currentRosterPlayerIdentities,
        int activePlayerIdentities,
        int activeRosteredPlayerIdentities,
        List<String> rosteredPlayerIdsAbsentFromActiveSource,
        int freeAgentIdentities,
        int exactButlerMappedFreeAgents,
        int unmappedFreeAgents,
        List<PlayerExample> unmappedFreeAgentExamples,
        int freeAgentsWithPosition,
        int freeAgentsWithFantasyPositions,
        int freeAgentsWithTeam,
        int freeAgentsWithStatus,
        List<PlayerExample> freeAgentExamples,
        AuditState state,
        List<String> blockers,
        Instant observedAtUtc) {
        public AuditReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            activePlayerSource = requireText(activePlayerSource, "activePlayerSource");
            leagueId = requireText(leagueId, "leagueId");
            leagueName = requireText(leagueName, "leagueName");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            rosteredPlayerIdsAbsentFromActiveSource = List.copyOf(rosteredPlayerIdsAbsentFromActiveSource);
            unmappedFreeAgentExamples = List.copyOf(unmappedFreeAgentExamples);
            freeAgentExamples = List.copyOf(freeAgentExamples);
            blockers = List.copyOf(blockers);
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(observedAtUtc, "observedAtUtc must not be null");
            if (state == AuditState.READY && !blockers.isEmpty()) throw new IllegalArgumentException("READY must have no blockers");
            if (state == AuditState.BLOCKED && blockers.isEmpty()) throw new IllegalArgumentException("BLOCKED must have blockers");
        }
    }

    private record ActivePlayerObservation(
        String id,
        String name,
        String position,
        String team,
        String status,
        List<String> fantasyPositions) {}

    private record RosterIdentityFrame(Set<String> playerIds, List<String> duplicatePlayerIds) {}
}
