package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Adds governed future draft-capital context to the existing trade posture context without
 * modifying market value, fairness, market edge, posture, supporting evidence, or roster context.
 */
public final class TradeFutureCapitalContextAnalyzer {
    private final TradeTeamPostureContextAnalyzer postureContext;
    private final LeagueFutureCapitalTierAnalyzer futureCapital;

    public TradeFutureCapitalContextAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.postureContext = new TradeTeamPostureContextAnalyzer(database);
        this.futureCapital = new LeagueFutureCapitalTierAnalyzer(database);
    }

    public TradeFutureCapitalReport analyze(String leagueId, int season,
                                            java.util.List<String> sideAPlayerIds,
                                            java.util.List<String> sideBPlayerIds) throws SQLException {
        var trade = postureContext.analyze(leagueId, season, sideAPlayerIds, sideBPlayerIds);
        var capital = futureCapital.analyze(leagueId, trade.trade().trade().tradeValue().source());
        return compose(trade, capital);
    }

    public TradeFutureCapitalReport analyze(String leagueId, int season,
                                            java.util.List<String> sideAPlayerIds,
                                            java.util.List<String> sideBPlayerIds,
                                            String source) throws SQLException {
        var trade = postureContext.analyze(leagueId, season, sideAPlayerIds, sideBPlayerIds, source);
        var capital = futureCapital.analyze(leagueId, trade.trade().trade().tradeValue().source());
        return compose(trade, capital);
    }

    public static TradeFutureCapitalReport compose(
        TradeTeamPostureContextAnalyzer.TradePostureContextReport trade,
        LeagueFutureCapitalTierAnalyzer.FutureCapitalReport capital) {
        Objects.requireNonNull(trade, "trade must not be null");
        Objects.requireNonNull(capital, "capital must not be null");

        var market = trade.trade().trade().tradeValue();
        if (!market.leagueId().equals(capital.leagueId())) {
            throw new IllegalStateException("trade and future-capital reports reference different leagues");
        }
        if (!market.source().equals(capital.source())) {
            throw new IllegalStateException("trade and future-capital reports use different value sources");
        }

        Map<String, LeagueFutureCapitalTierAnalyzer.TeamFutureCapital> byTeam = new HashMap<>();
        for (var team : capital.teams()) {
            if (byTeam.put(team.teamId(), team) != null) {
                throw new IllegalStateException("duplicate future-capital team: " + team.teamId());
            }
        }

        var sideA = attach(trade.sideA(), byTeam);
        var sideB = attach(trade.sideB(), byTeam);
        return new TradeFutureCapitalReport(
            trade, capital.policyId(), capital.available(), sideA, sideB);
    }

    private static TeamTradeFutureCapital attach(
        TradeTeamPostureContextAnalyzer.TeamTradePosture posture,
        Map<String, LeagueFutureCapitalTierAnalyzer.TeamFutureCapital> capital) {
        String teamId = posture.context().teamId();
        var teamCapital = capital.get(teamId);
        if (teamCapital == null) {
            throw new IllegalStateException("future-capital context missing for trade team: " + teamId);
        }
        if (!posture.context().teamName().equals(teamCapital.teamName())) {
            throw new IllegalStateException("trade context and future-capital names differ for team: " + teamId);
        }
        return new TeamTradeFutureCapital(posture, teamCapital);
    }

    public record TeamTradeFutureCapital(
        TradeTeamPostureContextAnalyzer.TeamTradePosture posture,
        LeagueFutureCapitalTierAnalyzer.TeamFutureCapital futureCapital) {
        public TeamTradeFutureCapital {
            Objects.requireNonNull(posture, "posture must not be null");
            Objects.requireNonNull(futureCapital, "futureCapital must not be null");
            if (!posture.context().teamId().equals(futureCapital.teamId())) {
                throw new IllegalArgumentException("trade posture and future capital must reference the same team");
            }
        }
    }

    public record TradeFutureCapitalReport(
        TradeTeamPostureContextAnalyzer.TradePostureContextReport trade,
        String futureCapitalPolicyId,
        boolean futureCapitalAvailable,
        TeamTradeFutureCapital sideA,
        TeamTradeFutureCapital sideB) {
        public TradeFutureCapitalReport {
            Objects.requireNonNull(trade, "trade must not be null");
            Objects.requireNonNull(futureCapitalPolicyId, "futureCapitalPolicyId must not be null");
            Objects.requireNonNull(sideA, "sideA must not be null");
            Objects.requireNonNull(sideB, "sideB must not be null");
            if (sideA.posture().context().teamId().equals(sideB.posture().context().teamId())) {
                throw new IllegalArgumentException("trade future-capital sides must reference different teams");
            }
        }
    }
}
