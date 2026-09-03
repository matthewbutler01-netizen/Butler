package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Adds governed positional-pressure context to mixed-asset strategic trade evidence. */
public final class TradeAssetPositionalContextAnalyzer {
    private static final List<String> CORE_POSITIONS = List.of("QB", "RB", "WR", "TE");
    private final TradeAssetStrategicContextAnalyzer strategic;
    private final LeaguePositionalPressureAnalyzer positional;

    public TradeAssetPositionalContextAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.strategic = new TradeAssetStrategicContextAnalyzer(database);
        this.positional = new LeaguePositionalPressureAnalyzer(database);
    }

    public TradePositionalContextReport analyze(String leagueId, int season,
                                                TradeAssetAnalyzer.TradePackage sideA,
                                                TradeAssetAnalyzer.TradePackage sideB) throws SQLException {
        var trade = strategic.analyze(leagueId, season, sideA, sideB);
        return compose(trade, positional.analyze(leagueId, trade.trade().source()));
    }

    public TradePositionalContextReport analyze(String leagueId, int season,
                                                TradeAssetAnalyzer.TradePackage sideA,
                                                TradeAssetAnalyzer.TradePackage sideB,
                                                String source) throws SQLException {
        var trade = strategic.analyze(leagueId, season, sideA, sideB, source);
        return compose(trade, positional.analyze(leagueId, trade.trade().source()));
    }

    public TradePositionalContextReport analyze(String leagueId, int season,
                                                TradeAssetAnalyzer.TradePackage sideA,
                                                TradeAssetAnalyzer.TradePackage sideB,
                                                LocalDate minimumAsOfDate) throws SQLException {
        var trade = strategic.analyze(leagueId, season, sideA, sideB, minimumAsOfDate);
        return compose(trade, positional.analyze(leagueId, trade.trade().source(), minimumAsOfDate));
    }

    public TradePositionalContextReport analyze(String leagueId, int season,
                                                TradeAssetAnalyzer.TradePackage sideA,
                                                TradeAssetAnalyzer.TradePackage sideB,
                                                String source, LocalDate minimumAsOfDate) throws SQLException {
        var trade = strategic.analyze(leagueId, season, sideA, sideB, source, minimumAsOfDate);
        return compose(trade, positional.analyze(leagueId, trade.trade().source(), minimumAsOfDate));
    }

    public static TradePositionalContextReport compose(
        TradeAssetStrategicContextAnalyzer.StrategicTradeReport strategic,
        LeaguePositionalPressureAnalyzer.PositionalPressureReport positional) {
        Objects.requireNonNull(strategic, "strategic must not be null");
        Objects.requireNonNull(positional, "positional must not be null");
        if (!strategic.trade().leagueId().equals(positional.leagueId())) {
            throw new IllegalStateException("trade and positional-pressure reports reference different leagues");
        }
        if (!strategic.trade().source().equals(positional.source())) {
            throw new IllegalStateException("trade and positional-pressure reports use different value sources");
        }
        if (!Objects.equals(strategic.trade().minimumAsOfDate(), positional.minimumAsOfDate())) {
            throw new IllegalStateException("trade and positional-pressure reports use different freshness boundaries");
        }

        var sideA = attach(strategic.sideA().identity(), positional);
        var sideB = attach(strategic.sideB().identity(), positional);
        return new TradePositionalContextReport(strategic, positional.policyId(), positional.lineupPolicyId(),
            positional.flexSlots(), positional.superFlexSlots(), sideA, sideB);
    }

    private static TeamPositionalContext attach(
        TradeAssetStrategicContextAnalyzer.TeamIdentity identity,
        LeaguePositionalPressureAnalyzer.PositionalPressureReport positional) {
        Map<String, LeaguePositionalPressureAnalyzer.TeamPositionPressure> byPosition = new LinkedHashMap<>();
        for (String position : CORE_POSITIONS) {
            var report = positional.positions().get(position);
            if (report == null) throw new IllegalStateException("positional-pressure report missing position: " + position);
            var team = report.teams().stream().filter(candidate -> candidate.teamId().equals(identity.teamId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("positional pressure missing for trade team: " + identity.teamId()));
            if (!team.teamName().equals(identity.teamName())) {
                throw new IllegalStateException("trade and positional-pressure team names differ: " + identity.teamId());
            }
            byPosition.put(position, team);
        }
        return new TeamPositionalContext(identity, Map.copyOf(byPosition));
    }

    public record TeamPositionalContext(
        TradeAssetStrategicContextAnalyzer.TeamIdentity identity,
        Map<String, LeaguePositionalPressureAnalyzer.TeamPositionPressure> positions) {
        public TeamPositionalContext {
            Objects.requireNonNull(identity, "identity must not be null");
            positions = Map.copyOf(Objects.requireNonNull(positions, "positions must not be null"));
            if (!positions.keySet().containsAll(CORE_POSITIONS)) {
                throw new IllegalArgumentException("team positional context must contain QB/RB/WR/TE");
            }
            for (var value : positions.values()) {
                if (!identity.teamId().equals(value.teamId()) || !identity.teamName().equals(value.teamName())) {
                    throw new IllegalArgumentException("team positional context identity mismatch");
                }
            }
        }
    }

    public record TradePositionalContextReport(
        TradeAssetStrategicContextAnalyzer.StrategicTradeReport strategic,
        String positionalPressurePolicyId,
        String lineupPolicyId,
        int flexSlots,
        int superFlexSlots,
        TeamPositionalContext sideA,
        TeamPositionalContext sideB) {
        public TradePositionalContextReport {
            Objects.requireNonNull(strategic, "strategic must not be null");
            Objects.requireNonNull(positionalPressurePolicyId, "positionalPressurePolicyId must not be null");
            Objects.requireNonNull(lineupPolicyId, "lineupPolicyId must not be null");
            if (flexSlots < 0 || superFlexSlots < 0) throw new IllegalArgumentException("flex exposure counts must be non-negative");
            Objects.requireNonNull(sideA, "sideA must not be null");
            Objects.requireNonNull(sideB, "sideB must not be null");
        }
    }
}
