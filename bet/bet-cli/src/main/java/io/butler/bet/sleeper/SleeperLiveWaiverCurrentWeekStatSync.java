package io.butler.bet.sleeper;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LiveWaiverAvailabilityRepository;
import io.butler.bet.data.LiveWaiverCurrentWeekStatRepository;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
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

/** BF-607 immutable current-week raw-stat evidence for the exact latest BF-603/BF-606 market-active frame. */
public final class SleeperLiveWaiverCurrentWeekStatSync {
    public static final String POLICY_ID =
        "sleeper-live-waiver-current-week-stats-v1-latest-bf603-bf606-exact-no-missing-as-zero";
    public static final String OBSERVATION_STATE = "CURRENT_WEEK_FINALITY_UNPROVEN";
    public static final int TARGET_SEASON = 2026;

    private final Database database;
    private final Source source;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper(
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    public SleeperLiveWaiverCurrentWeekStatSync(Database database) {
        this(database, new LiveSource(), Clock.systemUTC());
    }

    SleeperLiveWaiverCurrentWeekStatSync(Database database, Source source, Clock clock) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public SyncReport sync(String leagueId) throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");

        var coverage = new SleeperLiveWaiverProductionCoverageAudit(database).audit(normalizedLeagueId);
        if (coverage.unmappedCanonical() != 0) {
            throw new IllegalStateException("BF-607 BLOCKED: BF-604 has unmapped market-active identities");
        }
        if (coverage.marketActiveCandidates() <= 0) {
            throw new IllegalStateException("BF-607 BLOCKED: latest BF-603 market-active frame is empty");
        }

        LiveWaiverAvailabilityRepository availabilityRepository = new LiveWaiverAvailabilityRepository(database);
        var availability = availabilityRepository.latestForMarketSnapshot(coverage.marketSnapshotId())
            .orElseThrow(() -> new IllegalStateException(
                "BF-607 BLOCKED: no BF-606 availability snapshot exists for latest BF-603 market frame"));
        if (!SleeperLiveWaiverAvailabilitySync.POLICY_ID.equals(availability.policyId())) {
            throw new IllegalStateException("BF-607 BLOCKED: latest availability snapshot has unexpected policy");
        }
        if (!coverage.marketSnapshotId().equals(availability.marketSnapshotId())
            || availability.candidateCount() != coverage.marketActiveCandidates()) {
            throw new IllegalStateException("BF-607 BLOCKED: BF-603/BF-606 candidate lineage does not reconcile");
        }

        var linkedLeague = new LeagueRepository(database).findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + normalizedLeagueId));
        String sleeperLeagueId = requireText(linkedLeague.getExternalId(), "linked Sleeper league id");
        if (!sleeperLeagueId.equals(availability.sleeperLeagueId())) {
            throw new IllegalStateException("BF-607 BLOCKED: BF-606 Sleeper league differs from current linked league");
        }

        ProviderLeague providerLeague = parseLeague(source.league(sleeperLeagueId));
        if (!sleeperLeagueId.equals(providerLeague.leagueId())) {
            throw new IllegalStateException("BF-607 BLOCKED: provider league id differs from linked league");
        }
        if (providerLeague.season() != TARGET_SEASON || !"in_season".equals(providerLeague.status())) {
            throw new IllegalStateException("BF-607 BLOCKED: provider league is not 2026/in_season");
        }

        NflState nflState = parseNflState(source.nflState());
        if (nflState.season() != TARGET_SEASON || !"regular".equals(nflState.seasonType())) {
            throw new IllegalStateException("BF-607 BLOCKED: Sleeper NFL state is not 2026 regular season");
        }
        if (nflState.week() <= 0 || nflState.week() > 25) {
            throw new IllegalStateException("BF-607 BLOCKED: Sleeper NFL state week is invalid: " + nflState.week());
        }
        if (providerLeague.leg() != null && providerLeague.leg() != nflState.week()) {
            throw new IllegalStateException("BF-607 BLOCKED: provider league leg " + providerLeague.leg()
                + " differs from Sleeper NFL state week " + nflState.week());
        }

        List<LiveWaiverAvailabilityRepository.Entry> availabilityEntries =
            availabilityRepository.entries(availability.id());
        if (availabilityEntries.size() != coverage.marketActiveCandidates()) {
            throw new IllegalStateException("BF-607 BLOCKED: BF-606 entry count does not reconcile");
        }
        Set<String> targetIds = new TreeSet<>();
        for (var entry : availabilityEntries) targetIds.add(entry.sleeperPlayerId());
        if (targetIds.size() != availabilityEntries.size()) {
            throw new IllegalStateException("BF-607 BLOCKED: duplicate BF-606 target identities");
        }

        RosterFrame rosterFrame = parseRosterFrame(source.rosters(sleeperLeagueId));
        if (!rosterFrame.duplicatePlayerIds().isEmpty()) {
            throw new IllegalStateException("BF-607 BLOCKED: current provider roster surface contains duplicate identities: "
                + rosterFrame.duplicatePlayerIds());
        }
        Set<String> nowRosteredTargets = intersection(targetIds, rosterFrame.playerIds());
        if (!nowRosteredTargets.isEmpty()) {
            throw new IllegalStateException("BF-607 BLOCKED: market-active candidates are now rostered: "
                + nowRosteredTargets + "; refresh BF-602/BF-603 first");
        }

        String sourceLabel = "stats/nfl/regular/" + TARGET_SEASON + "/" + nflState.week();
        Map<String, StatRow> statRows = parseStatRows(source.weeklyStats(TARGET_SEASON, nflState.week()));

        List<LiveWaiverCurrentWeekStatRepository.Entry> entries = new ArrayList<>();
        int present = 0;
        int absent = 0;
        int withPassAtt = 0;
        int withRushAtt = 0;
        int withRecTgt = 0;
        int withReceptions = 0;

        List<LiveWaiverAvailabilityRepository.Entry> sorted = new ArrayList<>(availabilityEntries);
        sorted.sort(Comparator.comparing(LiveWaiverAvailabilityRepository.Entry::sleeperPlayerId));
        for (var candidate : sorted) {
            StatRow row = statRows.get(candidate.sleeperPlayerId());
            if (row == null) {
                absent++;
                entries.add(entry(candidate, "SOURCE_ABSENT", null, Map.of()));
                continue;
            }
            present++;
            if (row.numeric().containsKey("pass_att")) withPassAtt++;
            if (row.numeric().containsKey("rush_att")) withRushAtt++;
            if (row.numeric().containsKey("rec_tgt")) withRecTgt++;
            if (row.numeric().containsKey("rec")) withReceptions++;
            entries.add(entry(candidate, "SOURCE_PRESENT", row.rawNumericJson(), row.numeric()));
        }
        if (present + absent != availabilityEntries.size()) {
            throw new IllegalStateException("BF-607 internal source-presence partition did not reconcile");
        }

        String snapshotId = UUID.randomUUID().toString();
        Instant observedAt = Instant.now(clock);
        var snapshot = new LiveWaiverCurrentWeekStatRepository.Snapshot(
            snapshotId, normalizedLeagueId, coverage.marketSnapshotId(), availability.id(), sleeperLeagueId,
            providerLeague.season(), providerLeague.status(), providerLeague.leg(),
            nflState.season(), nflState.week(), nflState.seasonType(), POLICY_ID, sourceLabel,
            OBSERVATION_STATE, observedAt, entries.size(), present, absent,
            withPassAtt, withRushAtt, withRecTgt, withReceptions);

        LiveWaiverCurrentWeekStatRepository repository = new LiveWaiverCurrentWeekStatRepository(database);
        repository.save(snapshot, entries);
        var counts = repository.counts(snapshotId);
        if (counts.total() != entries.size() || counts.sourcePresent() != present || counts.sourceAbsent() != absent) {
            throw new IllegalStateException("BF-607 persisted current-week stat readback did not reconcile");
        }

        return new SyncReport(
            POLICY_ID, sourceLabel, OBSERVATION_STATE, snapshotId, coverage.marketSnapshotId(), availability.id(),
            normalizedLeagueId, sleeperLeagueId, providerLeague.season(), providerLeague.status(), providerLeague.leg(),
            nflState.season(), nflState.week(), nflState.seasonType(), observedAt, entries.size(), present, absent,
            withPassAtt, withRushAtt, withRecTgt, withReceptions, List.copyOf(entries),
            repository.snapshotCountForLeague(normalizedLeagueId));
    }

    private LiveWaiverCurrentWeekStatRepository.Entry entry(
        LiveWaiverAvailabilityRepository.Entry candidate,
        String sourceState,
        String rawNumericJson,
        Map<String, BigDecimal> numeric) {
        return new LiveWaiverCurrentWeekStatRepository.Entry(
            candidate.sleeperPlayerId(), candidate.displayName(), candidate.position(),
            candidate.addCount(), candidate.dropCount(), candidate.netAddAttention(), candidate.frameMembership(),
            sourceState, rawNumericJson,
            value(numeric, "pass_att"), value(numeric, "pass_cmp"), value(numeric, "pass_yd"),
            value(numeric, "pass_td"), value(numeric, "pass_int"), value(numeric, "rush_att"),
            value(numeric, "rush_yd"), value(numeric, "rush_td"), value(numeric, "rec_tgt"),
            value(numeric, "rec"), value(numeric, "rec_yd"), value(numeric, "rec_td"),
            value(numeric, "fum_lost"));
    }

    private Map<String, StatRow> parseStatRows(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "weekly stats payload"));
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Sleeper weekly stats payload must be an object keyed by exact identity");
        }
        Map<String, StatRow> result = new LinkedHashMap<>();
        var fields = root.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String playerId = requireText(field.getKey(), "weekly stats player id");
            JsonNode row = field.getValue();
            if (row == null || !row.isObject()) {
                throw new IllegalStateException("Sleeper weekly stat row must be an object for " + playerId);
            }
            Map<String, BigDecimal> numeric = new TreeMap<>();
            var stats = row.fields();
            while (stats.hasNext()) {
                var stat = stats.next();
                if (stat.getValue() != null && stat.getValue().isNumber()) {
                    numeric.put(stat.getKey(), stat.getValue().decimalValue());
                }
            }
            String raw = mapper.writeValueAsString(numeric);
            if (result.putIfAbsent(playerId, new StatRow(Map.copyOf(numeric), raw)) != null) {
                throw new IllegalStateException("Duplicate weekly stats identity: " + playerId);
            }
        }
        return Map.copyOf(result);
    }

    private ProviderLeague parseLeague(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "league payload"));
        if (root == null || !root.isObject()) throw new IllegalStateException("Sleeper league payload must be an object");
        String id = requireText(text(root.get("league_id")), "provider league_id");
        int season = parseInt(root.get("season"));
        String status = requireText(text(root.get("status")), "provider status");
        JsonNode settings = root.path("settings");
        Integer leg = settings.has("leg") && settings.get("leg").canConvertToInt() ? settings.get("leg").intValue() : null;
        return new ProviderLeague(id, season, status, leg);
    }

    private NflState parseNflState(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "NFL state payload"));
        if (root == null || !root.isObject()) throw new IllegalStateException("Sleeper NFL state payload must be an object");
        return new NflState(parseInt(root.get("season")), parseInt(root.get("week")),
            requireText(text(root.get("season_type")), "NFL state season_type"));
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
                if (id == null || id.isBlank() || "0".equals(id.trim())) continue;
                String normalized = id.trim();
                Integer prior = firstRoster.putIfAbsent(normalized, rosterId);
                if (prior != null && prior != rosterId) duplicates.add(normalized);
                ids.add(normalized);
            }
        }
        return new RosterFrame(Set.copyOf(ids), Set.copyOf(duplicates));
    }

    private static Double value(Map<String, BigDecimal> numeric, String key) {
        BigDecimal value = numeric.get(key);
        return value == null ? null : value.doubleValue();
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.retainAll(right);
        return result;
    }

    private static int parseInt(JsonNode node) {
        if (node == null || node.isNull()) return 0;
        if (node.canConvertToInt()) return node.intValue();
        try { return Integer.parseInt(node.asText("0").trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String text(JsonNode node) { return node == null || node.isNull() ? null : node.asText(null); }
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    interface Source {
        String league(String sleeperLeagueId) throws IOException, InterruptedException;
        String rosters(String sleeperLeagueId) throws IOException, InterruptedException;
        String nflState() throws IOException, InterruptedException;
        String weeklyStats(int season, int week) throws IOException, InterruptedException;
    }

    private static final class LiveSource implements Source {
        private final SleeperClient client = new SleeperClient();
        @Override public String league(String id) throws IOException, InterruptedException { return client.getLeague(id); }
        @Override public String rosters(String id) throws IOException, InterruptedException { return client.getLeagueRosters(id); }
        @Override public String nflState() throws IOException, InterruptedException { return client.getNflState(); }
        @Override public String weeklyStats(int season, int week) throws IOException, InterruptedException {
            return client.getNflWeeklyStats(season, week);
        }
    }

    private record ProviderLeague(String leagueId, int season, String status, Integer leg) {}
    private record NflState(int season, int week, String seasonType) {}
    private record RosterFrame(Set<String> playerIds, Set<String> duplicatePlayerIds) {}
    private record StatRow(Map<String, BigDecimal> numeric, String rawNumericJson) {}

    public record SyncReport(
        String policyId, String source, String observationState, String statSnapshotId,
        String marketSnapshotId, String availabilitySnapshotId, String leagueId, String sleeperLeagueId,
        int providerSeason, String providerStatus, Integer providerLeg,
        int stateSeason, int stateWeek, String stateSeasonType, Instant observedAtUtc,
        int candidateCount, int sourcePresentCount, int sourceAbsentCount,
        int withPassAttCount, int withRushAttCount, int withRecTgtCount, int withReceptionsCount,
        List<LiveWaiverCurrentWeekStatRepository.Entry> candidates, int snapshotCountForLeague) {
        public SyncReport {
            candidates = List.copyOf(candidates);
        }
    }
}
