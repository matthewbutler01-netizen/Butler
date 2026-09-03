package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Composes a player-only trade with the neutral roster and production context of the two
 * participating fantasy teams. This layer does not assign contender/rebuilder posture, grade
 * roster need, weight evidence, or recommend accepting/rejecting a trade.
 */
public final class TradeRosterContextAnalyzer {
    private final TradeSupportingEvidenceAnalyzer tradeEvidence;
    private final TradeMarketEdgeAnalyzer marketEdge;
    private final LeagueCompositeTeamProfileAnalyzer teamProfiles;
    private final LeagueProductionContextAnalyzer production;

    public TradeRosterContextAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.tradeEvidence = new TradeSupportingEvidenceAnalyzer(database);
        this.marketEdge = new TradeMarketEdgeAnalyzer();
        this.teamProfiles = new LeagueCompositeTeamProfileAnalyzer(database);
        this.production = new LeagueProductionContextAnalyzer(database);
    }

    public RosterContextReport analyze(String leagueId, int season,
                                       java.util.List<String> sideAPlayerIds,
                                       java.util.List<String> sideBPlayerIds) throws SQLException {
        var trade = tradeEvidence.analyze(leagueId, season, sideAPlayerIds, sideBPlayerIds);
        return compose(trade, marketEdge.analyze(trade),
            teamProfiles.analyze(leagueId, trade.tradeValue().source()),
            production.analyze(leagueId, season));
    }

    public RosterContextReport analyze(String leagueId, int season,
                                       java.util.List<String> sideAPlayerIds,
                                       java.util.List<String> sideBPlayerIds,
                                       String source) throws SQLException {
        var trade = tradeEvidence.analyze(leagueId, season, sideAPlayerIds, sideBPlayerIds, source);
        return compose(trade, marketEdge.analyze(trade),
            teamProfiles.analyze(leagueId, trade.tradeValue().source()),
            production.analyze(leagueId, season));
    }

    static RosterContextReport compose(
        TradeSupportingEvidenceAnalyzer.TradeEvidencePackage trade,
        TradeMarketEdgeAnalyzer.MarketEdgeReport edge,
        LeagueCompositeTeamProfileAnalyzer.CompositeProfileReport profiles,
        LeagueProductionContextAnalyzer.ProductionContextReport production) {
        Objects.requireNonNull(trade, "trade must not be null");
        Objects.requireNonNull(edge, "edge must not be null");
        Objects.requireNonNull(profiles, "profiles must not be null");
        Objects.requireNonNull(production, "production must not be null");

        var market = trade.tradeValue();
        if (!market.leagueId().equals(profiles.leagueId()) || !market.leagueId().equals(production.leagueId())) {
            throw new IllegalStateException("trade and roster-context reports reference different leagues");
        }
        if (!market.source().equals(profiles.source())) {
            throw new IllegalStateException("trade and team profiles use different market-value sources");
        }
        if (trade.season() != production.season()) {
            throw new IllegalStateException("trade evidence and production context use different seasons");
        }
        if (!edge.fairnessPolicyId().equals(trade.fairnessPolicyId())
            || edge.fairnessClassification() != trade.fairnessClassification()
            || !Objects.equals(edge.signedValueDifference(), trade.valueDifference())) {
            throw new IllegalStateException("market-edge report does not match trade evidence");
        }

        String sideATeamId = singleTeamId(market.sideA(), "side A");
        String sideBTeamId = singleTeamId(market.sideB(), "side B");
        if (sideATeamId.equals(sideBTeamId)) {
            throw new IllegalArgumentException("trade sides must resolve to different fantasy teams");
        }

        Map<String, LeagueCompositeTeamProfileAnalyzer.TeamProfile> profilesByTeam = new HashMap<>();
        for (var profile : profiles.teams()) {
            if (profilesByTeam.putIfAbsent(profile.teamId(), profile) != null) {
                throw new IllegalStateException("duplicate team profile: " + profile.teamId());
            }
        }
        Map<String, LeagueProductionContextAnalyzer.TeamProductionContext> productionByTeam = new HashMap<>();
        for (var team : production.teams()) {
            if (productionByTeam.putIfAbsent(team.teamId(), team) != null) {
                throw new IllegalStateException("duplicate team production context: " + team.teamId());
            }
        }

        return new RosterContextReport(
            trade,
            edge,
            production.source(),
            attach(sideATeamId, profilesByTeam, productionByTeam),
            attach(sideBTeamId, profilesByTeam, productionByTeam));
    }

    static String singleTeamId(TradeValueAnalyzer.TradeSide side, String label) {
        Objects.requireNonNull(side, "side must not be null");
        if (side.players().isEmpty()) throw new IllegalArgumentException(label + " must contain at least one player");
        String teamId = null;
        for (var player : side.players()) {
            if (player.teamId() == null || player.teamId().isBlank()) {
                throw new IllegalStateException(label + " player is missing fantasy team identity: " + player.playerId());
            }
            if (teamId == null) teamId = player.teamId();
            else if (!teamId.equals(player.teamId())) {
                throw new IllegalArgumentException(label + " spans multiple fantasy teams");
            }
        }
        return teamId;
    }

    private static TeamRosterContext attach(
        String teamId,
        Map<String, LeagueCompositeTeamProfileAnalyzer.TeamProfile> profiles,
        Map<String, LeagueProductionContextAnalyzer.TeamProductionContext> production) {
        var profile = profiles.get(teamId);
        if (profile == null) throw new IllegalStateException("team profile missing for trade team: " + teamId);
        var teamProduction = production.get(teamId);
        if (teamProduction == null) throw new IllegalStateException("production context missing for trade team: " + teamId);
        if (!profile.teamName().equals(teamProduction.teamName())) {
            throw new IllegalStateException("team profile and production context names differ for team: " + teamId);
        }
        return new TeamRosterContext(teamId, profile.teamName(), profile, teamProduction);
    }

    public record TeamRosterContext(
        String teamId,
        String teamName,
        LeagueCompositeTeamProfileAnalyzer.TeamProfile profile,
        LeagueProductionContextAnalyzer.TeamProductionContext production) {
        public TeamRosterContext {
            Objects.requireNonNull(teamId, "teamId must not be null");
            Objects.requireNonNull(teamName, "teamName must not be null");
            Objects.requireNonNull(profile, "profile must not be null");
            Objects.requireNonNull(production, "production must not be null");
            if (!teamId.equals(profile.teamId()) || !teamId.equals(production.teamId())) {
                throw new IllegalArgumentException("roster context components must reference the same team");
            }
        }
    }

    public record RosterContextReport(
        TradeSupportingEvidenceAnalyzer.TradeEvidencePackage trade,
        TradeMarketEdgeAnalyzer.MarketEdgeReport marketEdge,
        String productionSource,
        TeamRosterContext sideA,
        TeamRosterContext sideB) {
        public RosterContextReport {
            Objects.requireNonNull(trade, "trade must not be null");
            Objects.requireNonNull(marketEdge, "marketEdge must not be null");
            Objects.requireNonNull(productionSource, "productionSource must not be null");
            Objects.requireNonNull(sideA, "sideA must not be null");
            Objects.requireNonNull(sideB, "sideB must not be null");
            if (sideA.teamId().equals(sideB.teamId())) {
                throw new IllegalArgumentException("roster-context sides must reference different teams");
            }
        }
    }
}
