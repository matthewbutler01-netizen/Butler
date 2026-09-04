package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Builds one coordinate-consistent trade context containing the existing positional report plus
 * governed FLEX/SUPERFLEX pressure, lineup requirements, and positional depth needed for
 * post-trade flexible coverage recomputation. This layer does not emit a recommendation or veto.
 */
public final class TradeFlexibleRecommendationContextAnalyzer {
    private final TradeAssetPositionalContextAnalyzer trades;
    private final LeagueLineupRequirementsAnalyzer lineups;
    private final LeaguePositionalDepthAnalyzer depth;

    public TradeFlexibleRecommendationContextAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.trades = new TradeAssetPositionalContextAnalyzer(database);
        this.lineups = new LeagueLineupRequirementsAnalyzer(database);
        this.depth = new LeaguePositionalDepthAnalyzer(database);
    }

    public TradeFlexibleRecommendationContextReport analyze(
        String leagueId,
        int season,
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB) throws SQLException {
        return enrich(trades.analyze(leagueId, season, sideA, sideB));
    }

    public TradeFlexibleRecommendationContextReport analyze(
        String leagueId,
        int season,
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB,
        String source) throws SQLException {
        return enrich(trades.analyze(leagueId, season, sideA, sideB, source));
    }

    public TradeFlexibleRecommendationContextReport analyze(
        String leagueId,
        int season,
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB,
        LocalDate minimumAsOfDate) throws SQLException {
        return enrich(trades.analyze(leagueId, season, sideA, sideB, minimumAsOfDate));
    }

    public TradeFlexibleRecommendationContextReport analyze(
        String leagueId,
        int season,
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB,
        String source,
        LocalDate minimumAsOfDate) throws SQLException {
        return enrich(trades.analyze(leagueId, season, sideA, sideB, source, minimumAsOfDate));
    }

    private TradeFlexibleRecommendationContextReport enrich(
        TradeAssetPositionalContextAnalyzer.TradePositionalContextReport trade) throws SQLException {
        var tradeReport = trade.strategic().trade();
        var lineup = lineups.analyze(tradeReport.leagueId());
        var depthReport = depth.analyze(
            tradeReport.leagueId(), tradeReport.source(), tradeReport.minimumAsOfDate());
        return compose(trade, lineup, depthReport);
    }

    public static TradeFlexibleRecommendationContextReport compose(
        TradeAssetPositionalContextAnalyzer.TradePositionalContextReport trade,
        LeagueLineupRequirementsAnalyzer.LineupRequirementsReport lineup,
        LeaguePositionalDepthAnalyzer.DepthReport depth) {
        Objects.requireNonNull(trade, "trade must not be null");
        Objects.requireNonNull(lineup, "lineup must not be null");
        Objects.requireNonNull(depth, "depth must not be null");

        var tradeReport = trade.strategic().trade();
        if (!tradeReport.leagueId().equals(lineup.leagueId())
            || !tradeReport.leagueId().equals(depth.leagueId())) {
            throw new IllegalStateException("trade, lineup, and depth reports reference different leagues");
        }
        if (!tradeReport.source().equals(depth.source())) {
            throw new IllegalStateException("trade and depth reports use different value sources");
        }
        if (!Objects.equals(tradeReport.minimumAsOfDate(), depth.minimumAsOfDate())) {
            throw new IllegalStateException("trade and depth reports use different freshness boundaries");
        }
        if (trade.flexSlots() != lineup.flexSlots() || trade.superFlexSlots() != lineup.superFlexSlots()) {
            throw new IllegalStateException("trade positional context and lineup flexible exposure differ");
        }

        var coverage = LeagueFlexibleSlotCoverageAnalyzer.compose(lineup, depth);
        var pressure = LeagueFlexibleSlotPressureAnalyzer.classify(coverage);
        var flexible = TradeFlexibleSlotContextAnalyzer.compose(trade, pressure);
        return new TradeFlexibleRecommendationContextReport(trade, lineup, depth, flexible);
    }

    public record TradeFlexibleRecommendationContextReport(
        TradeAssetPositionalContextAnalyzer.TradePositionalContextReport trade,
        LeagueLineupRequirementsAnalyzer.LineupRequirementsReport lineup,
        LeaguePositionalDepthAnalyzer.DepthReport depth,
        TradeFlexibleSlotContextAnalyzer.TradeFlexibleContextReport flexible) {
        public TradeFlexibleRecommendationContextReport {
            Objects.requireNonNull(trade, "trade must not be null");
            Objects.requireNonNull(lineup, "lineup must not be null");
            Objects.requireNonNull(depth, "depth must not be null");
            Objects.requireNonNull(flexible, "flexible must not be null");
            var tradeReport = trade.strategic().trade();
            if (!tradeReport.leagueId().equals(flexible.leagueId())
                || !tradeReport.source().equals(flexible.source())
                || !Objects.equals(tradeReport.minimumAsOfDate(), flexible.minimumAsOfDate())) {
                throw new IllegalArgumentException("flexible recommendation context coordinate mismatch");
            }
        }
    }
}
