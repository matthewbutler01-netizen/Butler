package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Composes mixed player/draft-pick trade market evidence with governed team posture and future
 * capital. Player-only age evidence remains a separate optional dimension.
 */
public final class TradeAssetStrategicContextAnalyzer {
    private final TradeAssetAnalyzer trades;
    private final LeagueTeamPostureAnalyzer posture;
    private final LeagueFutureCapitalTierAnalyzer futureCapital;

    public TradeAssetStrategicContextAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.trades = new TradeAssetAnalyzer(database);
        this.posture = new LeagueTeamPostureAnalyzer(database);
        this.futureCapital = new LeagueFutureCapitalTierAnalyzer(database);
    }

    public StrategicTradeReport analyze(String leagueId, int season,
                                        TradeAssetAnalyzer.TradePackage sideA,
                                        TradeAssetAnalyzer.TradePackage sideB) throws SQLException {
        var trade = trades.analyze(leagueId, sideA, sideB);
        return compose(trade,
            posture.analyze(leagueId, season, trade.source()),
            futureCapital.analyze(leagueId, trade.source()));
    }

    public StrategicTradeReport analyze(String leagueId, int season,
                                        TradeAssetAnalyzer.TradePackage sideA,
                                        TradeAssetAnalyzer.TradePackage sideB,
                                        String source) throws SQLException {
        var trade = trades.analyze(leagueId, sideA, sideB, source);
        return compose(trade,
            posture.analyze(leagueId, season, trade.source()),
            futureCapital.analyze(leagueId, trade.source()));
    }

    public StrategicTradeReport analyze(String leagueId, int season,
                                        TradeAssetAnalyzer.TradePackage sideA,
                                        TradeAssetAnalyzer.TradePackage sideB,
                                        LocalDate minimumAsOfDate) throws SQLException {
        Objects.requireNonNull(minimumAsOfDate, "minimumAsOfDate must not be null");
        var trade = trades.analyze(leagueId, sideA, sideB, minimumAsOfDate);
        return compose(trade,
            posture.analyze(leagueId, season, trade.source(), minimumAsOfDate),
            futureCapital.analyze(leagueId, trade.source(), minimumAsOfDate));
    }

    public StrategicTradeReport analyze(String leagueId, int season,
                                        TradeAssetAnalyzer.TradePackage sideA,
                                        TradeAssetAnalyzer.TradePackage sideB,
                                        String source,
                                        LocalDate minimumAsOfDate) throws SQLException {
        Objects.requireNonNull(minimumAsOfDate, "minimumAsOfDate must not be null");
        var trade = trades.analyze(leagueId, sideA, sideB, source, minimumAsOfDate);
        return compose(trade,
            posture.analyze(leagueId, season, trade.source(), minimumAsOfDate),
            futureCapital.analyze(leagueId, trade.source(), minimumAsOfDate));
    }

    public static StrategicTradeReport compose(
        TradeAssetAnalyzer.TradeReport trade,
        LeagueTeamPostureAnalyzer.PostureReport posture,
        LeagueFutureCapitalTierAnalyzer.FutureCapitalReport futureCapital) {
        Objects.requireNonNull(trade, "trade must not be null");
        Objects.requireNonNull(posture, "posture must not be null");
        Objects.requireNonNull(futureCapital, "futureCapital must not be null");
        if (!trade.leagueId().equals(posture.leagueId()) || !trade.leagueId().equals(futureCapital.leagueId())) {
            throw new IllegalStateException("trade strategic-context reports reference different leagues");
        }
        if (!trade.source().equals(posture.rosterValueSource()) || !trade.source().equals(futureCapital.source())) {
            throw new IllegalStateException("trade strategic-context reports use different value sources");
        }

        TeamIdentity sideATeam = resolveSideTeam(trade.sideA(), "side A");
        TeamIdentity sideBTeam = resolveSideTeam(trade.sideB(), "side B");
        if (sideATeam.teamId().equals(sideBTeam.teamId())) {
            throw new IllegalArgumentException("trade sides must resolve to different fantasy teams");
        }

        Map<String, LeagueTeamPostureAnalyzer.TeamPosture> postureByTeam = new HashMap<>();
        for (var team : posture.teams()) {
            if (postureByTeam.put(team.teamId(), team) != null) {
                throw new IllegalStateException("duplicate posture team: " + team.teamId());
            }
        }
        Map<String, LeagueFutureCapitalTierAnalyzer.TeamFutureCapital> capitalByTeam = new HashMap<>();
        for (var team : futureCapital.teams()) {
            if (capitalByTeam.put(team.teamId(), team) != null) {
                throw new IllegalStateException("duplicate future-capital team: " + team.teamId());
            }
        }

        var sideAContext = attach(sideATeam, postureByTeam, capitalByTeam);
        var sideBContext = attach(sideBTeam, postureByTeam, capitalByTeam);

        Double signedDifference = trade.valueDifference();
        Double gapPercent = signedDifference == null ? null
            : TradeFairnessMeasurementPolicy.symmetricGapPercent(trade.sideA().totalValue(), trade.sideB().totalValue());
        TradeFairnessPolicy.Classification fairness = TradeFairnessPolicy.classify(gapPercent);
        TradeMarketEdgePolicy.Direction edge = TradeMarketEdgePolicy.classify(fairness, signedDifference);

        return new StrategicTradeReport(
            trade,
            TradeFairnessMeasurementPolicy.POLICY_ID,
            TradeFairnessPolicy.POLICY_ID,
            gapPercent,
            fairness,
            TradeMarketEdgePolicy.POLICY_ID,
            edge,
            posture.posturePolicyId(),
            posture.available(),
            futureCapital.policyId(),
            futureCapital.available(),
            sideAContext,
            sideBContext);
    }

    static TeamIdentity resolveSideTeam(TradeAssetAnalyzer.TradeSide side, String label) {
        Objects.requireNonNull(side, "side must not be null");
        String teamId = null;
        String teamName = null;
        for (var player : side.players()) {
            TeamIdentity identity = new TeamIdentity(player.teamId(), player.teamName());
            if (teamId == null) {
                teamId = identity.teamId();
                teamName = identity.teamName();
            } else if (!teamId.equals(identity.teamId()) || !teamName.equals(identity.teamName())) {
                throw new IllegalArgumentException(label + " spans multiple fantasy teams");
            }
        }
        for (var pick : side.draftPicks()) {
            TeamIdentity identity = new TeamIdentity(pick.ownerTeamId(), pick.ownerTeamName());
            if (teamId == null) {
                teamId = identity.teamId();
                teamName = identity.teamName();
            } else if (!teamId.equals(identity.teamId()) || !teamName.equals(identity.teamName())) {
                throw new IllegalArgumentException(label + " spans multiple fantasy teams");
            }
        }
        if (teamId == null) throw new IllegalArgumentException(label + " must contain at least one trade asset");
        return new TeamIdentity(teamId, teamName);
    }

    private static TeamStrategicContext attach(
        TeamIdentity identity,
        Map<String, LeagueTeamPostureAnalyzer.TeamPosture> posture,
        Map<String, LeagueFutureCapitalTierAnalyzer.TeamFutureCapital> futureCapital) {
        var teamPosture = posture.get(identity.teamId());
        if (teamPosture == null) throw new IllegalStateException("team posture missing for trade team: " + identity.teamId());
        var teamCapital = futureCapital.get(identity.teamId());
        if (teamCapital == null) throw new IllegalStateException("future capital missing for trade team: " + identity.teamId());
        if (!identity.teamName().equals(teamPosture.teamName()) || !identity.teamName().equals(teamCapital.teamName())) {
            throw new IllegalStateException("strategic context team-name mismatch: " + identity.teamId());
        }
        return new TeamStrategicContext(identity, teamPosture, teamCapital);
    }

    public record TeamIdentity(String teamId, String teamName) {
        public TeamIdentity {
            if (teamId == null || teamId.isBlank()) throw new IllegalArgumentException("teamId must not be blank");
            if (teamName == null || teamName.isBlank()) throw new IllegalArgumentException("teamName must not be blank");
        }
    }

    public record TeamStrategicContext(
        TeamIdentity identity,
        LeagueTeamPostureAnalyzer.TeamPosture posture,
        LeagueFutureCapitalTierAnalyzer.TeamFutureCapital futureCapital) {
        public TeamStrategicContext {
            Objects.requireNonNull(identity, "identity must not be null");
            Objects.requireNonNull(posture, "posture must not be null");
            Objects.requireNonNull(futureCapital, "futureCapital must not be null");
            if (!identity.teamId().equals(posture.teamId()) || !identity.teamId().equals(futureCapital.teamId())) {
                throw new IllegalArgumentException("strategic context components must reference the same team");
            }
        }
    }

    public record StrategicTradeReport(
        TradeAssetAnalyzer.TradeReport trade,
        String fairnessMeasurementPolicyId,
        String fairnessPolicyId,
        Double fairnessGapPercent,
        TradeFairnessPolicy.Classification fairnessClassification,
        String marketEdgePolicyId,
        TradeMarketEdgePolicy.Direction marketEdge,
        String posturePolicyId,
        boolean postureAvailable,
        String futureCapitalPolicyId,
        boolean futureCapitalAvailable,
        TeamStrategicContext sideA,
        TeamStrategicContext sideB) {
        public StrategicTradeReport {
            Objects.requireNonNull(trade, "trade must not be null");
            Objects.requireNonNull(fairnessMeasurementPolicyId, "fairnessMeasurementPolicyId must not be null");
            Objects.requireNonNull(fairnessPolicyId, "fairnessPolicyId must not be null");
            Objects.requireNonNull(fairnessClassification, "fairnessClassification must not be null");
            Objects.requireNonNull(marketEdgePolicyId, "marketEdgePolicyId must not be null");
            Objects.requireNonNull(marketEdge, "marketEdge must not be null");
            Objects.requireNonNull(posturePolicyId, "posturePolicyId must not be null");
            Objects.requireNonNull(futureCapitalPolicyId, "futureCapitalPolicyId must not be null");
            Objects.requireNonNull(sideA, "sideA must not be null");
            Objects.requireNonNull(sideB, "sideB must not be null");
        }
    }
}
