package io.butler.bet.sleeper;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LiveWaiverAvailabilityRepository;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
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

/** BF-606 immutable current availability/depth metadata for the exact latest BF-603 market-active frame. */
public final class SleeperLiveWaiverAvailabilitySync {
    public static final String POLICY_ID =
        "sleeper-live-waiver-availability-v1-latest-bf603-exact-current-player-map-daily-guard";
    public static final String SOURCE = "players/nfl?active=true";
    public static final int TARGET_SEASON = 2026;
    public static final Duration MIN_SOURCE_REFRESH_INTERVAL = Duration.ofHours(24);

    private final Database database;
    private final Source source;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper(
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    public SleeperLiveWaiverAvailabilitySync(Database database) {
        this(database, new LiveSource(), Clock.systemUTC());
    }

    SleeperLiveWaiverAvailabilitySync(Database database, Source source, Clock clock) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public SyncReport sync(String leagueId) throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        Instant now = Instant.now(clock);

        SleeperLiveWaiverProductionCoverageAudit.AuditReport coverage =
            new SleeperLiveWaiverProductionCoverageAudit(database).audit(normalizedLeagueId);
        if (coverage.unmappedCanonical() != 0) {
            throw new IllegalStateException("BF-606 BLOCKED: BF-604 still has "
                + coverage.unmappedCanonical() + " unmapped market-active identities; run BF-605 first");
        }
        if (coverage.marketActiveCandidates() <= 0) {
            throw new IllegalStateException("BF-606 BLOCKED: latest BF-603 market-active frame is empty");
        }

        MarketHeader market = marketHeader(coverage.marketSnapshotId());
        if (!normalizedLeagueId.equals(market.leagueId())) {
            throw new IllegalStateException("BF-606 BLOCKED: BF-604 and BF-603 league identities disagree");
        }
        if (market.season() != TARGET_SEASON || !"in_season".equals(market.providerStatus())) {
            throw new IllegalStateException("BF-606 BLOCKED: latest BF-603 snapshot is not a 2026 in-season frame");
        }

        var linkedLeague = new LeagueRepository(database).findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + normalizedLeagueId));
        String sleeperLeagueId = requireText(linkedLeague.getExternalId(), "linked Sleeper league id");
        if (!sleeperLeagueId.equals(market.sleeperLeagueId())) {
            throw new IllegalStateException("BF-606 BLOCKED: latest BF-603 Sleeper league does not match current linked league");
        }

        LiveWaiverAvailabilityRepository repository = new LiveWaiverAvailabilityRepository(database);
        var recent = repository.latestForMarketSnapshot(market.snapshotId());
        if (recent.isPresent()) {
            Duration age = Duration.between(recent.get().observedAtUtc(), now);
            if (age.isNegative()) {
                throw new IllegalStateException("BF-606 BLOCKED: prior availability observation is in the future");
            }
            if (age.compareTo(MIN_SOURCE_REFRESH_INTERVAL) < 0) {
                throw new IllegalStateException("BF-606 BLOCKED: availability for this BF-603 frame was already captured "
                    + age.toMinutes() + " minutes ago; Sleeper player-map refresh interval is 24 hours");
            }
        }

        ProviderLeague providerLeague = parseLeague(source.league(sleeperLeagueId));
        if (!sleeperLeagueId.equals(providerLeague.leagueId())) {
            throw new IllegalStateException("BF-606 BLOCKED: provider league id does not match linked Sleeper league");
        }
        if (providerLeague.season() != TARGET_SEASON || !"in_season".equals(providerLeague.status())) {
            throw new IllegalStateException("BF-606 BLOCKED: provider league is not 2026/in_season");
        }

        Set<String> targetIds = new TreeSet<>();
        for (var candidate : coverage.candidates()) targetIds.add(candidate.market().sleeperPlayerId());
        if (targetIds.size() != coverage.marketActiveCandidates()) {
            throw new IllegalStateException("BF-606 BLOCKED: duplicate exact target identities in BF-603 market-active frame");
        }

        RosterFrame rosterFrame = parseRosterFrame(source.rosters(sleeperLeagueId));
        if (!rosterFrame.duplicatePlayerIds().isEmpty()) {
            throw new IllegalStateException("BF-606 BLOCKED: current provider roster surface contains duplicate identities: "
                + rosterFrame.duplicatePlayerIds());
        }
        Set<String> nowRosteredTargets = intersection(targetIds, rosterFrame.playerIds());
        if (!nowRosteredTargets.isEmpty()) {
            throw new IllegalStateException("BF-606 BLOCKED: BF-603 market-active candidates are now rostered: "
                + nowRosteredTargets + "; refresh BF-602/BF-603 first");
        }

        Map<String, CurrentPlayer> activePlayers = parseActivePlayers(source.activePlayers());
        List<LiveWaiverAvailabilityRepository.Entry> entries = new ArrayList<>();
        int present = 0;
        int absent = 0;
        int withTeam = 0;
        int withStatus = 0;
        int withInjury = 0;
        int withPractice = 0;
        int withDepthPosition = 0;
        int withDepthOrder = 0;

        List<SleeperLiveWaiverProductionCoverageAudit.CandidateCoverage> candidates =
            new ArrayList<>(coverage.candidates());
        candidates.sort(Comparator.comparing(value -> value.market().sleeperPlayerId()));
        for (var candidate : candidates) {
            var marketCandidate = candidate.market();
            CurrentPlayer current = activePlayers.get(marketCandidate.sleeperPlayerId());
            if (current == null) {
                absent++;
                entries.add(new LiveWaiverAvailabilityRepository.Entry(
                    marketCandidate.sleeperPlayerId(), marketCandidate.displayName(), marketCandidate.position(),
                    marketCandidate.addCount(), marketCandidate.dropCount(), marketCandidate.netAddAttention(),
                    marketCandidate.frameMembership(), "SOURCE_ABSENT", null, null, null, null, null, null, null));
                continue;
            }
            present++;
            if (usable(current.team())) withTeam++;
            if (usable(current.status())) withStatus++;
            if (usable(current.injuryStatus())) withInjury++;
            if (usable(current.practiceParticipation())) withPractice++;
            if (usable(current.depthChartPosition())) withDepthPosition++;
            if (current.depthChartOrder() != null) withDepthOrder++;
            entries.add(new LiveWaiverAvailabilityRepository.Entry(
                marketCandidate.sleeperPlayerId(), marketCandidate.displayName(), marketCandidate.position(),
                marketCandidate.addCount(), marketCandidate.dropCount(), marketCandidate.netAddAttention(),
                marketCandidate.frameMembership(), "SOURCE_PRESENT", current.team(), current.status(),
                current.injuryStatus(), current.injuryStartDate(), current.practiceParticipation(),
                current.depthChartPosition(), current.depthChartOrder()));
        }
        if (present + absent != coverage.marketActiveCandidates()) {
            throw new IllegalStateException("BF-606 internal source-presence partition did not reconcile");
        }

        String snapshotId = UUID.randomUUID().toString();
        Instant observedAt = Instant.now(clock);
        var snapshot = new LiveWaiverAvailabilityRepository.Snapshot(
            snapshotId, normalizedLeagueId, market.snapshotId(), sleeperLeagueId, providerLeague.season(),
            providerLeague.status(), providerLeague.leg(), POLICY_ID, SOURCE, observedAt,
            entries.size(), present, absent, withTeam, withStatus, withInjury, withPractice,
            withDepthPosition, withDepthOrder);
        repository.save(snapshot, entries);
        LiveWaiverAvailabilityRepository.Counts readback = repository.counts(snapshotId);
        if (readback.total() != entries.size()
            || readback.sourcePresent() != present
            || readback.sourceAbsent() != absent) {
            throw new IllegalStateException("BF-606 persisted availability readback did not reconcile");
        }

        return new SyncReport(
            POLICY_ID, SOURCE, snapshotId, market.snapshotId(), normalizedLeagueId, sleeperLeagueId,
            providerLeague.season(), providerLeague.status(), providerLeague.leg(), observedAt,
            entries.size(), present, absent, withTeam, withStatus, withInjury, withPractice,
            withDepthPosition, withDepthOrder, List.copyOf(entries),
            repository.snapshotCountForLeague(normalizedLeagueId));
    }

    private MarketHeader marketHeader(String marketSnapshotId) throws SQLException {
        try (Connection connection = database.openConnection();
             var statement = connection.prepareStatement("""
                 SELECT id, league_id, sleeper_league_id, season, provider_status, provider_leg
                 FROM live_waiver_market_attention_snapshots WHERE id = ?
                 """)) {
            statement.setString(1, marketSnapshotId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("BF-606 BLOCKED: referenced BF-603 snapshot is missing");
                Object leg = rs.getObject("provider_leg");
                MarketHeader result = new MarketHeader(
                    rs.getString("id"), rs.getString("league_id"), rs.getString("sleeper_league_id"),
                    rs.getInt("season"), rs.getString("provider_status"), leg == null ? null : rs.getInt("provider_leg"));
                if (rs.next()) throw new IllegalStateException("BF-606 BLOCKED: duplicate BF-603 snapshot id");
                return result;
            }
        }
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
            for (JsonNode node : players) {
                String id = text(node);
                if (!usable(id) || "0".equals(id.trim())) continue;
                String normalized = id.trim();
                Integer prior = firstRoster.putIfAbsent(normalized, rosterId);
                if (prior != null && prior != rosterId) duplicates.add(normalized);
                ids.add(normalized);
            }
        }
        return new RosterFrame(Set.copyOf(ids), List.copyOf(duplicates));
    }

    private Map<String, CurrentPlayer> parseActivePlayers(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "active-player payload"));
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Sleeper active-player payload must be an object");
        }
        Map<String, CurrentPlayer> result = new LinkedHashMap<>();
        var fields = root.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String id = requireText(field.getKey(), "active player id");
            JsonNode node = field.getValue();
            if (node == null || !node.isObject()) {
                throw new IllegalStateException("Sleeper active player " + id + " payload must be an object");
            }
            CurrentPlayer current = new CurrentPlayer(
                id, text(node.get("team")), text(node.get("status")), text(node.get("injury_status")),
                text(node.get("injury_start_date")), text(node.get("practice_participation")),
                scalarText(node.get("depth_chart_position")), nullableInt(node.get("depth_chart_order"), id));
            if (result.putIfAbsent(id, current) != null) {
                throw new IllegalStateException("Duplicate active player id: " + id);
            }
        }
        return Map.copyOf(result);
    }

    private static Integer nullableInt(JsonNode node, String playerId) {
        if (node == null || node.isNull()) return null;
        if (node.canConvertToInt()) return node.intValue();
        String value = node.asText(null);
        if (value == null || value.isBlank()) return null;
        try { return Integer.valueOf(value.trim()); }
        catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid depth_chart_order for Sleeper player " + playerId + ": " + value, e);
        }
    }

    private static String scalarText(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isValueNode()) throw new IllegalStateException("depth_chart_position must be scalar when present");
        String value = node.asText(null);
        return usable(value) ? value.trim() : null;
    }

    private static int parseSeason(JsonNode node) {
        if (node == null || node.isNull()) return 0;
        if (node.canConvertToInt()) return node.intValue();
        try { return Integer.parseInt(node.asText("0").trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.retainAll(right);
        return result;
    }

    private static String text(JsonNode node) { return node == null || node.isNull() ? null : node.asText(null); }
    private static boolean usable(String value) { return value != null && !value.isBlank(); }
    private static String requireText(String value, String field) {
        if (!usable(value)) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    interface Source {
        String league(String sleeperLeagueId) throws IOException, InterruptedException;
        String rosters(String sleeperLeagueId) throws IOException, InterruptedException;
        String activePlayers() throws IOException, InterruptedException;
    }

    private static final class LiveSource implements Source {
        private final SleeperClient client = new SleeperClient();
        @Override public String league(String id) throws IOException, InterruptedException { return client.getLeague(id); }
        @Override public String rosters(String id) throws IOException, InterruptedException { return client.getLeagueRosters(id); }
        @Override public String activePlayers() throws IOException, InterruptedException { return client.getNflActivePlayers(); }
    }

    private record MarketHeader(
        String snapshotId, String leagueId, String sleeperLeagueId, int season,
        String providerStatus, Integer providerLeg) {}
    private record ProviderLeague(String leagueId, int season, String status, Integer leg) {}
    private record RosterFrame(Set<String> playerIds, List<String> duplicatePlayerIds) {}
    private record CurrentPlayer(
        String sleeperPlayerId, String team, String status, String injuryStatus, String injuryStartDate,
        String practiceParticipation, String depthChartPosition, Integer depthChartOrder) {}

    public record SyncReport(
        String policyId, String source, String availabilitySnapshotId, String marketSnapshotId,
        String leagueId, String sleeperLeagueId, int providerSeason, String providerStatus,
        Integer providerLeg, Instant observedAtUtc, int candidateCount, int sourcePresentCount,
        int sourceAbsentCount, int withTeamCount, int withStatusCount, int withInjuryStatusCount,
        int withPracticeParticipationCount, int withDepthChartPositionCount, int withDepthChartOrderCount,
        List<LiveWaiverAvailabilityRepository.Entry> candidates, int snapshotCountForLeague) {
        public SyncReport {
            candidates = List.copyOf(candidates);
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!SOURCE.equals(source)) throw new IllegalArgumentException("unexpected source");
            if (candidateCount != sourcePresentCount + sourceAbsentCount) {
                throw new IllegalArgumentException("source presence counts must reconcile");
            }
        }
    }
}
