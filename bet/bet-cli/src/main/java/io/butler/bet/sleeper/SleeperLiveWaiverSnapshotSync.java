package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueLineupConfigurationRepository;
import io.butler.bet.data.LiveWaiverSnapshotRepository;
import io.butler.bet.data.PlayerRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** BF-602 atomic persistence of a BF-601-proven live waiver frame plus deterministic league eligibility. */
public final class SleeperLiveWaiverSnapshotSync {
    public static final String POLICY_ID =
        "sleeper-live-waiver-snapshot-v1-bf601-same-frame-immutable-atomic";
    public static final String ELIGIBILITY_POLICY_ID =
        "live-waiver-eligibility-v1-provider-fantasy-position-lineup-slot-expansion";

    private final Database database;
    private final FrameSource source;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public SleeperLiveWaiverSnapshotSync(Database database) {
        this(database, new LiveSource(), Clock.systemUTC());
    }

    SleeperLiveWaiverSnapshotSync(Database database, FrameSource source, Clock clock) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public SyncReport sync(String leagueId) throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        CachedSource frame = new CachedSource(source);
        var proof = new SleeperLiveWaiverUniverseAudit(database, frame, clock).audit(normalizedLeagueId);
        if (proof.state() != SleeperLiveWaiverUniverseAudit.AuditState.READY) {
            throw new IllegalStateException("BF-601 waiver universe is " + proof.state() + ": " + proof.blockers());
        }

        Map<String, PlayerObservation> active = parseActivePlayers(frame.activePlayers());
        Set<String> rostered = parseRosteredIds(frame.rosters(proof.sleeperLeagueId()));
        Set<String> activeIds = new TreeSet<>(active.keySet());
        Set<String> activeRostered = intersection(activeIds, rostered);
        Set<String> freeAgents = difference(activeIds, rostered);
        Set<String> rosteredAbsentActive = difference(rostered, activeIds);

        if (activeIds.size() != proof.activePlayerIdentities()
            || rostered.size() != proof.currentRosterPlayerIdentities()
            || activeRostered.size() != proof.activeRosteredPlayerIdentities()
            || freeAgents.size() != proof.freeAgentIdentities()
            || rosteredAbsentActive.size() != proof.rosteredPlayerIdsAbsentFromActiveSource().size()) {
            throw new IllegalStateException("Same-frame BF-601 count reconciliation failed before persistence");
        }

        List<String> lineupSlots = new LeagueLineupConfigurationRepository(database).findByLeagueId(normalizedLeagueId);
        if (lineupSlots.isEmpty()) throw new IllegalStateException("Persisted league lineup configuration is empty");
        Set<String> eligiblePositions = eligiblePositions(lineupSlots);
        if (eligiblePositions.isEmpty()) throw new IllegalStateException("League lineup configuration yields no rosterable fantasy positions");

        List<LiveWaiverSnapshotRepository.Entry> entries = new ArrayList<>();
        int eligibleFreeAgents = 0;
        for (String id : activeIds) {
            PlayerObservation player = active.get(id);
            boolean isRostered = rostered.contains(id);
            boolean isFreeAgent = !isRostered;
            Eligibility eligibility = eligibility(player, isRostered, eligiblePositions);
            if (eligibility.eligible()) eligibleFreeAgents++;
            entries.add(new LiveWaiverSnapshotRepository.Entry(
                id, player.name(), player.position(), player.fantasyPositions(), player.team(), player.status(),
                isRostered, isFreeAgent, eligibility.eligible(), eligibility.reason()));
        }

        int canonicalPlayersBefore = new PlayerRepository(database).findAll().size();
        String snapshotId = UUID.randomUUID().toString();
        var snapshot = new LiveWaiverSnapshotRepository.Snapshot(
            snapshotId,
            normalizedLeagueId,
            proof.sleeperLeagueId(),
            proof.providerSeason(),
            proof.providerStatus(),
            proof.providerLeg(),
            proof.activePlayerSource(),
            SleeperLiveWaiverUniverseAudit.POLICY_ID,
            ELIGIBILITY_POLICY_ID,
            proof.observedAtUtc(),
            proof.currentRosterPlayerIdentities(),
            proof.activePlayerIdentities(),
            proof.activeRosteredPlayerIdentities(),
            proof.rosteredPlayerIdsAbsentFromActiveSource().size(),
            proof.freeAgentIdentities(),
            eligibleFreeAgents);

        LiveWaiverSnapshotRepository repository = new LiveWaiverSnapshotRepository(database);
        repository.save(snapshot, entries);
        var counts = repository.counts(snapshotId);
        if (counts.total() != proof.activePlayerIdentities()
            || counts.rostered() != proof.activeRosteredPlayerIdentities()
            || counts.freeAgents() != proof.freeAgentIdentities()
            || counts.eligibleFreeAgents() != eligibleFreeAgents) {
            throw new IllegalStateException("Persisted BF-602 snapshot readback reconciliation failed");
        }
        int canonicalPlayersAfter = new PlayerRepository(database).findAll().size();
        if (canonicalPlayersAfter != canonicalPlayersBefore) {
            throw new IllegalStateException("BF-602 changed canonical Butler player count");
        }

        Map<String, Integer> reasons = new TreeMap<>();
        entries.forEach(entry -> reasons.merge(entry.eligibilityReason(), 1, Integer::sum));
        List<String> eligibleExamples = entries.stream()
            .filter(LiveWaiverSnapshotRepository.Entry::leagueEligible)
            .limit(20)
            .map(entry -> entry.sleeperPlayerId() + " | " + entry.displayName() + " | pos=" + nullText(entry.position())
                + " | fantasy=" + entry.fantasyPositions() + " | team=" + nullText(entry.nflTeam())
                + " | status=" + nullText(entry.providerStatus()))
            .toList();

        return new SyncReport(
            POLICY_ID, ELIGIBILITY_POLICY_ID, snapshotId, normalizedLeagueId, proof.sleeperLeagueId(),
            proof.observedAtUtc().toString(), lineupSlots, List.copyOf(eligiblePositions),
            counts.total(), proof.currentRosterPlayerIdentities(), counts.rostered(), rosteredAbsentActive.size(),
            counts.freeAgents(), counts.eligibleFreeAgents(), Map.copyOf(reasons), eligibleExamples,
            canonicalPlayersBefore, canonicalPlayersAfter, repository.snapshotCountForLeague(normalizedLeagueId));
    }

    static Set<String> eligiblePositions(List<String> slots) {
        Set<String> positions = new LinkedHashSet<>();
        for (String raw : slots) {
            if (raw == null || raw.isBlank()) continue;
            String slot = raw.trim().toUpperCase();
            switch (slot) {
                case "BN", "BENCH", "IR", "RESERVE", "TAXI" -> { }
                case "FLEX" -> positions.addAll(List.of("RB", "WR", "TE"));
                case "SUPER_FLEX" -> positions.addAll(List.of("QB", "RB", "WR", "TE"));
                case "REC_FLEX" -> positions.addAll(List.of("WR", "TE"));
                case "WRRB_FLEX" -> positions.addAll(List.of("WR", "RB"));
                case "IDP_FLEX" -> positions.addAll(List.of("DB", "DL", "LB"));
                default -> positions.add(slot);
            }
        }
        return Set.copyOf(positions);
    }

    private static Eligibility eligibility(PlayerObservation player, boolean rostered, Set<String> eligiblePositions) {
        if (rostered) return new Eligibility(false, "ROSTERED");
        Set<String> candidatePositions = new TreeSet<>();
        player.fantasyPositions().stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().toUpperCase())
            .forEach(candidatePositions::add);
        if (candidatePositions.isEmpty() && usable(player.position())) {
            candidatePositions.add(player.position().trim().toUpperCase());
        }
        if (candidatePositions.isEmpty()) return new Eligibility(false, "NO_SUPPORTED_FANTASY_POSITION");
        Set<String> overlap = new TreeSet<>(candidatePositions);
        overlap.retainAll(eligiblePositions);
        return overlap.isEmpty()
            ? new Eligibility(false, "NO_LEAGUE_ELIGIBLE_POSITION_MATCH")
            : new Eligibility(true, "LEAGUE_ELIGIBLE_POSITION_MATCH");
    }

    private Map<String, PlayerObservation> parseActivePlayers(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "active-player payload"));
        if (root == null || !root.isObject()) throw new IllegalStateException("Sleeper active-player payload must be an object");
        Map<String, PlayerObservation> result = new TreeMap<>();
        var fields = root.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String id = requireText(entry.getKey(), "active player id");
            JsonNode node = entry.getValue();
            if (node == null || !node.isObject()) throw new IllegalStateException("Active player payload must be an object: " + id);
            String name = text(node.get("full_name"));
            if (!usable(name)) {
                String first = text(node.get("first_name"));
                String last = text(node.get("last_name"));
                name = ((first == null ? "" : first.trim()) + " " + (last == null ? "" : last.trim())).trim();
                if (name.isBlank()) name = id;
            }
            result.put(id, new PlayerObservation(id, name, text(node.get("position")), text(node.get("team")),
                text(node.get("status")), stringArray(node.get("fantasy_positions"))));
        }
        return Map.copyOf(result);
    }

    private Set<String> parseRosteredIds(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "rosters payload"));
        if (root == null || !root.isArray()) throw new IllegalStateException("Sleeper rosters payload must be an array");
        Set<String> ids = new TreeSet<>();
        for (JsonNode roster : root) {
            JsonNode players = roster.get("players");
            if (players == null || !players.isArray()) throw new IllegalStateException("Sleeper roster has no players array");
            for (JsonNode value : players) {
                String id = text(value);
                if (usable(id) && !"0".equals(id.trim())) ids.add(id.trim());
            }
        }
        return Set.copyOf(ids);
    }

    private static List<String> stringArray(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalStateException("fantasy_positions must be an array when present");
        List<String> values = new ArrayList<>();
        node.forEach(value -> { String text = text(value); if (usable(text)) values.add(text.trim()); });
        return List.copyOf(values);
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left); result.retainAll(right); return result;
    }
    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left); result.removeAll(right); return result;
    }
    private static String text(JsonNode node) { return node == null || node.isNull() ? null : node.asText(null); }
    private static boolean usable(String value) { return value != null && !value.isBlank(); }
    private static String requireText(String value, String field) {
        if (!usable(value)) throw new IllegalArgumentException(field + " must not be blank"); return value.trim();
    }
    private static String nullText(String value) { return usable(value) ? value : "none"; }

    interface FrameSource extends SleeperLiveWaiverUniverseAudit.FrameSource {}

    private static final class LiveSource implements FrameSource {
        private final SleeperClient client = new SleeperClient();
        @Override public String league(String id) throws IOException, InterruptedException { return client.getLeague(id); }
        @Override public String rosters(String id) throws IOException, InterruptedException { return client.getLeagueRosters(id); }
        @Override public String users(String id) throws IOException, InterruptedException { return client.getLeagueUsers(id); }
        @Override public String activePlayers() throws IOException, InterruptedException { return client.getNflActivePlayers(); }
    }

    private static final class CachedSource implements FrameSource {
        private final FrameSource delegate;
        private final Map<String, String> leagues = new LinkedHashMap<>();
        private final Map<String, String> rosters = new LinkedHashMap<>();
        private final Map<String, String> users = new LinkedHashMap<>();
        private String activePlayers;
        CachedSource(FrameSource delegate) { this.delegate = delegate; }
        @Override public String league(String id) throws IOException, InterruptedException {
            if (!leagues.containsKey(id)) leagues.put(id, delegate.league(id)); return leagues.get(id);
        }
        @Override public String rosters(String id) throws IOException, InterruptedException {
            if (!rosters.containsKey(id)) rosters.put(id, delegate.rosters(id)); return rosters.get(id);
        }
        @Override public String users(String id) throws IOException, InterruptedException {
            if (!users.containsKey(id)) users.put(id, delegate.users(id)); return users.get(id);
        }
        @Override public String activePlayers() throws IOException, InterruptedException {
            if (activePlayers == null) activePlayers = delegate.activePlayers(); return activePlayers;
        }
    }

    private record PlayerObservation(String id, String name, String position, String team, String status, List<String> fantasyPositions) {}
    private record Eligibility(boolean eligible, String reason) {}

    public record SyncReport(
        String policyId, String eligibilityPolicyId, String snapshotId, String leagueId, String sleeperLeagueId,
        String observedAtUtc, List<String> lineupSlots, List<String> eligiblePositions,
        int persistedActiveEntries, int currentRosterIdentities, int activeRosteredEntries,
        int rosteredAbsentActive, int freeAgentEntries, int leagueEligibleFreeAgents,
        Map<String, Integer> eligibilityReasons, List<String> eligibleExamples,
        int canonicalPlayersBefore, int canonicalPlayersAfter, int snapshotCountForLeague) {
        public SyncReport {
            lineupSlots = List.copyOf(lineupSlots); eligiblePositions = List.copyOf(eligiblePositions);
            eligibilityReasons = Map.copyOf(eligibilityReasons); eligibleExamples = List.copyOf(eligibleExamples);
        }
    }
}
