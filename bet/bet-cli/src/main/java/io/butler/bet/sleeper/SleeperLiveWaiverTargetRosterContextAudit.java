package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueLineupConfigurationRepository;
import io.butler.bet.data.TeamRepository;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** BF-610 read-only exact live target-roster context for governed waiver review. */
public final class SleeperLiveWaiverTargetRosterContextAudit {
    public static final String POLICY_ID =
        "sleeper-live-waiver-target-roster-context-v1-bf609-bf603-bf602-roster-stable-exact-owner-read-only";
    public static final int TARGET_SEASON = 2026;

    private final Database database;
    private final ReadinessSource readinessSource;
    private final FrameSource frameSource;
    private final Source source;
    private final ObjectMapper mapper = new ObjectMapper();

    public SleeperLiveWaiverTargetRosterContextAudit(Database database) {
        this(
            database,
            leagueId -> readinessFrame(new SleeperLiveWaiverPregameEvidenceReadinessAudit(database).audit(leagueId)),
            new DatabaseFrameSource(database),
            new LiveSource());
    }

    SleeperLiveWaiverTargetRosterContextAudit(
        Database database,
        ReadinessSource readinessSource,
        FrameSource frameSource,
        Source source) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.readinessSource = Objects.requireNonNull(readinessSource, "readinessSource must not be null");
        this.frameSource = Objects.requireNonNull(frameSource, "frameSource must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
    }

    public AuditReport audit(String leagueId, String sleeperOwnerId)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");

        ReadinessFrame readiness = readinessSource.audit(normalizedLeagueId);
        if (readiness.candidateCount() <= 0 || readiness.reviewableCandidateCount() <= 0) {
            throw new IllegalStateException("BF-610 BLOCKED: BF-609 has no evidence-reviewable waiver candidates");
        }

        MarketRosterFrame frame = frameSource.load(normalizedLeagueId, readiness.marketSnapshotId());
        if (!readiness.marketSnapshotId().equals(frame.marketSnapshotId())) {
            throw new IllegalStateException("BF-610 BLOCKED: BF-609 and BF-603/BF-602 market lineage disagree");
        }
        if (frame.season() != TARGET_SEASON || !"in_season".equals(frame.providerStatus())) {
            throw new IllegalStateException("BF-610 BLOCKED: referenced BF-603 frame is not in-season 2026");
        }
        if (frame.rosteredPlayerIds().isEmpty()) {
            throw new IllegalStateException("BF-610 BLOCKED: referenced BF-602 rostered identity frame is empty");
        }

        ProviderLeague providerLeague = parseLeague(source.league(frame.sleeperLeagueId()));
        if (!frame.sleeperLeagueId().equals(providerLeague.leagueId())) {
            throw new IllegalStateException("BF-610 BLOCKED: linked Sleeper league id differs from provider league payload");
        }
        if (providerLeague.season() != TARGET_SEASON || !"in_season".equals(providerLeague.status())) {
            throw new IllegalStateException("BF-610 BLOCKED: current provider league is not in-season 2026");
        }

        List<String> persistedLineupSlots = new LeagueLineupConfigurationRepository(database)
            .findByLeagueId(normalizedLeagueId);
        if (persistedLineupSlots.isEmpty()) {
            throw new IllegalStateException("BF-610 BLOCKED: persisted current league lineup slots are missing");
        }
        if (!persistedLineupSlots.equals(providerLeague.rosterPositions())) {
            throw new IllegalStateException("BF-610 BLOCKED: live provider roster_positions drifted from persisted current league configuration");
        }

        List<ProviderRoster> providerRosters = parseRosters(source.rosters(frame.sleeperLeagueId()));
        if (providerRosters.isEmpty()) {
            throw new IllegalStateException("BF-610 BLOCKED: provider returned no current rosters");
        }
        if (providerLeague.totalRosters() > 0 && providerLeague.totalRosters() != providerRosters.size()) {
            throw new IllegalStateException("BF-610 BLOCKED: provider declared roster count does not match returned rosters");
        }

        Set<String> currentRosteredIds = exactCurrentRosteredIds(providerRosters);
        if (!currentRosteredIds.equals(frame.rosteredPlayerIds())) {
            Set<String> added = difference(currentRosteredIds, frame.rosteredPlayerIds());
            Set<String> removed = difference(frame.rosteredPlayerIds(), currentRosteredIds);
            throw new IllegalStateException(
                "BF-610 BLOCKED: current roster membership drifted from BF-603/BF-602 frame; added="
                    + added + " removed=" + removed
                    + "; refresh BF-602/BF-603 and downstream live evidence before target-roster review");
        }

        Map<String, ProviderUser> usersById = parseUsers(source.users(frame.sleeperLeagueId()));
        ProviderUser owner = usersById.get(normalizedOwnerId);
        if (owner == null) {
            throw new IllegalStateException("BF-610 BLOCKED: exact Sleeper owner id is absent from current provider users: "
                + normalizedOwnerId);
        }

        List<ProviderRoster> ownerRosters = providerRosters.stream()
            .filter(roster -> normalizedOwnerId.equals(roster.ownerId()))
            .toList();
        if (ownerRosters.size() != 1) {
            throw new IllegalStateException("BF-610 BLOCKED: exact Sleeper owner id resolves to "
                + ownerRosters.size() + " current rosters instead of exactly one");
        }
        ProviderRoster target = ownerRosters.get(0);
        if (!target.startersFieldPresent()) {
            throw new IllegalStateException("BF-610 BLOCKED: target roster has no provider starters surface");
        }

        String rosterExternalId = Integer.toString(target.rosterId());
        var butlerTeam = new TeamRepository(database).findByExternalId(normalizedLeagueId, rosterExternalId)
            .orElseThrow(() -> new IllegalStateException(
                "BF-610 BLOCKED: target provider roster has no exact persisted Butler team: " + rosterExternalId));

        List<String> startingSlots = startingSlots(persistedLineupSlots);
        if (target.starterIds().size() != startingSlots.size()) {
            throw new IllegalStateException("BF-610 BLOCKED: target starter identity count "
                + target.starterIds().size() + " does not match live starting-slot count " + startingSlots.size());
        }

        TargetPartition partition = targetPartition(target, startingSlots);
        List<TargetPlayer> targetPlayers = new ArrayList<>();
        int mapped = 0;
        int unmapped = 0;
        for (TargetSlot slot : partition.orderedSlots()) {
            CanonicalPlayer canonical = exactCanonicalPlayer(slot.sleeperPlayerId());
            if (canonical == null) unmapped++;
            else mapped++;
            targetPlayers.add(new TargetPlayer(
                slot.sleeperPlayerId(),
                slot.rosterSlot(),
                slot.starterOrdinal(),
                slot.lineupSlot(),
                canonical == null ? null : canonical.butlerPlayerId(),
                canonical == null ? null : canonical.displayName(),
                canonical == null ? null : canonical.position(),
                canonical == null ? null : canonical.nflTeam(),
                canonical == null ? "UNMAPPED_CANONICAL" : "EXACT_CANONICAL"));
        }

        if (targetPlayers.size() != target.playerIds().size()) {
            throw new IllegalStateException("BF-610 BLOCKED: target roster slot partition does not reconcile");
        }

        return new AuditReport(
            POLICY_ID,
            normalizedLeagueId,
            readiness.marketSnapshotId(),
            frame.waiverSnapshotId(),
            frame.sleeperLeagueId(),
            providerLeague.season(),
            providerLeague.status(),
            providerLeague.leg(),
            normalizedOwnerId,
            owner.displayName(),
            owner.teamName(),
            target.rosterId(),
            butlerTeam.getId(),
            butlerTeam.getName(),
            List.copyOf(persistedLineupSlots),
            List.copyOf(startingSlots),
            readiness.candidateCount(),
            readiness.reviewableCandidateCount(),
            target.playerIds().size(),
            partition.starterCount(),
            partition.benchCount(),
            partition.reserveCount(),
            partition.taxiCount(),
            mapped,
            unmapped,
            List.copyOf(targetPlayers));
    }

    private CanonicalPlayer exactCanonicalPlayer(String sleeperPlayerId) throws SQLException {
        try (Connection connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "SELECT id, display_name, position, nfl_team FROM players WHERE external_id = ? ORDER BY id")) {
            statement.setString(1, sleeperPlayerId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                CanonicalPlayer result = new CanonicalPlayer(
                    rs.getString("id"), rs.getString("display_name"),
                    rs.getString("position"), rs.getString("nfl_team"));
                if (rs.next()) {
                    throw new IllegalStateException("BF-610 BLOCKED: duplicate exact Butler player mapping for Sleeper id "
                        + sleeperPlayerId);
                }
                return result;
            }
        }
    }

    private ProviderLeague parseLeague(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "league payload"));
        if (root == null || !root.isObject()) throw new IllegalStateException("BF-610 BLOCKED: Sleeper league payload must be an object");
        String leagueId = requireText(text(root.get("league_id")), "provider league_id");
        int season = parseSeason(root.get("season"));
        String status = text(root.get("status"));
        JsonNode settings = root.path("settings");
        Integer leg = settings.has("leg") && settings.get("leg").canConvertToInt()
            ? settings.get("leg").intValue() : null;
        int totalRosters = root.path("total_rosters").asInt(0);
        return new ProviderLeague(
            leagueId, season, status, leg, totalRosters,
            stringArray(root.get("roster_positions"), "roster_positions", false));
    }

    private List<ProviderRoster> parseRosters(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "rosters payload"));
        if (root == null || !root.isArray()) throw new IllegalStateException("BF-610 BLOCKED: Sleeper rosters payload must be an array");
        List<ProviderRoster> result = new ArrayList<>();
        Set<Integer> rosterIds = new LinkedHashSet<>();
        for (JsonNode node : root) {
            int rosterId = node.path("roster_id").asInt(0);
            if (rosterId <= 0 || !rosterIds.add(rosterId)) {
                throw new IllegalStateException("BF-610 BLOCKED: missing, invalid, or duplicate provider roster_id");
            }
            List<String> players = stringArray(node.get("players"), "players", true);
            List<String> starters = stringArray(node.get("starters"), "starters", true);
            List<String> reserve = stringArray(node.get("reserve"), "reserve", true);
            List<String> taxi = stringArray(node.get("taxi"), "taxi", true);
            ensureUnique(players, "players", rosterId);
            ensureUnique(starters, "starters", rosterId);
            ensureUnique(reserve, "reserve", rosterId);
            ensureUnique(taxi, "taxi", rosterId);
            ensureSubset(starters, players, "starters", rosterId);
            ensureSubset(reserve, players, "reserve", rosterId);
            ensureSubset(taxi, players, "taxi", rosterId);
            ensureDisjoint(starters, reserve, "starters/reserve", rosterId);
            ensureDisjoint(starters, taxi, "starters/taxi", rosterId);
            ensureDisjoint(reserve, taxi, "reserve/taxi", rosterId);
            result.add(new ProviderRoster(
                rosterId,
                trimToNull(text(node.get("owner_id"))),
                players,
                starters,
                reserve,
                taxi,
                node.has("starters") && node.get("starters").isArray()));
        }
        return result.stream().sorted(Comparator.comparingInt(ProviderRoster::rosterId)).toList();
    }

    private Map<String, ProviderUser> parseUsers(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "users payload"));
        if (root == null || !root.isArray()) throw new IllegalStateException("BF-610 BLOCKED: Sleeper users payload must be an array");
        Map<String, ProviderUser> result = new LinkedHashMap<>();
        for (JsonNode node : root) {
            String id = trimToNull(text(node.get("user_id")));
            if (id == null) continue;
            JsonNode metadata = node.path("metadata");
            ProviderUser user = new ProviderUser(
                id,
                trimToNull(text(node.get("display_name"))),
                trimToNull(text(metadata.get("team_name"))));
            if (result.putIfAbsent(id, user) != null) {
                throw new IllegalStateException("BF-610 BLOCKED: duplicate provider user_id " + id);
            }
        }
        return Map.copyOf(result);
    }

    private static Set<String> exactCurrentRosteredIds(List<ProviderRoster> rosters) {
        Set<String> result = new TreeSet<>();
        for (ProviderRoster roster : rosters) {
            for (String playerId : roster.playerIds()) {
                if (!result.add(playerId)) {
                    throw new IllegalStateException("BF-610 BLOCKED: provider player identity " + playerId
                        + " appears on multiple current rosters");
                }
            }
        }
        return Set.copyOf(result);
    }

    private static TargetPartition targetPartition(ProviderRoster roster, List<String> startingSlots) {
        Set<String> starters = Set.copyOf(roster.starterIds());
        Set<String> reserve = Set.copyOf(roster.reserveIds());
        Set<String> taxi = Set.copyOf(roster.taxiIds());
        List<TargetSlot> ordered = new ArrayList<>();

        for (int i = 0; i < roster.starterIds().size(); i++) {
            ordered.add(new TargetSlot(roster.starterIds().get(i), "STARTER", i, startingSlots.get(i)));
        }
        roster.playerIds().stream()
            .filter(id -> !starters.contains(id) && !reserve.contains(id) && !taxi.contains(id))
            .sorted()
            .forEach(id -> ordered.add(new TargetSlot(id, "BENCH", null, null)));
        roster.reserveIds().stream().sorted()
            .forEach(id -> ordered.add(new TargetSlot(id, "RESERVE", null, null)));
        roster.taxiIds().stream().sorted()
            .forEach(id -> ordered.add(new TargetSlot(id, "TAXI", null, null)));

        int bench = roster.playerIds().size() - starters.size() - reserve.size() - taxi.size();
        if (bench < 0 || ordered.size() != roster.playerIds().size()) {
            throw new IllegalStateException("BF-610 BLOCKED: target roster slot partition does not reconcile");
        }
        return new TargetPartition(List.copyOf(ordered), starters.size(), bench, reserve.size(), taxi.size());
    }

    private static List<String> startingSlots(List<String> lineupSlots) {
        return lineupSlots.stream()
            .filter(slot -> !isNonStarterSlot(slot))
            .toList();
    }

    private static boolean isNonStarterSlot(String slot) {
        return switch (slot) {
            case "BN", "BENCH", "IR", "RESERVE", "TAXI" -> true;
            default -> false;
        };
    }

    private static ReadinessFrame readinessFrame(
        SleeperLiveWaiverPregameEvidenceReadinessAudit.ReadinessReport report) {
        int reviewable = report.summaries()
            .get(SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION).total()
            + report.summaries()
            .get(SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITHOUT_PRIOR_PRODUCTION).total();
        return new ReadinessFrame(report.marketSnapshotId(), report.candidateCount(), reviewable);
    }

    private static List<String> stringArray(JsonNode node, String field, boolean filterZero) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalStateException("BF-610 BLOCKED: Sleeper " + field + " must be an array");
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            String text = trimToNull(value == null || value.isNull() ? null : value.asText(null));
            if (text != null && (!filterZero || !"0".equals(text))) result.add(text);
        }
        return List.copyOf(result);
    }

    private static void ensureUnique(List<String> values, String field, int rosterId) {
        if (new LinkedHashSet<>(values).size() != values.size()) {
            throw new IllegalStateException("BF-610 BLOCKED: duplicate identity in provider " + field
                + " for roster " + rosterId);
        }
    }

    private static void ensureSubset(List<String> child, List<String> parent, String field, int rosterId) {
        if (!new LinkedHashSet<>(parent).containsAll(child)) {
            throw new IllegalStateException("BF-610 BLOCKED: provider " + field
                + " contains identity absent from players for roster " + rosterId);
        }
    }

    private static void ensureDisjoint(List<String> left, List<String> right, String fields, int rosterId) {
        Set<String> overlap = new LinkedHashSet<>(left);
        overlap.retainAll(right);
        if (!overlap.isEmpty()) {
            throw new IllegalStateException("BF-610 BLOCKED: provider " + fields
                + " overlap for roster " + rosterId + ": " + overlap);
        }
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

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String field) {
        String normalized = trimToNull(value);
        if (normalized == null) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    @FunctionalInterface
    interface ReadinessSource {
        ReadinessFrame audit(String leagueId) throws SQLException;
    }

    @FunctionalInterface
    interface FrameSource {
        MarketRosterFrame load(String leagueId, String marketSnapshotId) throws SQLException;
    }

    interface Source {
        String league(String sleeperLeagueId) throws IOException, InterruptedException;
        String rosters(String sleeperLeagueId) throws IOException, InterruptedException;
        String users(String sleeperLeagueId) throws IOException, InterruptedException;
    }

    private static final class LiveSource implements Source {
        private final SleeperClient client = new SleeperClient();
        @Override public String league(String id) throws IOException, InterruptedException { return client.getLeague(id); }
        @Override public String rosters(String id) throws IOException, InterruptedException { return client.getLeagueRosters(id); }
        @Override public String users(String id) throws IOException, InterruptedException { return client.getLeagueUsers(id); }
    }

    private static final class DatabaseFrameSource implements FrameSource {
        private final Database database;
        private DatabaseFrameSource(Database database) { this.database = Objects.requireNonNull(database); }

        @Override
        public MarketRosterFrame load(String leagueId, String marketSnapshotId) throws SQLException {
            try (Connection connection = database.openConnection()) {
                MarketHeader market = marketHeader(connection, leagueId, marketSnapshotId);
                WaiverHeader waiver = waiverHeader(connection, leagueId, market.waiverSnapshotId(), market.sleeperLeagueId());
                if (waiver.rosteredAbsentActiveCount() != 0
                    || waiver.currentRosterIdentityCount() != waiver.activeRosteredIdentityCount()) {
                    throw new IllegalStateException("BF-610 BLOCKED: referenced BF-602 frame does not exactly represent all current roster identities");
                }
                Set<String> rostered = new TreeSet<>();
                try (var statement = connection.prepareStatement(
                    "SELECT sleeper_player_id FROM live_waiver_snapshot_entries WHERE snapshot_id=? AND rostered=1 ORDER BY sleeper_player_id")) {
                    statement.setString(1, market.waiverSnapshotId());
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) rostered.add(rs.getString(1));
                    }
                }
                if (rostered.size() != waiver.activeRosteredIdentityCount()) {
                    throw new IllegalStateException("BF-610 BLOCKED: referenced BF-602 rostered entry count does not reconcile");
                }
                return new MarketRosterFrame(
                    marketSnapshotId, market.waiverSnapshotId(), market.sleeperLeagueId(),
                    market.season(), market.providerStatus(), Set.copyOf(rostered));
            }
        }

        private static MarketHeader marketHeader(Connection connection, String leagueId, String marketSnapshotId)
            throws SQLException {
            try (var statement = connection.prepareStatement("""
                SELECT waiver_snapshot_id, sleeper_league_id, season, provider_status
                FROM live_waiver_market_attention_snapshots
                WHERE id=? AND league_id=?
                """)) {
                statement.setString(1, marketSnapshotId);
                statement.setString(2, leagueId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) throw new IllegalStateException("BF-610 BLOCKED: BF-609 market snapshot is absent from BF-603 persistence");
                    MarketHeader header = new MarketHeader(
                        rs.getString("waiver_snapshot_id"), rs.getString("sleeper_league_id"),
                        rs.getInt("season"), rs.getString("provider_status"));
                    if (rs.next()) throw new IllegalStateException("BF-610 BLOCKED: duplicate BF-603 market snapshot identity");
                    return header;
                }
            }
        }

        private static WaiverHeader waiverHeader(Connection connection, String leagueId, String waiverSnapshotId,
                                                  String sleeperLeagueId) throws SQLException {
            try (var statement = connection.prepareStatement("""
                SELECT current_roster_identity_count, active_rostered_identity_count, rostered_absent_active_count
                FROM live_waiver_snapshots
                WHERE id=? AND league_id=? AND sleeper_league_id=?
                """)) {
                statement.setString(1, waiverSnapshotId);
                statement.setString(2, leagueId);
                statement.setString(3, sleeperLeagueId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) throw new IllegalStateException("BF-610 BLOCKED: BF-603 referenced BF-602 snapshot is missing");
                    WaiverHeader header = new WaiverHeader(
                        rs.getInt("current_roster_identity_count"),
                        rs.getInt("active_rostered_identity_count"),
                        rs.getInt("rostered_absent_active_count"));
                    if (rs.next()) throw new IllegalStateException("BF-610 BLOCKED: duplicate BF-602 waiver snapshot identity");
                    return header;
                }
            }
        }
    }

    record ReadinessFrame(String marketSnapshotId, int candidateCount, int reviewableCandidateCount) {}
    record MarketRosterFrame(String marketSnapshotId, String waiverSnapshotId, String sleeperLeagueId,
                             int season, String providerStatus, Set<String> rosteredPlayerIds) {
        MarketRosterFrame {
            rosteredPlayerIds = Set.copyOf(Objects.requireNonNull(rosteredPlayerIds));
        }
    }
    private record MarketHeader(String waiverSnapshotId, String sleeperLeagueId, int season, String providerStatus) {}
    private record WaiverHeader(int currentRosterIdentityCount, int activeRosteredIdentityCount, int rosteredAbsentActiveCount) {}
    private record ProviderLeague(String leagueId, int season, String status, Integer leg,
                                  int totalRosters, List<String> rosterPositions) {}
    private record ProviderUser(String id, String displayName, String teamName) {}
    private record ProviderRoster(int rosterId, String ownerId, List<String> playerIds,
                                  List<String> starterIds, List<String> reserveIds, List<String> taxiIds,
                                  boolean startersFieldPresent) {}
    private record TargetSlot(String sleeperPlayerId, String rosterSlot, Integer starterOrdinal, String lineupSlot) {}
    private record TargetPartition(List<TargetSlot> orderedSlots, int starterCount, int benchCount, int reserveCount, int taxiCount) {}
    private record CanonicalPlayer(String butlerPlayerId, String displayName, String position, String nflTeam) {}

    public record TargetPlayer(
        String sleeperPlayerId,
        String rosterSlot,
        Integer starterOrdinal,
        String lineupSlot,
        String butlerPlayerId,
        String displayName,
        String position,
        String nflTeam,
        String mappingState) {}

    public record AuditReport(
        String policyId,
        String leagueId,
        String marketSnapshotId,
        String waiverSnapshotId,
        String sleeperLeagueId,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        String sleeperOwnerId,
        String ownerDisplayName,
        String ownerTeamName,
        int rosterId,
        String butlerTeamId,
        String butlerTeamName,
        List<String> lineupSlots,
        List<String> startingSlots,
        int candidateCount,
        int reviewableCandidateCount,
        int targetPlayerCount,
        int starterCount,
        int benchCount,
        int reserveCount,
        int taxiCount,
        int exactMappedTargetPlayers,
        int unmappedTargetPlayers,
        List<TargetPlayer> targetPlayers) {
        public AuditReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-610 policyId");
            lineupSlots = List.copyOf(Objects.requireNonNull(lineupSlots));
            startingSlots = List.copyOf(Objects.requireNonNull(startingSlots));
            targetPlayers = List.copyOf(Objects.requireNonNull(targetPlayers));
            if (targetPlayerCount != targetPlayers.size()) throw new IllegalArgumentException("target player count must reconcile");
            if (starterCount + benchCount + reserveCount + taxiCount != targetPlayerCount) {
                throw new IllegalArgumentException("target roster slot counts must reconcile");
            }
            if (exactMappedTargetPlayers + unmappedTargetPlayers != targetPlayerCount) {
                throw new IllegalArgumentException("target mapping counts must reconcile");
            }
            if (reviewableCandidateCount < 0 || reviewableCandidateCount > candidateCount) {
                throw new IllegalArgumentException("reviewable candidate count must be within candidate frame");
            }
        }
    }
}