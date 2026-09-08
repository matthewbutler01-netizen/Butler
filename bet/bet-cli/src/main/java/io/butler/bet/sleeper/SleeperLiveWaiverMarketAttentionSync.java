package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LiveWaiverMarketAttentionRepository;
import io.butler.bet.data.LiveWaiverSnapshotRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** BF-603 fresh Sleeper waiver add/drop market-attention evidence against the latest BF-602 snapshot. */
public final class SleeperLiveWaiverMarketAttentionSync {
    public static final String POLICY_ID =
        "sleeper-live-waiver-market-attention-v1-latest-bf602-roster-stable-24h-top200";
    public static final int TARGET_SEASON = 2026;
    public static final int LOOKBACK_HOURS = 24;
    public static final int RESULT_LIMIT = 200;
    public static final Duration MAX_WAIVER_SNAPSHOT_AGE = Duration.ofHours(6);
    private static final int EXAMPLE_LIMIT = 20;

    private final Database database;
    private final Source source;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public SleeperLiveWaiverMarketAttentionSync(Database database) {
        this(database, new LiveSource(), Clock.systemUTC());
    }

    SleeperLiveWaiverMarketAttentionSync(Database database, Source source, Clock clock) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public SyncReport sync(String leagueId) throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        Instant now = Instant.now(clock);

        var league = new LeagueRepository(database).findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + normalizedLeagueId));
        String sleeperLeagueId = requireText(league.getExternalId(), "league external Sleeper id");

        LiveWaiverSnapshotRepository waiverRepository = new LiveWaiverSnapshotRepository(database);
        var waiverSnapshot = waiverRepository.latestForLeague(normalizedLeagueId)
            .orElseThrow(() -> new IllegalStateException("BF-603 BLOCKED: no BF-602 waiver snapshot exists for league"));
        if (!sleeperLeagueId.equals(waiverSnapshot.sleeperLeagueId())) {
            throw new IllegalStateException("BF-603 BLOCKED: latest BF-602 snapshot Sleeper league does not match current linked league");
        }
        if (waiverSnapshot.season() != TARGET_SEASON || !"in_season".equals(waiverSnapshot.providerStatus())) {
            throw new IllegalStateException("BF-603 BLOCKED: latest BF-602 snapshot is not a 2026 in-season observation");
        }
        Duration snapshotAge = Duration.between(waiverSnapshot.observedAtUtc(), now);
        if (snapshotAge.isNegative()) {
            throw new IllegalStateException("BF-603 BLOCKED: latest BF-602 snapshot observation is in the future");
        }
        if (snapshotAge.compareTo(MAX_WAIVER_SNAPSHOT_AGE) > 0) {
            throw new IllegalStateException("BF-603 BLOCKED: latest BF-602 snapshot is stale at "
                + snapshotAge.toMinutes() + " minutes old; refresh BF-602 first");
        }

        List<LiveWaiverSnapshotRepository.Entry> waiverEntries = waiverRepository.entries(waiverSnapshot.id());
        Set<String> persistedRosteredIds = new TreeSet<>();
        List<LiveWaiverSnapshotRepository.Entry> candidates = new ArrayList<>();
        for (var entry : waiverEntries) {
            if (entry.rostered()) persistedRosteredIds.add(entry.sleeperPlayerId());
            if (entry.leagueEligible()) candidates.add(entry);
        }
        candidates.sort(Comparator.comparing(LiveWaiverSnapshotRepository.Entry::sleeperPlayerId));
        if (persistedRosteredIds.size() != waiverSnapshot.activeRosteredIdentityCount()) {
            throw new IllegalStateException("BF-603 BLOCKED: persisted BF-602 rostered identity readback does not reconcile");
        }
        if (candidates.size() != waiverSnapshot.leagueEligibleFreeAgentCount()) {
            throw new IllegalStateException("BF-603 BLOCKED: persisted BF-602 eligible candidate readback does not reconcile");
        }

        ProviderLeague providerLeague = parseLeague(source.league(sleeperLeagueId));
        if (!sleeperLeagueId.equals(providerLeague.leagueId())) {
            throw new IllegalStateException("BF-603 BLOCKED: provider league id does not match linked Sleeper league");
        }
        if (providerLeague.season() != TARGET_SEASON) {
            throw new IllegalStateException("BF-603 BLOCKED: provider season is " + providerLeague.season());
        }
        if (!"in_season".equals(providerLeague.status())) {
            throw new IllegalStateException("BF-603 BLOCKED: provider status is " + providerLeague.status());
        }

        RosterFrame rosterFrame = parseRosterFrame(source.rosters(sleeperLeagueId));
        if (!rosterFrame.duplicatePlayerIds().isEmpty()) {
            throw new IllegalStateException("BF-603 BLOCKED: current provider roster surface contains duplicate player identities: "
                + rosterFrame.duplicatePlayerIds());
        }
        if (!rosterFrame.playerIds().equals(persistedRosteredIds)) {
            Set<String> addedSinceSnapshot = difference(rosterFrame.playerIds(), persistedRosteredIds);
            Set<String> removedSinceSnapshot = difference(persistedRosteredIds, rosterFrame.playerIds());
            throw new IllegalStateException("BF-603 BLOCKED: current roster membership drifted since BF-602; added="
                + addedSinceSnapshot + " removed=" + removedSinceSnapshot + "; refresh BF-602 first");
        }

        Map<String, Integer> adds = parseTrendFrame(source.trending("add", LOOKBACK_HOURS, RESULT_LIMIT), "add");
        Map<String, Integer> drops = parseTrendFrame(source.trending("drop", LOOKBACK_HOURS, RESULT_LIMIT), "drop");

        List<LiveWaiverMarketAttentionRepository.Entry> entries = new ArrayList<>();
        int addOnly = 0;
        int dropOnly = 0;
        int both = 0;
        int neither = 0;
        for (var candidate : candidates) {
            boolean inAdd = adds.containsKey(candidate.sleeperPlayerId());
            boolean inDrop = drops.containsKey(candidate.sleeperPlayerId());
            int addCount = adds.getOrDefault(candidate.sleeperPlayerId(), 0);
            int dropCount = drops.getOrDefault(candidate.sleeperPlayerId(), 0);
            String membership;
            if (inAdd && inDrop) {
                membership = "BOTH";
                both++;
            } else if (inAdd) {
                membership = "ADD_ONLY";
                addOnly++;
            } else if (inDrop) {
                membership = "DROP_ONLY";
                dropOnly++;
            } else {
                membership = "NEITHER";
                neither++;
            }
            entries.add(new LiveWaiverMarketAttentionRepository.Entry(
                candidate.sleeperPlayerId(), candidate.displayName(), candidate.position(), candidate.nflTeam(),
                candidate.providerStatus(), addCount, dropCount, addCount - dropCount, inAdd, inDrop, membership));
        }

        String marketSnapshotId = UUID.randomUUID().toString();
        Instant observedAt = Instant.now(clock);
        var marketSnapshot = new LiveWaiverMarketAttentionRepository.Snapshot(
            marketSnapshotId,
            normalizedLeagueId,
            waiverSnapshot.id(),
            sleeperLeagueId,
            providerLeague.season(),
            providerLeague.status(),
            providerLeague.leg(),
            POLICY_ID,
            LOOKBACK_HOURS,
            RESULT_LIMIT,
            observedAt,
            entries.size(),
            adds.size(),
            drops.size(),
            addOnly,
            dropOnly,
            both,
            neither);

        LiveWaiverMarketAttentionRepository marketRepository = new LiveWaiverMarketAttentionRepository(database);
        marketRepository.save(marketSnapshot, entries);
        var counts = marketRepository.counts(marketSnapshotId);
        if (counts.total() != candidates.size()
            || counts.addOnly() != addOnly
            || counts.dropOnly() != dropOnly
            || counts.both() != both
            || counts.neither() != neither) {
            throw new IllegalStateException("BF-603 persisted market-attention readback reconciliation failed");
        }

        List<String> topAddExamples = entries.stream()
            .filter(LiveWaiverMarketAttentionRepository.Entry::inAddFrame)
            .sorted(Comparator.comparingInt(LiveWaiverMarketAttentionRepository.Entry::addCount).reversed()
                .thenComparing(LiveWaiverMarketAttentionRepository.Entry::sleeperPlayerId))
            .limit(EXAMPLE_LIMIT)
            .map(SleeperLiveWaiverMarketAttentionSync::formatExample)
            .toList();
        List<String> topDropExamples = entries.stream()
            .filter(LiveWaiverMarketAttentionRepository.Entry::inDropFrame)
            .sorted(Comparator.comparingInt(LiveWaiverMarketAttentionRepository.Entry::dropCount).reversed()
                .thenComparing(LiveWaiverMarketAttentionRepository.Entry::sleeperPlayerId))
            .limit(EXAMPLE_LIMIT)
            .map(SleeperLiveWaiverMarketAttentionSync::formatExample)
            .toList();
        List<String> topNetExamples = entries.stream()
            .filter(entry -> entry.inAddFrame() || entry.inDropFrame())
            .sorted(Comparator.comparingInt(LiveWaiverMarketAttentionRepository.Entry::netAddAttention).reversed()
                .thenComparing(LiveWaiverMarketAttentionRepository.Entry::sleeperPlayerId))
            .limit(EXAMPLE_LIMIT)
            .map(SleeperLiveWaiverMarketAttentionSync::formatExample)
            .toList();

        return new SyncReport(
            POLICY_ID,
            marketSnapshotId,
            waiverSnapshot.id(),
            waiverSnapshot.observedAtUtc(),
            snapshotAge,
            normalizedLeagueId,
            sleeperLeagueId,
            providerLeague.season(),
            providerLeague.status(),
            providerLeague.leg(),
            LOOKBACK_HOURS,
            RESULT_LIMIT,
            candidates.size(),
            adds.size(),
            drops.size(),
            addOnly,
            dropOnly,
            both,
            neither,
            topAddExamples,
            topDropExamples,
            topNetExamples,
            observedAt,
            marketRepository.snapshotCountForLeague(normalizedLeagueId));
    }

    private ProviderLeague parseLeague(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "league payload"));
        if (root == null || !root.isObject()) throw new IllegalStateException("Sleeper league payload must be an object");
        String id = requireText(text(root.get("league_id")), "provider league_id");
        int season = parseSeason(root.get("season"));
        String status = text(root.get("status"));
        JsonNode settings = root.path("settings");
        Integer leg = settings.has("leg") && settings.get("leg").canConvertToInt()
            ? settings.get("leg").intValue() : null;
        return new ProviderLeague(id, season, status, leg);
    }

    private RosterFrame parseRosterFrame(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "rosters payload"));
        if (root == null || !root.isArray()) throw new IllegalStateException("Sleeper rosters payload must be an array");
        Set<String> ids = new TreeSet<>();
        Set<String> duplicates = new TreeSet<>();
        Map<String, Integer> firstRoster = new TreeMap<>();
        for (JsonNode roster : root) {
            int rosterId = roster.path("roster_id").asInt(0);
            if (rosterId <= 0) throw new IllegalStateException("Current roster payload contains invalid roster_id");
            JsonNode players = roster.get("players");
            if (players == null || !players.isArray()) {
                throw new IllegalStateException("Current roster " + rosterId + " has no players array");
            }
            for (JsonNode value : players) {
                String id = text(value);
                if (id == null || id.isBlank() || "0".equals(id.trim())) continue;
                String normalized = id.trim();
                Integer prior = firstRoster.putIfAbsent(normalized, rosterId);
                if (prior != null && prior != rosterId) duplicates.add(normalized);
                ids.add(normalized);
            }
        }
        return new RosterFrame(Set.copyOf(ids), List.copyOf(duplicates));
    }

    private Map<String, Integer> parseTrendFrame(String json, String type) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, type + " trending payload"));
        if (root == null || !root.isArray()) throw new IllegalStateException("Sleeper " + type + " trending payload must be an array");
        Map<String, Integer> result = new LinkedHashMap<>();
        for (JsonNode row : root) {
            if (row == null || !row.isObject()) throw new IllegalStateException("Sleeper " + type + " trend row must be an object");
            String playerId = requireText(text(row.get("player_id")), type + " trend player_id");
            JsonNode countNode = row.get("count");
            if (countNode == null || !countNode.canConvertToInt()) {
                throw new IllegalStateException("Sleeper " + type + " trend count must be an integer for " + playerId);
            }
            int count = countNode.intValue();
            if (count < 0) throw new IllegalStateException("Sleeper " + type + " trend count must be nonnegative for " + playerId);
            if (result.putIfAbsent(playerId, count) != null) {
                throw new IllegalStateException("Duplicate Sleeper " + type + " trending player id: " + playerId);
            }
        }
        return Map.copyOf(result);
    }

    private static String formatExample(LiveWaiverMarketAttentionRepository.Entry entry) {
        return entry.sleeperPlayerId() + " | " + entry.displayName()
            + " | pos=" + nullText(entry.position())
            + " | team=" + nullText(entry.nflTeam())
            + " | status=" + nullText(entry.providerStatus())
            + " | add=" + entry.addCount()
            + " | drop=" + entry.dropCount()
            + " | net=" + entry.netAddAttention()
            + " | frame=" + entry.frameMembership();
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static int parseSeason(JsonNode value) {
        if (value == null || value.isNull()) return 0;
        if (value.canConvertToInt()) return value.intValue();
        try { return Integer.parseInt(value.asText("0").trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String text(JsonNode node) { return node == null || node.isNull() ? null : node.asText(null); }
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
    private static String nullText(String value) { return value == null || value.isBlank() ? "none" : value; }

    interface Source {
        String league(String sleeperLeagueId) throws IOException, InterruptedException;
        String rosters(String sleeperLeagueId) throws IOException, InterruptedException;
        String trending(String type, int lookbackHours, int limit) throws IOException, InterruptedException;
    }

    private static final class LiveSource implements Source {
        private final SleeperClient client = new SleeperClient();
        @Override public String league(String id) throws IOException, InterruptedException { return client.getLeague(id); }
        @Override public String rosters(String id) throws IOException, InterruptedException { return client.getLeagueRosters(id); }
        @Override public String trending(String type, int lookbackHours, int limit) throws IOException, InterruptedException {
            return client.getNflTrendingPlayers(type, lookbackHours, limit);
        }
    }

    private record ProviderLeague(String leagueId, int season, String status, Integer leg) {}
    private record RosterFrame(Set<String> playerIds, List<String> duplicatePlayerIds) {}

    public record SyncReport(
        String policyId,
        String marketSnapshotId,
        String waiverSnapshotId,
        Instant waiverSnapshotObservedAtUtc,
        Duration waiverSnapshotAge,
        String leagueId,
        String sleeperLeagueId,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        int lookbackHours,
        int resultLimit,
        int candidateCount,
        int addFrameSize,
        int dropFrameSize,
        int addOnlyCount,
        int dropOnlyCount,
        int bothCount,
        int neitherCount,
        List<String> topAddExamples,
        List<String> topDropExamples,
        List<String> topNetExamples,
        Instant observedAtUtc,
        int marketSnapshotCountForLeague) {
        public SyncReport {
            topAddExamples = List.copyOf(topAddExamples);
            topDropExamples = List.copyOf(topDropExamples);
            topNetExamples = List.copyOf(topNetExamples);
        }
    }
}
