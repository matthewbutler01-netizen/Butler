package io.butler.bet.intelligence;

import java.util.List;
import java.util.Objects;

/**
 * Recomputes legal FLEX/SUPERFLEX coverage after a trade for a team already under flexible pressure.
 * The same lineup reservation, flexible-slot optimizer, and shared post-trade roster mutation used
 * by transition evidence is reused here while preserving the selected-team-only v4 contract.
 * This analyzer measures evidence only; it does not emit a recommendation or veto.
 */
public final class TradeFlexibleCoverageMaterialLossAnalyzer {
    public static final String POLICY_ID = "trade-flexible-coverage-loss-v1-post-trade-legal-lineup";
    private static final double VALUE_TOLERANCE = 1e-9;

    private TradeFlexibleCoverageMaterialLossAnalyzer() {}

    public enum AssessmentState {
        NOT_PROTECTED,
        INSUFFICIENT_EVIDENCE,
        WITHIN_TOLERANCE,
        MATERIAL_LOSS
    }

    public static Assessment assess(
        TradeFlexibleSlotContextAnalyzer.TradeFlexibleContextReport context,
        TradeFlexibleSlotContextAnalyzer.TeamFlexibleContext teamContext,
        LeagueLineupRequirementsAnalyzer.LineupRequirementsReport lineup,
        LeaguePositionalDepthAnalyzer.DepthReport depth,
        TradeAssetAnalyzer.TradeSide outgoing,
        TradeAssetAnalyzer.TradeSide incoming) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(teamContext, "teamContext must not be null");
        Objects.requireNonNull(lineup, "lineup must not be null");
        Objects.requireNonNull(depth, "depth must not be null");
        Objects.requireNonNull(outgoing, "outgoing must not be null");
        Objects.requireNonNull(incoming, "incoming must not be null");

        validateCoordinates(context, teamContext, lineup, depth);
        var tier = teamContext.pressure().tier();
        if (!context.flexiblePressureAvailable()) {
            if (tier != LeagueFlexibleSlotPressurePolicy.Tier.INSUFFICIENT_EVIDENCE) {
                throw new IllegalStateException("unavailable flexible pressure contains classified trade-team tier");
            }
            return Assessment.insufficient(context.flexiblePressureInsufficiencyReason());
        }
        if (tier == LeagueFlexibleSlotPressurePolicy.Tier.INSUFFICIENT_EVIDENCE) {
            throw new IllegalStateException("available flexible pressure contains insufficient trade-team tier");
        }
        if (tier != LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE) {
            return Assessment.notProtected();
        }

        var identity = teamContext.identity();
        var currentTeam = depth.teams().stream()
            .filter(team -> team.teamId().equals(identity.teamId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "positional depth missing for flexible-pressure trade team: " + identity.teamId()));
        if (!currentTeam.teamName().equals(identity.teamName())) {
            throw new IllegalStateException("trade and positional-depth team names differ: " + identity.teamId());
        }

        var baseline = coverageForTeam(context, lineup, currentTeam);
        validateBaseline(teamContext.pressure(), baseline);
        var postTradeTeam = TradeFlexiblePostTradeDepthAnalyzer.applySelectedTeam(
            context, depth, teamContext, outgoing, incoming);
        var postTrade = coverageForTeam(context, lineup, postTradeTeam);

        var flow = new TradeProtectedValueFlowAnalyzer.ValueFlow(
            baseline.flexibleCoverageValue(), postTrade.flexibleCoverageValue());
        var materiality = TradeProtectedValueMaterialityPolicy.classify(flow);
        return materiality == TradeProtectedValueMaterialityPolicy.Classification.MATERIAL_LOSS
            ? Assessment.materialLoss(baseline.flexibleCoverageValue(), postTrade.flexibleCoverageValue(), flow.lossFraction())
            : Assessment.withinTolerance(baseline.flexibleCoverageValue(), postTrade.flexibleCoverageValue(), flow.lossFraction());
    }

    private static void validateCoordinates(
        TradeFlexibleSlotContextAnalyzer.TradeFlexibleContextReport context,
        TradeFlexibleSlotContextAnalyzer.TeamFlexibleContext teamContext,
        LeagueLineupRequirementsAnalyzer.LineupRequirementsReport lineup,
        LeaguePositionalDepthAnalyzer.DepthReport depth) {
        boolean knownTeam = teamContext.equals(context.sideA()) || teamContext.equals(context.sideB());
        if (!knownTeam) {
            throw new IllegalArgumentException("team flexible context must belong to the trade context");
        }
        if (!context.leagueId().equals(lineup.leagueId()) || !context.leagueId().equals(depth.leagueId())) {
            throw new IllegalStateException("flexible material-loss inputs reference different leagues");
        }
        if (!context.source().equals(depth.source())) {
            throw new IllegalStateException("flexible material-loss inputs use different value sources");
        }
        if (!Objects.equals(context.minimumAsOfDate(), depth.minimumAsOfDate())) {
            throw new IllegalStateException("flexible material-loss inputs use different freshness boundaries");
        }
        if (context.flexSlots() != lineup.flexSlots() || context.superFlexSlots() != lineup.superFlexSlots()) {
            throw new IllegalStateException("flexible material-loss lineup exposure differs from trade context");
        }
    }

    private static LeagueFlexibleSlotCoverageAnalyzer.TeamFlexibleCoverage coverageForTeam(
        TradeFlexibleSlotContextAnalyzer.TradeFlexibleContextReport context,
        LeagueLineupRequirementsAnalyzer.LineupRequirementsReport lineup,
        LeaguePositionalDepthAnalyzer.TeamDepth team) {
        var singleTeamDepth = new LeaguePositionalDepthAnalyzer.DepthReport(
            context.leagueId(), context.source(), context.minimumAsOfDate(), List.of(team));
        var coverage = LeagueFlexibleSlotCoverageAnalyzer.compose(lineup, singleTeamDepth);
        if (!coverage.available()) {
            throw new IllegalStateException("trade-team flexible coverage unexpectedly unavailable: "
                + coverage.insufficiencyReason());
        }
        return coverage.teams().get(0);
    }

    private static void validateBaseline(
        LeagueFlexibleSlotPressureAnalyzer.TeamFlexiblePressure pressure,
        LeagueFlexibleSlotCoverageAnalyzer.TeamFlexibleCoverage baseline) {
        if (!pressure.teamId().equals(baseline.teamId()) || !pressure.teamName().equals(baseline.teamName())) {
            throw new IllegalStateException("flexible pressure and recomputed baseline identity mismatch");
        }
        if (pressure.flexibleSlots() != baseline.flexibleSlots()
            || pressure.flexibleCoveredSlots() != baseline.flexibleCoveredSlots()
            || pressure.flexibleUnfilledSlots() != baseline.flexibleUnfilledSlots()
            || Math.abs(pressure.flexibleCoverageValue() - baseline.flexibleCoverageValue()) > VALUE_TOLERANCE) {
            throw new IllegalStateException("recomputed baseline flexible coverage differs from governed pressure evidence");
        }
    }

    public record Assessment(
        String policyId,
        String materialityPolicyId,
        AssessmentState state,
        String insufficiencyReason,
        Double preTradeCoverageValue,
        Double postTradeCoverageValue,
        Double lossFraction) {
        public Assessment {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!TradeProtectedValueMaterialityPolicy.POLICY_ID.equals(materialityPolicyId)) {
                throw new IllegalArgumentException("unexpected materialityPolicyId");
            }
            Objects.requireNonNull(state, "state must not be null");
            if (state == AssessmentState.INSUFFICIENT_EVIDENCE) {
                if (insufficiencyReason == null || insufficiencyReason.isBlank()) {
                    throw new IllegalArgumentException("insufficient assessment requires reason");
                }
                if (preTradeCoverageValue != null || postTradeCoverageValue != null || lossFraction != null) {
                    throw new IllegalArgumentException("insufficient assessment cannot carry coverage values");
                }
            } else {
                if (insufficiencyReason != null) {
                    throw new IllegalArgumentException("available assessment cannot carry insufficiency reason");
                }
                boolean measured = state == AssessmentState.WITHIN_TOLERANCE || state == AssessmentState.MATERIAL_LOSS;
                if (measured) {
                    validateMeasured(preTradeCoverageValue, postTradeCoverageValue, lossFraction);
                } else if (preTradeCoverageValue != null || postTradeCoverageValue != null || lossFraction != null) {
                    throw new IllegalArgumentException("not-protected assessment cannot carry coverage values");
                }
            }
        }

        public boolean available() {
            return state != AssessmentState.INSUFFICIENT_EVIDENCE;
        }

        public boolean protectedPressureArea() {
            return state == AssessmentState.WITHIN_TOLERANCE || state == AssessmentState.MATERIAL_LOSS;
        }

        public boolean materialLoss() {
            return state == AssessmentState.MATERIAL_LOSS;
        }

        static Assessment notProtected() {
            return new Assessment(POLICY_ID, TradeProtectedValueMaterialityPolicy.POLICY_ID,
                AssessmentState.NOT_PROTECTED, null, null, null, null);
        }

        static Assessment insufficient(String reason) {
            return new Assessment(POLICY_ID, TradeProtectedValueMaterialityPolicy.POLICY_ID,
                AssessmentState.INSUFFICIENT_EVIDENCE, reason, null, null, null);
        }

        static Assessment withinTolerance(double before, double after, double lossFraction) {
            return new Assessment(POLICY_ID, TradeProtectedValueMaterialityPolicy.POLICY_ID,
                AssessmentState.WITHIN_TOLERANCE, null, before, after, lossFraction);
        }

        static Assessment materialLoss(double before, double after, double lossFraction) {
            return new Assessment(POLICY_ID, TradeProtectedValueMaterialityPolicy.POLICY_ID,
                AssessmentState.MATERIAL_LOSS, null, before, after, lossFraction);
        }

        private static void validateMeasured(Double before, Double after, Double lossFraction) {
            if (before == null || after == null || lossFraction == null
                || !Double.isFinite(before) || before < 0.0
                || !Double.isFinite(after) || after < 0.0
                || !Double.isFinite(lossFraction) || lossFraction < 0.0 || lossFraction > 1.0) {
                throw new IllegalArgumentException("measured assessment requires valid coverage values");
            }
            var flow = new TradeProtectedValueFlowAnalyzer.ValueFlow(before, after);
            if (Math.abs(flow.lossFraction() - lossFraction) > VALUE_TOLERANCE) {
                throw new IllegalArgumentException("lossFraction must match pre/post flexible coverage values");
            }
        }
    }
}
