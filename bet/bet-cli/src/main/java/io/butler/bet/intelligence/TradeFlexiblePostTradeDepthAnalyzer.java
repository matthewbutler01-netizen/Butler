package io.butler.bet.intelligence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reconstructs the selected trade team's positional depth after a trade and replaces only that team
 * inside the full league depth report. This is neutral evidence plumbing; it does not classify
 * pressure, materiality, recommendations, or vetoes.
 */
public final class TradeFlexiblePostTradeDepthAnalyzer {
    public static final String POLICY_ID = "trade-flexible-post-trade-depth-v1-selected-team-replacement";

    private TradeFlexiblePostTradeDepthAnalyzer() {}

    public static PostTradeDepthReport apply(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        TradeFlexibleSlotContextAnalyzer.TeamFlexibleContext teamContext,
        TradeAssetAnalyzer.TradeSide outgoing,
        TradeAssetAnalyzer.TradeSide incoming) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(teamContext, "teamContext must not be null");
        Objects.requireNonNull(outgoing, "outgoing must not be null");
        Objects.requireNonNull(incoming, "incoming must not be null");
        boolean knownTeam = teamContext.equals(context.flexible().sideA())
            || teamContext.equals(context.flexible().sideB());
        if (!knownTeam) {
            throw new IllegalArgumentException("team flexible context must belong to recommendation context");
        }
        if (context.flexible().flexSlots() + context.flexible().superFlexSlots() == 0) {
            throw new IllegalArgumentException("post-trade flexible depth requires FLEX or SUPERFLEX exposure");
        }

        var identity = teamContext.identity();
        var currentTeam = context.depth().teams().stream()
            .filter(team -> team.teamId().equals(identity.teamId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "positional depth missing for trade team: " + identity.teamId()));
        if (!currentTeam.teamName().equals(identity.teamName())) {
            throw new IllegalStateException("trade and positional-depth team names differ: " + identity.teamId());
        }

        var postTradeTeam = applyTrade(
            currentTeam,
            identity,
            context.flexible().flexSlots(),
            context.flexible().superFlexSlots(),
            context.flexible().minimumAsOfDate(),
            outgoing,
            incoming);

        List<LeaguePositionalDepthAnalyzer.TeamDepth> teams = new ArrayList<>();
        boolean replaced = false;
        for (var team : context.depth().teams()) {
            if (team.teamId().equals(identity.teamId())) {
                teams.add(postTradeTeam);
                replaced = true;
            } else {
                teams.add(team);
            }
        }
        if (!replaced) {
            throw new IllegalStateException("selected trade team was not present in league depth");
        }
        var postTradeDepth = new LeaguePositionalDepthAnalyzer.DepthReport(
            context.depth().leagueId(),
            context.depth().source(),
            context.depth().minimumAsOfDate(),
            List.copyOf(teams));
        return new PostTradeDepthReport(POLICY_ID, identity, postTradeTeam, postTradeDepth);
    }

    private static LeaguePositionalDepthAnalyzer.TeamDepth applyTrade(
        LeaguePositionalDepthAnalyzer.TeamDepth currentTeam,
        TradeAssetStrategicContextAnalyzer.TeamIdentity identity,
        int flexSlots,
        int superFlexSlots,
        java.time.LocalDate minimumAsOfDate,
        TradeAssetAnalyzer.TradeSide outgoing,
        TradeAssetAnalyzer.TradeSide incoming) {
        Set<String> relevantPositions = relevantPositions(flexSlots, superFlexSlots);
        Map<String, LeaguePositionalDepthAnalyzer.PlayerDepthValue> playersById = new HashMap<>();
        for (var position : currentTeam.positions().values()) {
            for (var player : position.players()) {
                if (playersById.put(player.playerId(), player) != null) {
                    throw new IllegalStateException("duplicate player in positional depth: " + player.playerId());
                }
            }
        }

        for (var player : outgoing.players()) {
            if (!identity.teamId().equals(player.teamId()) || !identity.teamName().equals(player.teamName())) {
                throw new IllegalArgumentException(
                    "outgoing player does not belong to selected trade team: " + player.playerId());
            }
            var removed = playersById.remove(player.playerId());
            if (removed == null && relevantPositions.contains(normalizePosition(player.position()))) {
                throw new IllegalStateException(
                    "outgoing flexible-eligible player missing from current depth: " + player.playerId());
            }
        }

        for (var player : incoming.players()) {
            String position = normalizePosition(player.position());
            if (!relevantPositions.contains(position)) continue;
            if (playersById.containsKey(player.playerId())) {
                throw new IllegalArgumentException(
                    "incoming player is already rostered by selected team: " + player.playerId());
            }
            if (player.value() == null || !Double.isFinite(player.value()) || player.value() < 0.0 || player.stale()) {
                throw new IllegalArgumentException(
                    "incoming flexible-eligible player requires current finite value: " + player.playerId());
            }
            if (minimumAsOfDate != null
                && (player.asOfDate() == null || player.asOfDate().isBefore(minimumAsOfDate))) {
                throw new IllegalArgumentException(
                    "incoming flexible-eligible player is outside freshness boundary: " + player.playerId());
            }
            playersById.put(player.playerId(), new LeaguePositionalDepthAnalyzer.PlayerDepthValue(
                player.playerId(), player.playerName(), position, "TRADE_IN", player.value(), player.asOfDate()));
        }

        Map<String, List<LeaguePositionalDepthAnalyzer.PlayerDepthValue>> grouped = new LinkedHashMap<>();
        playersById.values().forEach(player -> grouped
            .computeIfAbsent(normalizePosition(player.position()), ignored -> new ArrayList<>())
            .add(player));

        Comparator<LeaguePositionalDepthAnalyzer.PlayerDepthValue> order = Comparator
            .comparingDouble(LeaguePositionalDepthAnalyzer.PlayerDepthValue::value).reversed()
            .thenComparing(LeaguePositionalDepthAnalyzer.PlayerDepthValue::playerName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(LeaguePositionalDepthAnalyzer.PlayerDepthValue::playerId);
        Map<String, LeaguePositionalDepthAnalyzer.PositionDepth> positions = new LinkedHashMap<>();
        grouped.forEach((position, players) -> {
            players.sort(order);
            positions.put(position, new LeaguePositionalDepthAnalyzer.PositionDepth(
                position, players.size(), players.size(), 0, 0, List.copyOf(players)));
        });
        return new LeaguePositionalDepthAnalyzer.TeamDepth(
            identity.teamId(), identity.teamName(), Map.copyOf(positions));
    }

    private static Set<String> relevantPositions(int flexSlots, int superFlexSlots) {
        var exposure = TradeFlexibleSlotEligibilityPolicy.exposure(flexSlots, superFlexSlots);
        Set<String> positions = new HashSet<>();
        if (exposure.active(TradeFlexibleSlotEligibilityPolicy.SlotType.FLEX)) {
            positions.addAll(TradeFlexibleSlotEligibilityPolicy.eligiblePositions(
                TradeFlexibleSlotEligibilityPolicy.SlotType.FLEX));
        }
        if (exposure.active(TradeFlexibleSlotEligibilityPolicy.SlotType.SUPERFLEX)) {
            positions.addAll(TradeFlexibleSlotEligibilityPolicy.eligiblePositions(
                TradeFlexibleSlotEligibilityPolicy.SlotType.SUPERFLEX));
        }
        return Set.copyOf(positions);
    }

    private static String normalizePosition(String position) {
        if (position == null || position.isBlank()) {
            throw new IllegalArgumentException("position must not be blank");
        }
        return position.trim().toUpperCase(Locale.ROOT);
    }

    public record PostTradeDepthReport(
        String policyId,
        TradeAssetStrategicContextAnalyzer.TeamIdentity selectedTeam,
        LeaguePositionalDepthAnalyzer.TeamDepth selectedTeamDepth,
        LeaguePositionalDepthAnalyzer.DepthReport leagueDepth) {
        public PostTradeDepthReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            Objects.requireNonNull(selectedTeam, "selectedTeam must not be null");
            Objects.requireNonNull(selectedTeamDepth, "selectedTeamDepth must not be null");
            Objects.requireNonNull(leagueDepth, "leagueDepth must not be null");
            if (!selectedTeam.teamId().equals(selectedTeamDepth.teamId())
                || !selectedTeam.teamName().equals(selectedTeamDepth.teamName())) {
                throw new IllegalArgumentException("selected team identity mismatch");
            }
            long matches = leagueDepth.teams().stream()
                .filter(team -> team.teamId().equals(selectedTeam.teamId()))
                .count();
            if (matches != 1) {
                throw new IllegalArgumentException("post-trade league depth must contain selected team exactly once");
            }
        }
    }
}
