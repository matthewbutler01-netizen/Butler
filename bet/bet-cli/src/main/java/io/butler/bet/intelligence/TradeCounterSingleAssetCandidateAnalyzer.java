package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Discovers single-asset market-value candidates that would move an outside-band trade into the
 * existing governed fairness band. Candidate discovery is deliberately non-prescriptive: it
 * returns a ranked set and does not choose an asset, infer team perspective, or emit COUNTER.
 */
public final class TradeCounterSingleAssetCandidateAnalyzer {
    public static final String POLICY_ID =
        "trade-counter-single-asset-candidate-v1-market-fair-minimum-excess";

    private final LeagueAssetInventoryAnalyzer inventory;

    public TradeCounterSingleAssetCandidateAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.inventory = new LeagueAssetInventoryAnalyzer(database);
    }

    public CandidateReport analyze(TradeAssetAnalyzer.TradeReport trade) throws SQLException {
        Objects.requireNonNull(trade, "trade must not be null");
        var context = TradeCounterValueContextAnalyzer.compose(trade);
        var leagueInventory = inventory.analyze(trade.leagueId(), trade.source());
        return assess(trade, context, leagueInventory);
    }

    static CandidateReport assess(
        TradeAssetAnalyzer.TradeReport trade,
        TradeCounterValueContextAnalyzer.CounterValueContextReport context,
        LeagueAssetInventoryAnalyzer.InventoryReport inventory) {
        Objects.requireNonNull(trade, "trade must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(inventory, "inventory must not be null");
        requireMatchingCoordinates(trade, context, inventory);

        if (!context.available()) {
            return unavailable(trade, context.insufficiencyReason());
        }

        var target = context.target();
        if (target.currentFairness() == TradeFairnessPolicy.Classification.MARKET_FAIR) {
            return available(trade, target.currentFairness(), List.of());
        }

        TeamOwner sideAOwner = resolveOwner(trade.sideA());
        TeamOwner sideBOwner = resolveOwner(trade.sideB());
        if (sideAOwner == null || sideBOwner == null) {
            return unavailable(trade,
                "Trade counter candidate discovery requires each package to resolve to one current fantasy team.");
        }
        if (sideAOwner.teamId().equals(sideBOwner.teamId())) {
            return unavailable(trade,
                "Trade counter candidate discovery requires distinct package owners.");
        }

        var addTarget = target.options().stream()
            .filter(option -> option.type()
                == TradeCounterValueTargetAnalyzer.AdjustmentType.ADD_TO_LOWER_VALUE_PACKAGE)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("outside-band target missing add-to-lower option"));
        var removeTarget = target.options().stream()
            .filter(option -> option.type()
                == TradeCounterValueTargetAnalyzer.AdjustmentType.REMOVE_FROM_HIGHER_VALUE_PACKAGE)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("outside-band target missing remove-from-higher option"));

        TeamOwner lowerOwner = addTarget.side() == TradeCounterValueTargetAnalyzer.Side.SIDE_A
            ? sideAOwner : sideBOwner;
        Set<String> packagePlayerIds = packagePlayerIds(trade);
        Set<String> packagePickIds = packagePickIds(trade);
        List<Candidate> candidates = new ArrayList<>();

        addInventoryCandidates(
            trade, inventory, lowerOwner, addTarget, packagePlayerIds, packagePickIds, candidates);
        addRemovalCandidates(trade, removeTarget, candidates);

        candidates.sort(Comparator
            .comparingDouble(Candidate::excessValue)
            .thenComparingDouble(Candidate::assetValue)
            .thenComparing(candidate -> candidate.adjustmentType().name())
            .thenComparing(candidate -> candidate.assetType().name())
            .thenComparing(Candidate::assetId));
        return available(trade, target.currentFairness(), List.copyOf(candidates));
    }

    private static void addInventoryCandidates(
        TradeAssetAnalyzer.TradeReport trade,
        LeagueAssetInventoryAnalyzer.InventoryReport inventory,
        TeamOwner lowerOwner,
        TradeCounterValueTargetAnalyzer.AdjustmentOption target,
        Set<String> packagePlayerIds,
        Set<String> packagePickIds,
        List<Candidate> candidates) {
        LeagueAssetInventoryAnalyzer.TeamInventory team = inventory.teams().stream()
            .filter(candidate -> candidate.teamId().equals(lowerOwner.teamId()))
            .findFirst()
            .orElse(null);
        if (team == null) return;

        for (var player : team.players()) {
            if (!player.valued() || packagePlayerIds.contains(player.playerId())
                || !freshEnough(player.asOfDate(), trade.minimumAsOfDate())) continue;
            maybeAddCandidate(
                trade,
                AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
                target.side(),
                AssetType.PLAYER,
                player.playerId(),
                player.playerName(),
                team.teamId(),
                team.teamName(),
                player.value(),
                player.asOfDate(),
                target.requiredValueChange(),
                candidates);
        }
        for (var pick : team.draftPicks()) {
            if (!pick.valued() || packagePickIds.contains(pick.draftPickId())
                || !freshEnough(pick.asOfDate(), trade.minimumAsOfDate())) continue;
            maybeAddCandidate(
                trade,
                AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
                target.side(),
                AssetType.DRAFT_PICK,
                pick.draftPickId(),
                pick.label(),
                team.teamId(),
                team.teamName(),
                pick.value(),
                pick.asOfDate(),
                target.requiredValueChange(),
                candidates);
        }
    }

    private static void addRemovalCandidates(
        TradeAssetAnalyzer.TradeReport trade,
        TradeCounterValueTargetAnalyzer.AdjustmentOption target,
        List<Candidate> candidates) {
        TradeAssetAnalyzer.TradeSide side = target.side() == TradeCounterValueTargetAnalyzer.Side.SIDE_A
            ? trade.sideA() : trade.sideB();
        for (var player : side.players()) {
            if (!player.valued() || !freshEnough(player.asOfDate(), trade.minimumAsOfDate())) continue;
            maybeAddCandidate(
                trade,
                AdjustmentType.REMOVE_ASSET_FROM_HIGHER_PACKAGE,
                target.side(),
                AssetType.PLAYER,
                player.playerId(),
                player.playerName(),
                player.teamId(),
                player.teamName(),
                player.value(),
                player.asOfDate(),
                target.requiredValueChange(),
                candidates);
        }
        for (var pick : side.draftPicks()) {
            if (!pick.valued() || !freshEnough(pick.asOfDate(), trade.minimumAsOfDate())) continue;
            maybeAddCandidate(
                trade,
                AdjustmentType.REMOVE_ASSET_FROM_HIGHER_PACKAGE,
                target.side(),
                AssetType.DRAFT_PICK,
                pick.draftPickId(),
                pick.label(),
                pick.ownerTeamId(),
                pick.ownerTeamName(),
                pick.value(),
                pick.asOfDate(),
                target.requiredValueChange(),
                candidates);
        }
    }

    private static void maybeAddCandidate(
        TradeAssetAnalyzer.TradeReport trade,
        AdjustmentType adjustmentType,
        TradeCounterValueTargetAnalyzer.Side side,
        AssetType assetType,
        String assetId,
        String displayName,
        String teamId,
        String teamName,
        double assetValue,
        LocalDate asOfDate,
        double requiredValueChange,
        List<Candidate> candidates) {
        double sideAValue = trade.sideA().totalValue();
        double sideBValue = trade.sideB().totalValue();
        if (adjustmentType == AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE) {
            if (side == TradeCounterValueTargetAnalyzer.Side.SIDE_A) sideAValue += assetValue;
            else sideBValue += assetValue;
        } else {
            if (side == TradeCounterValueTargetAnalyzer.Side.SIDE_A) sideAValue -= assetValue;
            else sideBValue -= assetValue;
        }
        if (sideAValue < 0.0 || sideBValue < 0.0) return;

        double resultingGap = TradeFairnessMeasurementPolicy.symmetricGapPercent(sideAValue, sideBValue);
        var resultingFairness = TradeFairnessPolicy.classify(resultingGap);
        if (resultingFairness != TradeFairnessPolicy.Classification.MARKET_FAIR) return;

        candidates.add(new Candidate(
            adjustmentType,
            side,
            assetType,
            requireText(assetId, "assetId"),
            requireText(displayName, "displayName"),
            requireText(teamId, "teamId"),
            requireText(teamName, "teamName"),
            assetValue,
            asOfDate,
            requiredValueChange,
            Math.max(0.0, assetValue - requiredValueChange),
            sideAValue,
            sideBValue,
            resultingGap,
            resultingFairness));
    }

    private static TeamOwner resolveOwner(TradeAssetAnalyzer.TradeSide side) {
        Set<TeamOwner> owners = new LinkedHashSet<>();
        for (var player : side.players()) {
            owners.add(new TeamOwner(requireText(player.teamId(), "player.teamId"),
                requireText(player.teamName(), "player.teamName")));
        }
        for (var pick : side.draftPicks()) {
            owners.add(new TeamOwner(requireText(pick.ownerTeamId(), "pick.ownerTeamId"),
                requireText(pick.ownerTeamName(), "pick.ownerTeamName")));
        }
        if (owners.isEmpty()) return null;
        String teamId = owners.iterator().next().teamId();
        if (owners.stream().anyMatch(owner -> !owner.teamId().equals(teamId))) return null;
        return owners.iterator().next();
    }

    private static Set<String> packagePlayerIds(TradeAssetAnalyzer.TradeReport trade) {
        Set<String> ids = new HashSet<>();
        trade.sideA().players().forEach(player -> ids.add(player.playerId()));
        trade.sideB().players().forEach(player -> ids.add(player.playerId()));
        return Set.copyOf(ids);
    }

    private static Set<String> packagePickIds(TradeAssetAnalyzer.TradeReport trade) {
        Set<String> ids = new HashSet<>();
        trade.sideA().draftPicks().forEach(pick -> ids.add(pick.draftPickId()));
        trade.sideB().draftPicks().forEach(pick -> ids.add(pick.draftPickId()));
        return Set.copyOf(ids);
    }

    private static boolean freshEnough(LocalDate asOfDate, LocalDate minimumAsOfDate) {
        return minimumAsOfDate == null || (asOfDate != null && !asOfDate.isBefore(minimumAsOfDate));
    }

    private static void requireMatchingCoordinates(
        TradeAssetAnalyzer.TradeReport trade,
        TradeCounterValueContextAnalyzer.CounterValueContextReport context,
        LeagueAssetInventoryAnalyzer.InventoryReport inventory) {
        if (!trade.leagueId().equals(context.leagueId())
            || !trade.source().equals(context.source())
            || !trade.leagueId().equals(inventory.leagueId())
            || !trade.source().equals(inventory.source())) {
            throw new IllegalArgumentException("trade, counter context, and inventory coordinates must match");
        }
    }

    private static CandidateReport available(
        TradeAssetAnalyzer.TradeReport trade,
        TradeFairnessPolicy.Classification currentFairness,
        List<Candidate> candidates) {
        return new CandidateReport(
            POLICY_ID,
            TradeCounterValueContextAnalyzer.POLICY_ID,
            TradeCounterValueTargetAnalyzer.POLICY_ID,
            trade.leagueId(),
            trade.source(),
            trade.minimumAsOfDate(),
            true,
            null,
            currentFairness,
            candidates);
    }

    private static CandidateReport unavailable(TradeAssetAnalyzer.TradeReport trade, String reason) {
        return new CandidateReport(
            POLICY_ID,
            TradeCounterValueContextAnalyzer.POLICY_ID,
            TradeCounterValueTargetAnalyzer.POLICY_ID,
            trade.leagueId(),
            trade.source(),
            trade.minimumAsOfDate(),
            false,
            requireText(reason, "reason"),
            null,
            List.of());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record TeamOwner(String teamId, String teamName) {}

    public enum AdjustmentType {
        ADD_ASSET_TO_LOWER_PACKAGE,
        REMOVE_ASSET_FROM_HIGHER_PACKAGE
    }

    public enum AssetType {
        PLAYER,
        DRAFT_PICK
    }

    public record Candidate(
        AdjustmentType adjustmentType,
        TradeCounterValueTargetAnalyzer.Side side,
        AssetType assetType,
        String assetId,
        String displayName,
        String teamId,
        String teamName,
        double assetValue,
        LocalDate asOfDate,
        double requiredValueChange,
        double excessValue,
        double resultingSideAValue,
        double resultingSideBValue,
        double resultingGapPercent,
        TradeFairnessPolicy.Classification resultingFairness) {
        public Candidate {
            Objects.requireNonNull(adjustmentType, "adjustmentType must not be null");
            Objects.requireNonNull(side, "side must not be null");
            Objects.requireNonNull(assetType, "assetType must not be null");
            assetId = requireText(assetId, "assetId");
            displayName = requireText(displayName, "displayName");
            teamId = requireText(teamId, "teamId");
            teamName = requireText(teamName, "teamName");
            requireFiniteNonNegative(assetValue, "assetValue");
            if (!Double.isFinite(requiredValueChange) || requiredValueChange <= 0.0) {
                throw new IllegalArgumentException("requiredValueChange must be finite and positive");
            }
            requireFiniteNonNegative(excessValue, "excessValue");
            requireFiniteNonNegative(resultingSideAValue, "resultingSideAValue");
            requireFiniteNonNegative(resultingSideBValue, "resultingSideBValue");
            requireFiniteNonNegative(resultingGapPercent, "resultingGapPercent");
            if (resultingFairness != TradeFairnessPolicy.Classification.MARKET_FAIR) {
                throw new IllegalArgumentException("candidate must produce MARKET_FAIR result");
            }
        }
    }

    public record CandidateReport(
        String policyId,
        String contextPolicyId,
        String targetPolicyId,
        String leagueId,
        String source,
        LocalDate minimumAsOfDate,
        boolean available,
        String insufficiencyReason,
        TradeFairnessPolicy.Classification currentFairness,
        List<Candidate> candidates) {
        public CandidateReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!TradeCounterValueContextAnalyzer.POLICY_ID.equals(contextPolicyId)) {
                throw new IllegalArgumentException("unexpected contextPolicyId");
            }
            if (!TradeCounterValueTargetAnalyzer.POLICY_ID.equals(targetPolicyId)) {
                throw new IllegalArgumentException("unexpected targetPolicyId");
            }
            leagueId = requireText(leagueId, "leagueId");
            source = requireText(source, "source");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
            if (available) {
                if (insufficiencyReason != null) {
                    throw new IllegalArgumentException("available candidate report cannot carry insufficiency reason");
                }
                Objects.requireNonNull(currentFairness, "available candidate report requires currentFairness");
            } else {
                if (insufficiencyReason == null || insufficiencyReason.isBlank()) {
                    throw new IllegalArgumentException("unavailable candidate report requires insufficiency reason");
                }
                if (currentFairness != null || !candidates.isEmpty()) {
                    throw new IllegalArgumentException("unavailable candidate report cannot carry fairness or candidates");
                }
            }
        }
    }

    private static void requireFiniteNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(field + " must be finite and non-negative");
        }
    }
}
