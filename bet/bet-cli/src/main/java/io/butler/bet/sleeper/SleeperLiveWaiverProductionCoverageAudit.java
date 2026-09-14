package io.butler.bet.sleeper;

import io.butler.bet.data.Database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** BF-604 read-only audit of governed prior-season production coverage for BF-603 market-active candidates. */
public final class SleeperLiveWaiverProductionCoverageAudit {
    public static final String POLICY_ID =
        "sleeper-live-waiver-production-coverage-v1-latest-bf603-market-active-exact-identity-read-only";
    public static final int PRODUCTION_SEASON = 2025;
    static final int SQL_BATCH_SIZE = 400;

    private final Database database;

    public SleeperLiveWaiverProductionCoverageAudit(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public AuditReport audit(String leagueId) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        try (Connection connection = database.openConnection()) {
            SnapshotFrame frame = latestMarketSnapshot(connection, normalizedLeagueId);
            List<MarketCandidate> marketActive = marketActiveCandidates(connection, frame.snapshotId());
            int expectedMarketActive = frame.addOnlyCount() + frame.dropOnlyCount() + frame.bothCount();
            if (marketActive.size() != expectedMarketActive) {
                throw new IllegalStateException("BF-604 BLOCKED: BF-603 market-active candidate count does not reconcile; expected="
                    + expectedMarketActive + " actual=" + marketActive.size());
            }

            Map<String, String> butlerPlayerIds = exactButlerPlayerIds(connection, marketActive);
            Map<String, List<ProductionObservation>> productionByPlayerId = latestProductionPerPlayerAndSource(
                connection,
                butlerPlayerIds.values().stream().distinct().toList(),
                PRODUCTION_SEASON);

            List<CandidateCoverage> candidates = new ArrayList<>();
            int unmapped = 0;
            int mappedNoProduction = 0;
            int mappedWithProduction = 0;
            Map<String, MutableSourceCoverage> sourceAccumulator = new LinkedHashMap<>();

            for (MarketCandidate candidate : marketActive) {
                String butlerPlayerId = butlerPlayerIds.get(candidate.sleeperPlayerId());
                List<ProductionObservation> observations = butlerPlayerId == null
                    ? List.of()
                    : productionByPlayerId.getOrDefault(butlerPlayerId, List.of());

                CoverageState state;
                if (butlerPlayerId == null) {
                    state = CoverageState.UNMAPPED_CANONICAL;
                    unmapped++;
                } else if (observations.isEmpty()) {
                    state = CoverageState.MAPPED_NO_2025_PRODUCTION;
                    mappedNoProduction++;
                } else {
                    state = CoverageState.MAPPED_WITH_2025_PRODUCTION;
                    mappedWithProduction++;
                    for (ProductionObservation observation : observations) {
                        sourceAccumulator
                            .computeIfAbsent(observation.source(), ignored -> new MutableSourceCoverage())
                            .observe(observation.asOfDate());
                    }
                }
                candidates.add(new CandidateCoverage(candidate, butlerPlayerId, state, observations));
            }

            if (unmapped + mappedNoProduction + mappedWithProduction != marketActive.size()) {
                throw new IllegalStateException("BF-604 internal coverage partition did not reconcile");
            }

            Map<String, SourceCoverage> sourceCoverage = new LinkedHashMap<>();
            sourceAccumulator.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                sourceCoverage.put(entry.getKey(), entry.getValue().freeze()));

            return new AuditReport(
                POLICY_ID,
                normalizedLeagueId,
                frame.snapshotId(),
                frame.observedAtUtc(),
                PRODUCTION_SEASON,
                marketActive.size(),
                unmapped,
                mappedNoProduction,
                mappedWithProduction,
                Collections.unmodifiableMap(new LinkedHashMap<>(sourceCoverage)),
                List.copyOf(candidates));
        }
    }

    private static SnapshotFrame latestMarketSnapshot(Connection connection, String leagueId) throws SQLException {
        requireTable(connection, "live_waiver_market_attention_snapshots");
        try (var statement = connection.prepareStatement("""
            SELECT id, observed_at_utc, add_only_count, drop_only_count, both_count
            FROM live_waiver_market_attention_snapshots
            WHERE league_id = ?
            ORDER BY observed_at_utc DESC, id DESC
            LIMIT 1
            """)) {
            statement.setString(1, leagueId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("BF-604 BLOCKED: no BF-603 market-attention snapshot exists for league " + leagueId);
                }
                return new SnapshotFrame(
                    rs.getString("id"),
                    rs.getString("observed_at_utc"),
                    rs.getInt("add_only_count"),
                    rs.getInt("drop_only_count"),
                    rs.getInt("both_count"));
            }
        }
    }

    private static List<MarketCandidate> marketActiveCandidates(Connection connection, String marketSnapshotId) throws SQLException {
        requireTable(connection, "live_waiver_market_attention_entries");
        List<MarketCandidate> result = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
            SELECT sleeper_player_id, display_name, position, nfl_team, provider_status,
                   add_count, drop_count, net_add_attention, frame_membership
            FROM live_waiver_market_attention_entries
            WHERE market_snapshot_id = ? AND frame_membership <> 'NEITHER'
            ORDER BY add_count DESC, drop_count DESC, sleeper_player_id ASC
            """)) {
            statement.setString(1, marketSnapshotId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new MarketCandidate(
                        rs.getString("sleeper_player_id"),
                        rs.getString("display_name"),
                        rs.getString("position"),
                        rs.getString("nfl_team"),
                        rs.getString("provider_status"),
                        rs.getInt("add_count"),
                        rs.getInt("drop_count"),
                        rs.getInt("net_add_attention"),
                        rs.getString("frame_membership")));
                }
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, String> exactButlerPlayerIds(
        Connection connection,
        List<MarketCandidate> candidates) throws SQLException {
        if (candidates.isEmpty()) return Map.of();

        List<String> sleeperPlayerIds = candidates.stream()
            .map(MarketCandidate::sleeperPlayerId)
            .distinct()
            .toList();
        Map<String, String> result = new LinkedHashMap<>();

        for (int offset = 0; offset < sleeperPlayerIds.size(); offset += SQL_BATCH_SIZE) {
            List<String> batch = sleeperPlayerIds.subList(
                offset,
                Math.min(offset + SQL_BATCH_SIZE, sleeperPlayerIds.size()));
            String sql = "SELECT external_id, id FROM players WHERE external_id IN ("
                + placeholders(batch.size())
                + ") ORDER BY external_id ASC, id ASC";
            try (var statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < batch.size(); index++) {
                    statement.setString(index + 1, batch.get(index));
                }
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        String sleeperPlayerId = rs.getString("external_id");
                        String butlerPlayerId = rs.getString("id");
                        if (result.putIfAbsent(sleeperPlayerId, butlerPlayerId) != null) {
                            throw new IllegalStateException(
                                "BF-604 BLOCKED: duplicate exact Butler external player identity " + sleeperPlayerId);
                        }
                    }
                }
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, List<ProductionObservation>> latestProductionPerPlayerAndSource(
        Connection connection,
        List<String> butlerPlayerIds,
        int season) throws SQLException {
        if (butlerPlayerIds.isEmpty()) return Map.of();
        requireTable(connection, "player_season_production");

        List<String> playerIds = butlerPlayerIds.stream().distinct().toList();
        Map<String, List<ProductionObservation>> mutable = new LinkedHashMap<>();

        for (int offset = 0; offset < playerIds.size(); offset += SQL_BATCH_SIZE) {
            List<String> batch = playerIds.subList(
                offset,
                Math.min(offset + SQL_BATCH_SIZE, playerIds.size()));
            String sql = """
                WITH latest AS (
                    SELECT player_id, source, MAX(as_of_date) AS latest_date
                    FROM player_season_production
                    WHERE season = ? AND player_id IN (%s)
                    GROUP BY player_id, source
                )
                SELECT p.player_id, p.source, p.as_of_date, p.games_played,
                       p.passing_yards, p.passing_touchdowns,
                       p.rushing_yards, p.rushing_touchdowns,
                       p.receptions, p.receiving_yards, p.receiving_touchdowns
                FROM player_season_production p
                JOIN latest
                  ON latest.player_id = p.player_id
                 AND latest.source = p.source
                 AND latest.latest_date = p.as_of_date
                WHERE p.season = ?
                ORDER BY p.player_id ASC, p.source ASC, p.as_of_date DESC, p.id ASC
                """.formatted(placeholders(batch.size()));
            try (var statement = connection.prepareStatement(sql)) {
                int parameter = 1;
                statement.setInt(parameter++, season);
                for (String playerId : batch) statement.setString(parameter++, playerId);
                statement.setInt(parameter, season);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        mutable.computeIfAbsent(rs.getString("player_id"), ignored -> new ArrayList<>())
                            .add(toObservation(rs));
                    }
                }
            }
        }

        Map<String, List<ProductionObservation>> result = new LinkedHashMap<>();
        mutable.forEach((playerId, observations) -> result.put(playerId, List.copyOf(observations)));
        return Map.copyOf(result);
    }

    private static ProductionObservation toObservation(ResultSet rs) throws SQLException {
        return new ProductionObservation(
            rs.getString("source"),
            LocalDate.parse(rs.getString("as_of_date")),
            rs.getInt("games_played"),
            rs.getInt("passing_yards"),
            rs.getInt("passing_touchdowns"),
            rs.getInt("rushing_yards"),
            rs.getInt("rushing_touchdowns"),
            rs.getInt("receptions"),
            rs.getInt("receiving_yards"),
            rs.getInt("receiving_touchdowns"));
    }

    private static String placeholders(int count) {
        if (count <= 0 || count > SQL_BATCH_SIZE) {
            throw new IllegalArgumentException("BF-746 SQL batch size out of range: " + count);
        }
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static void requireTable(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("BF-604 BLOCKED: required evidence table missing: " + table);
                }
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum CoverageState {
        UNMAPPED_CANONICAL,
        MAPPED_NO_2025_PRODUCTION,
        MAPPED_WITH_2025_PRODUCTION
    }

    public record AuditReport(
        String policyId,
        String leagueId,
        String marketSnapshotId,
        String marketObservedAtUtc,
        int productionSeason,
        int marketActiveCandidates,
        int unmappedCanonical,
        int mappedNoProduction,
        int mappedWithProduction,
        Map<String, SourceCoverage> sourceCoverage,
        List<CandidateCoverage> candidates) {}

    public record CandidateCoverage(
        MarketCandidate market,
        String butlerPlayerId,
        CoverageState state,
        List<ProductionObservation> production) {
        public CandidateCoverage {
            production = List.copyOf(production);
        }
    }

    public record MarketCandidate(
        String sleeperPlayerId,
        String displayName,
        String position,
        String nflTeam,
        String providerStatus,
        int addCount,
        int dropCount,
        int netAddAttention,
        String frameMembership) {}

    public record ProductionObservation(
        String source,
        LocalDate asOfDate,
        int gamesPlayed,
        int passingYards,
        int passingTouchdowns,
        int rushingYards,
        int rushingTouchdowns,
        int receptions,
        int receivingYards,
        int receivingTouchdowns) {}

    public record SourceCoverage(int candidateObservations, LocalDate earliestAsOf, LocalDate latestAsOf) {}

    private record SnapshotFrame(String snapshotId, String observedAtUtc, int addOnlyCount, int dropOnlyCount, int bothCount) {}

    private static final class MutableSourceCoverage {
        private int count;
        private LocalDate earliest;
        private LocalDate latest;

        private void observe(LocalDate date) {
            count++;
            if (earliest == null || date.isBefore(earliest)) earliest = date;
            if (latest == null || date.isAfter(latest)) latest = date;
        }

        private SourceCoverage freeze() {
            return new SourceCoverage(count, earliest, latest);
        }
    }
}
