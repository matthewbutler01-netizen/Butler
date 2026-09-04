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
 * Reconstructs trade-team positional depth using one shared roster-mutation algorithm. The full
 * league path replaces both trade teams for league-relative transition evidence, while the selected
 * team path preserves the earlier v4 material-loss evidence contract.
 */
public final class TradeFlexiblePostTradeDepthAnalyzer {
    public static final String POLICY_ID = "trade-flexible-post-trade-depth-v1-two-team-exchange";

    private TradeFlexiblePostTradeDepthAnalyzer() {}

    public static PostTradeDepthReport apply(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        TradeFlexibleSlotContextAnalyzer.TeamFlexibleContext teamContext,
        TradeAssetAnalyzer.TradeSide outgoing,
        TradeAssetAnalyzer.TradeSide incoming) {
        Objects.requireNonNull(context, "context must not be null");
        return apply(context.flexible(), context.depth(), teamContext, outgoing, incoming);
    }

    public static PostTradeDepthReport apply(
        TradeFlexibleSlotContextAnalyzer.TradeFlexibleContextReport flexible,
        LeaguePositionalDepthAnalyzer.DepthReport depth,
        TradeFlexibleSlotContextAnalyzer.TeamFlexibleContext teamContext,
        TradeAssetAnalyzer.TradeSide outgoing,
        TradeAssetAnalyzer.TradeSide incoming) {
        validateInputs(flexible, depth, teamContext, outgoing, incoming);
        boolean selectedIsSideA = teamContext.equals(flexible.sideA());
        var selectedIdentity = teamContext.identity();
        var oppositeContext = selectedIsSideA ? flexible.sideB() : flexible.sideA();
        var oppositeIdentity = oppositeContext.identity();

        var selectedPostTrade = applySelectedTeam(flexible, depth, teamContext, outgoing, incoming);
        var oppositePostTrade = applySelectedTeam(
            flexible, depth, oppositeContext, incoming, outgoing);

        List<LeaguePositionalDepthAnalyzer.TeamDepth> teams = new ArrayList<>();
        boolean selectedReplaced = false;
        boolean oppositeReplaced = false;
        for (var team : depth.teams()) {
            if (team.teamId().equals(selectedIdentity.teamId())) {
                teams.add(selectedPostTrade);
                selectedReplaced = true;
            } else if (team.teamId().equals(oppositeIdentity.teamId())) {
                teams.add(oppositePostTrade);
                oppositeReplaced = true;
            } else {
                teams.add(team);
            }
        }
        if (!selectedReplaced || !oppositeReplaced) {
            throw new IllegalStateException("both trade teams must be present in league depth");
        }
        var postTradeDepth = new LeaguePositionalDepthAnalyzer.DepthReport(
            depth.leagueId(),
            depth.source(),
            depth.minimumAsOfDate(),
            List.copyOf(teams));
        return new PostTradeDepthReport(
            POLICY_ID,
            selectedIdentity,
            oppositeIdentity,
            selectedPostTrade,
            oppositePostTrade,
            postTradeDepth);
    }

    public static LeaguePositionalDepthAnalyzer.TeamDepth applySelectedTeam(
        TradeFlexibleSlotContextAnalyzer.TradeFlexibleContextReport flexible,
        LeaguePositionalDepthAnalyzer.DepthReport depth,
        TradeFlexibleSlotContextAnalyzer.TeamFlexibleContext teamContext,
        TradeAssetAnalyzer.TradeSide outgoing,
        TradeAssetAnalyzer.TradeSide incoming) {
        validateInputs(flexible, depth, teamContext, outgoing, incoming);
        var identity = teamContext.identity();
        var currentTeam = findTeam(depth, identity);
        return applyTrade(
            currentTeam,
            identity,
            flexible.flexSlots(),
            flexible.superFlexSlots(),
            flexible.minimumAsOfDate(),
            outgoing,
            incoming);
    }

    private static void validateInputs(
        TradeFlexibleSlotContextAnalyzer.TradeFlexibleContextReport flexible,
        LeaguePositionalDepthAnalyzer.DepthReport depth,
        TradeFlexibleSlotContextAnalyzer.TeamFlexibleContext teamContext,
        TradeAssetAnalyzer.TradeSide outgoing,
        TradeAssetAnalyzer.TradeSide incoming) {
        Objects.requireNonNull(flexible, "flexible must not be null");
        Objects.requireNonNull(depth, "depth must not be null");
        Objects.requireNonNull(teamContext, "teamContext must not be null");
        Objects.requireNonNull(outgoing, "outgoing must not be null");
        Objects.requireNonNull(incoming, "incoming must not be null");
        if (!flexible.leagueId().equals(depth.leagueId())) {
            throw new IllegalStateException("flexible context and depth reference different leagues");
        }
        if (!flexible.source().equals(depth.source())) {
            throw new IllegalStateException("flexible context and depth use different value sources");
        }
        if (!Objects.equals(flexible.minimumAsOfDate(), depth.minimumAsOfDate())) {
            throw new IllegalStateException("flexible context and depth use different freshness boundaries");
        }
        boolean knownTeam = teamContext.equals(flexible.sideA()) || teamContext.equals(flexible.sideB());
        if (!knownTeam) {
            throw new IllegalArgumentException("team flexible context must belong to recommendation context");
        }
        if (flexible.flexSlots() + flexible.superFlexSlots() == 0) {
            throw new IllegalArgumentException("post-trade flexible depth requires FLEX or SUPERFLEX exposure");
        }
    }

    private static LeaguePositionalDepthAnalyzer.TeamDepth findTeam(
        LeaguePositionalDepthAnalyzer.DepthReport depth,
        TradeAssetStrategicContextAnalyzer.TeamIdentity identity) {
        var team = depth.teams().stream()
            .filter(candidate -> candidate.teamId().equals(identity.teamId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "positional depth missing for trade team: " + identity.teamId()));
        if (!team.teamName().equals(identity.teamName())) {
            throw new IllegalStateException("trade and positional-depth team names differ: " + identity.teamId());
        }
        return team;
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
                    "outgoing player does not belong to trade team " + identity.teamId() + ": " + player.playerId());
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
                    "incoming player is already rostered by trade team " + identity.teamId() + ": " + player.playerId());
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
        TradeAssetStrategicContextAnalyzer.TeamIdentity oppositeTeam,
        LeaguePositionalDepthAnalyzer.TeamDepth selectedTeamDepth,
        LeaguePositionalDepthAnalyzer.TeamDepth oppositeTeamDepth,
        LeaguePositionalDepthAnalyzer.DepthReport leagueDepth) {
        public PostTradeDepthReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            Objects.requireNonNull(selectedTeam, "selectedTeam must not be null");
            Objects.requireNonNull(oppositeTeam, "oppositeTeam must not be null");
            Objects.requireNonNull(selectedTeamDepth, "selectedTeamDepth must not be null");
            Objects.requireNonNull(oppositeTeamDepth, "oppositeTeamDepth must not be null");
            Objects.requireNonNull(leagueDepth, "leagueDepth must not be null");
            if (selectedTeam.teamId().equals(oppositeTeam.teamId())) {
                throw new IllegalArgumentException("post-trade depth requires distinct trade teams");
            }
            if (!selectedTeam.teamId().equals(selectedTeamDepth.teamId())
                || !selectedTeam.teamName().equals(selectedTeamDepth.teamName())
                || !oppositeTeam.teamId().equals(oppositeTeamDepth.teamId())
                || !oppositeTeam.teamName().equals(oppositeTeamDepth.teamName())) {
                throw new IllegalArgumentException("post-trade team identity mismatch");
            }
            long selectedMatches = leagueDepth.teams().stream()
                .filter(team -> team.teamId().equals(selectedTeam.teamId()))
                .count();
            long oppositeMatches = leagueDepth.teams().stream()
                .filter(team -> team.teamId().equals(oppositeTeam.teamId()))
                .count();
            if (selectedMatches != 1 || oppositeMatches != 1) {
                throw new IllegalArgumentException("post-trade league depth must contain both trade teams exactly once");
            }
        }
    }
}
