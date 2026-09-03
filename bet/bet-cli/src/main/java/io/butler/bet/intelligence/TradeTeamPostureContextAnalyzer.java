package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Adds governed team posture to the existing trade roster-context report without modifying market
 * value, fairness, market edge, supporting evidence, roster context, or production context.
 */
public final class TradeTeamPostureContextAnalyzer {
    private final TradeRosterContextAnalyzer rosterContext;
    private final LeagueTeamPostureAnalyzer posture;

    public TradeTeamPostureContextAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.rosterContext = new TradeRosterContextAnalyzer(database);
        this.posture = new LeagueTeamPostureAnalyzer(database);
    }

    public TradePostureContextReport analyze(String leagueId, int season,
                                             java.util.List<String> sideAPlayerIds,
                                             java.util.List<String> sideBPlayerIds) throws SQLException {
        var trade = rosterContext.analyze(leagueId, season, sideAPlayerIds, sideBPlayerIds);
        var postureReport = posture.analyze(leagueId, season, trade.trade().tradeValue().source());
        return compose(trade, postureReport);
    }

    public TradePostureContextReport analyze(String leagueId, int season,
                                             java.util.List<String> sideAPlayerIds,
                                             java.util.List<String> sideBPlayerIds,
                                             String source) throws SQLException {
        var trade = rosterContext.analyze(leagueId, season, sideAPlayerIds, sideBPlayerIds, source);
        var postureReport = posture.analyze(leagueId, season, trade.trade().tradeValue().source());
        return compose(trade, postureReport);
    }

    public static TradePostureContextReport compose(TradeRosterContextAnalyzer.RosterContextReport trade,
                                                    LeagueTeamPostureAnalyzer.PostureReport postureReport) {
        Objects.requireNonNull(trade, "trade must not be null");
        Objects.requireNonNull(postureReport, "postureReport must not be null");
        var market = trade.trade().tradeValue();
        if (!market.leagueId().equals(postureReport.leagueId())) {
            throw new IllegalStateException("trade and posture reports reference different leagues");
        }
        if (trade.trade().season() != postureReport.season()) {
            throw new IllegalStateException("trade and posture reports reference different seasons");
        }
        if (!market.source().equals(postureReport.rosterValueSource())) {
            throw new IllegalStateException("trade and posture reports use different roster value sources");
        }

        Map<String, LeagueTeamPostureAnalyzer.TeamPosture> byTeam = new HashMap<>();
        for (var team : postureReport.teams()) {
            if (byTeam.put(team.teamId(), team) != null) {
                throw new IllegalStateException("duplicate posture team: " + team.teamId());
            }
        }
        var sideA = attach(trade.sideA(), byTeam);
        var sideB = attach(trade.sideB(), byTeam);
        return new TradePostureContextReport(trade, postureReport.posturePolicyId(), postureReport.available(), sideA, sideB);
    }

    private static TeamTradePosture attach(TradeRosterContextAnalyzer.TeamRosterContext context,
                                           Map<String, LeagueTeamPostureAnalyzer.TeamPosture> posture) {
        var teamPosture = posture.get(context.teamId());
        if (teamPosture == null) throw new IllegalStateException("team posture missing for trade team: " + context.teamId());
        if (!context.teamName().equals(teamPosture.teamName())) {
            throw new IllegalStateException("trade context and posture names differ for team: " + context.teamId());
        }
        return new TeamTradePosture(context, teamPosture);
    }

    public record TeamTradePosture(TradeRosterContextAnalyzer.TeamRosterContext context,
                                   LeagueTeamPostureAnalyzer.TeamPosture posture) {
        public TeamTradePosture {
            Objects.requireNonNull(context, "context must not be null");
            Objects.requireNonNull(posture, "posture must not be null");
            if (!context.teamId().equals(posture.teamId())) {
                throw new IllegalArgumentException("trade context and posture must reference the same team");
            }
        }
    }

    public record TradePostureContextReport(TradeRosterContextAnalyzer.RosterContextReport trade,
                                            String posturePolicyId,
                                            boolean postureAvailable,
                                            TeamTradePosture sideA,
                                            TeamTradePosture sideB) {
        public TradePostureContextReport {
            Objects.requireNonNull(trade, "trade must not be null");
            Objects.requireNonNull(posturePolicyId, "posturePolicyId must not be null");
            Objects.requireNonNull(sideA, "sideA must not be null");
            Objects.requireNonNull(sideB, "sideB must not be null");
            if (sideA.context().teamId().equals(sideB.context().teamId())) {
                throw new IllegalArgumentException("trade posture sides must reference different teams");
            }
        }
    }
}
