package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Rebuilds each governed single-asset counter candidate through Butler's existing season-aware
 * positional/flexible recommendation context and applies the existing v5 strategic veto detector
 * bilaterally. This analyzer labels evidence; it does not select a candidate or emit COUNTER.
 */
public final class TradeCounterStrategicCandidateVettingAnalyzer {
    public static final String POLICY_ID =
        "trade-counter-strategic-candidate-v1-bilateral-v5-veto";

    private final TradeAssetAnalyzer trades;
    private final TradeCounterSingleAssetCandidateAnalyzer candidates;
    private final TradeFlexibleRecommendationContextAnalyzer contexts;

    public TradeCounterStrategicCandidateVettingAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.trades = new TradeAssetAnalyzer(database);
        this.candidates = new TradeCounterSingleAssetCandidateAnalyzer(database);
        this.contexts = new TradeFlexibleRecommendationContextAnalyzer(database);
    }

    public StrategicCandidateReport analyze(
        String leagueId,
        int season,
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB) throws SQLException {
        var trade = trades.analyze(leagueId, sideA, sideB);
        return analyzeResolved(trade, season);
    }

    public StrategicCandidateReport analyze(
        String leagueId,
        int season,
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB,
        String source) throws SQLException {
        var trade = trades.analyze(leagueId, sideA, sideB, source);
        return analyzeResolved(trade, season);
    }

    public StrategicCandidateReport analyze(
        String leagueId,
        int season,
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB,
        LocalDate minimumAsOfDate) throws SQLException {
        var trade = trades.analyze(leagueId, sideA, sideB, minimumAsOfDate);
        return analyzeResolved(trade, season);
    }

    public StrategicCandidateReport analyze(
        String leagueId,
        int season,
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB,
        String source,
        LocalDate minimumAsOfDate) throws SQLException {
        var trade = trades.analyze(leagueId, sideA, sideB, source, minimumAsOfDate);
        return analyzeResolved(trade, season);
    }

    private StrategicCandidateReport analyzeResolved(
        TradeAssetAnalyzer.TradeReport originalTrade,
        int season) throws SQLException {
        requireSeason(season);
        var marketCandidates = candidates.analyze(originalTrade);
        if (!marketCandidates.available()) {
            return unavailable(originalTrade, season, marketCandidates.insufficiencyReason());
        }
        if (marketCandidates.candidates().isEmpty()) {
            return available(originalTrade, season, List.of());
        }

        List<StrategicCandidate> vetted = new ArrayList<>();
        int marketRank = 1;
        for (var candidate : marketCandidates.candidates()) {
            ModifiedPackages modified = applyCandidate(originalTrade, candidate);
            var context = analyzeContext(originalTrade, season, modified);
            EvidenceStatus evidence = evidenceStatus(context);
            if (!evidence.complete()) {
                return unavailable(originalTrade, season,
                    "Strategic counter candidate vetting requires complete "
                        + missingEvidence(evidence) + " evidence.");
            }

            SideVetting sideA = assessSide(context, Side.SIDE_A);
            SideVetting sideB = assessSide(context, Side.SIDE_B);
            vetted.add(new StrategicCandidate(
                marketRank++,
                candidate,
                combine(sideA, sideB),
                sideA,
                sideB));
        }
        return available(originalTrade, season, List.copyOf(vetted));
    }

    private TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport analyzeContext(
        TradeAssetAnalyzer.TradeReport originalTrade,
        int season,
        ModifiedPackages modified) throws SQLException {
        if (originalTrade.minimumAsOfDate() != null) {
            return contexts.analyze(
                originalTrade.leagueId(),
                season,
                modified.sideA(),
                modified.sideB(),
                originalTrade.source(),
                originalTrade.minimumAsOfDate());
        }
        return contexts.analyze(
            originalTrade.leagueId(),
            season,
            modified.sideA(),
            modified.sideB(),
            originalTrade.source());
    }

    static ModifiedPackages applyCandidate(
        TradeAssetAnalyzer.TradeReport originalTrade,
        TradeCounterSingleAssetCandidateAnalyzer.Candidate candidate) {
        Objects.requireNonNull(originalTrade, "originalTrade must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");

        List<String> sideAPlayers = new ArrayList<>(playerIds(originalTrade.sideA()));
        List<String> sideAPicks = new ArrayList<>(pickIds(originalTrade.sideA()));
        List<String> sideBPlayers = new ArrayList<>(playerIds(originalTrade.sideB()));
        List<String> sideBPicks = new ArrayList<>(pickIds(originalTrade.sideB()));

        List<String> players = candidate.side() == TradeCounterValueTargetAnalyzer.Side.SIDE_A
            ? sideAPlayers : sideBPlayers;
        List<String> picks = candidate.side() == TradeCounterValueTargetAnalyzer.Side.SIDE_A
            ? sideAPicks : sideBPicks;
        List<String> assets = candidate.assetType() == TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER
            ? players : picks;

        if (candidate.adjustmentType()
            == TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE) {
            if (assets.contains(candidate.assetId())) {
                throw new IllegalStateException("counter add candidate is already present in target package: "
                    + candidate.assetId());
            }
            assets.add(candidate.assetId());
        } else {
            if (!assets.remove(candidate.assetId())) {
                throw new IllegalStateException("counter removal candidate is not present in target package: "
                    + candidate.assetId());
            }
            if (players.isEmpty() && picks.isEmpty()) {
                throw new IllegalStateException("counter removal candidate would leave an empty trade package");
            }
        }

        return new ModifiedPackages(
            new TradeAssetAnalyzer.TradePackage(List.copyOf(sideAPlayers), List.copyOf(sideAPicks)),
            new TradeAssetAnalyzer.TradePackage(List.copyOf(sideBPlayers), List.copyOf(sideBPicks)));
    }

    static EvidenceStatus evidenceStatus(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context) {
        Objects.requireNonNull(context, "context must not be null");
        var trade = context.trade();
        boolean positionalAvailable = trade.positionAvailability().values().stream()
            .allMatch(TradeAssetPositionalContextAnalyzer.PositionAvailability::available);
        return new EvidenceStatus(
            trade.strategic().marketEdge() != TradeMarketEdgePolicy.Direction.UNAVAILABLE,
            trade.strategic().postureAvailable(),
            trade.strategic().futureCapitalAvailable(),
            positionalAvailable,
            context.flexible().flexiblePressureAvailable());
    }

    static SideVetting assessSide(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        Side side) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(side, "side must not be null");
        var report = context.trade();
        boolean sideA = side == Side.SIDE_A;
        var team = sideA ? report.strategic().sideA() : report.strategic().sideB();
        var positional = sideA ? report.sideA() : report.sideB();
        var flexibleTeam = sideA ? context.flexible().sideA() : context.flexible().sideB();
        var outgoing = sideA ? report.strategic().trade().sideA() : report.strategic().trade().sideB();
        var incoming = sideA ? report.strategic().trade().sideB() : report.strategic().trade().sideA();

        var flexibleLoss = TradeFlexibleCoverageMaterialLossAnalyzer.assess(
            context.flexible(), flexibleTeam, context.lineup(), context.depth(), outgoing, incoming);
        var transition = TradeFlexiblePressureTransitionAnalyzer.assess(
            context, flexibleTeam, outgoing, incoming);
        var veto = TradeStrategicFlexibleTransitionMaterialLossVetoDetector.assess(
            team, positional, flexibleLoss, transition, outgoing, incoming);

        return new SideVetting(
            side,
            team.identity().teamId(),
            team.identity().teamName(),
            veto.state(),
            veto.reasons());
    }

    static VettingState combine(SideVetting sideA, SideVetting sideB) {
        Objects.requireNonNull(sideA, "sideA must not be null");
        Objects.requireNonNull(sideB, "sideB must not be null");
        return sideA.vetoState() == TradeRecommendationVetoPolicy.VetoState.BLOCKED
            || sideB.vetoState() == TradeRecommendationVetoPolicy.VetoState.BLOCKED
            ? VettingState.BLOCKED
            : VettingState.CLEAR;
    }

    private static List<String> playerIds(TradeAssetAnalyzer.TradeSide side) {
        return side.players().stream().map(TradeAssetAnalyzer.TradePlayer::playerId).toList();
    }

    private static List<String> pickIds(TradeAssetAnalyzer.TradeSide side) {
        return side.draftPicks().stream().map(TradeAssetAnalyzer.TradeDraftPick::draftPickId).toList();
    }

    private static String missingEvidence(EvidenceStatus status) {
        List<String> missing = new ArrayList<>();
        if (!status.marketDirectionAvailable()) missing.add("market direction");
        if (!status.postureAvailable()) missing.add("team posture");
        if (!status.futureCapitalAvailable()) missing.add("future capital");
        if (!status.positionalPressureAvailable()) missing.add("positional pressure");
        if (!status.flexiblePressureAvailable()) missing.add("flexible pressure");
        return String.join(", ", missing);
    }

    private static StrategicCandidateReport available(
        TradeAssetAnalyzer.TradeReport trade,
        int season,
        List<StrategicCandidate> candidates) {
        return new StrategicCandidateReport(
            POLICY_ID,
            TradeCounterSingleAssetCandidateAnalyzer.POLICY_ID,
            TradeStrategicFlexibleTransitionMaterialLossVetoDetector.POLICY_ID,
            trade.leagueId(),
            season,
            trade.source(),
            trade.minimumAsOfDate(),
            true,
            null,
            candidates);
    }

    private static StrategicCandidateReport unavailable(
        TradeAssetAnalyzer.TradeReport trade,
        int season,
        String reason) {
        return new StrategicCandidateReport(
            POLICY_ID,
            TradeCounterSingleAssetCandidateAnalyzer.POLICY_ID,
            TradeStrategicFlexibleTransitionMaterialLossVetoDetector.POLICY_ID,
            trade.leagueId(),
            season,
            trade.source(),
            trade.minimumAsOfDate(),
            false,
            requireText(reason, "reason"),
            List.of());
    }

    private static int requireSeason(int season) {
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be a year between 1999 and 2100: " + season);
        }
        return season;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum VettingState { CLEAR, BLOCKED }
    public enum Side { SIDE_A, SIDE_B }

    public record ModifiedPackages(
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB) {
        public ModifiedPackages {
            Objects.requireNonNull(sideA, "sideA must not be null");
            Objects.requireNonNull(sideB, "sideB must not be null");
        }
    }

    public record EvidenceStatus(
        boolean marketDirectionAvailable,
        boolean postureAvailable,
        boolean futureCapitalAvailable,
        boolean positionalPressureAvailable,
        boolean flexiblePressureAvailable) {
        public boolean complete() {
            return marketDirectionAvailable && postureAvailable && futureCapitalAvailable
                && positionalPressureAvailable && flexiblePressureAvailable;
        }
    }

    public record SideVetting(
        Side side,
        String teamId,
        String teamName,
        TradeRecommendationVetoPolicy.VetoState vetoState,
        List<TradeStrategicFlexibleTransitionMaterialLossVetoDetector.VetoReason> reasons) {
        public SideVetting {
            Objects.requireNonNull(side, "side must not be null");
            teamId = requireText(teamId, "teamId");
            teamName = requireText(teamName, "teamName");
            Objects.requireNonNull(vetoState, "vetoState must not be null");
            reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons must not be null"));
            if (vetoState == TradeRecommendationVetoPolicy.VetoState.CLEAR && !reasons.isEmpty()) {
                throw new IllegalArgumentException("clear side vetting cannot carry veto reasons");
            }
            if (vetoState == TradeRecommendationVetoPolicy.VetoState.BLOCKED && reasons.isEmpty()) {
                throw new IllegalArgumentException("blocked side vetting requires veto reasons");
            }
        }
    }

    public record StrategicCandidate(
        int marketRank,
        TradeCounterSingleAssetCandidateAnalyzer.Candidate candidate,
        VettingState state,
        SideVetting sideA,
        SideVetting sideB) {
        public StrategicCandidate {
            if (marketRank < 1) throw new IllegalArgumentException("marketRank must be positive");
            Objects.requireNonNull(candidate, "candidate must not be null");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(sideA, "sideA must not be null");
            Objects.requireNonNull(sideB, "sideB must not be null");
            if (sideA.side() != Side.SIDE_A || sideB.side() != Side.SIDE_B) {
                throw new IllegalArgumentException("strategic candidate side vetting is misordered");
            }
            if (state != combine(sideA, sideB)) {
                throw new IllegalArgumentException("strategic candidate state must match bilateral veto state");
            }
        }
    }

    public record StrategicCandidateReport(
        String policyId,
        String marketCandidatePolicyId,
        String strategicVetoPolicyId,
        String leagueId,
        int season,
        String source,
        LocalDate minimumAsOfDate,
        boolean available,
        String insufficiencyReason,
        List<StrategicCandidate> candidates) {
        public StrategicCandidateReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!TradeCounterSingleAssetCandidateAnalyzer.POLICY_ID.equals(marketCandidatePolicyId)) {
                throw new IllegalArgumentException("unexpected marketCandidatePolicyId");
            }
            if (!TradeStrategicFlexibleTransitionMaterialLossVetoDetector.POLICY_ID.equals(strategicVetoPolicyId)) {
                throw new IllegalArgumentException("unexpected strategicVetoPolicyId");
            }
            leagueId = requireText(leagueId, "leagueId");
            requireSeason(season);
            source = requireText(source, "source");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
            if (available) {
                if (insufficiencyReason != null) {
                    throw new IllegalArgumentException("available strategic candidate report cannot carry insufficiency reason");
                }
            } else {
                if (insufficiencyReason == null || insufficiencyReason.isBlank()) {
                    throw new IllegalArgumentException("unavailable strategic candidate report requires insufficiency reason");
                }
                if (!candidates.isEmpty()) {
                    throw new IllegalArgumentException("unavailable strategic candidate report cannot carry candidates");
                }
            }
        }
    }
}
