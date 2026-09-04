package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Measures whether a selected trade team moves into league-relative FLEXIBLE_PRESSURE after the
 * trade and whether the accompanying legal flexible coverage loss exceeds the governed materiality
 * threshold. This is evidence only; it does not emit a recommendation or veto.
 */
public final class TradeFlexiblePressureTransitionAnalyzer {
    public static final String POLICY_ID =
        "trade-flexible-pressure-transition-v1-post-trade-league-relative";
    private static final double VALUE_TOLERANCE = 1e-9;

    private TradeFlexiblePressureTransitionAnalyzer() {}

    public enum AssessmentState {
        NO_FLEXIBLE_REQUIREMENT,
        INSUFFICIENT_EVIDENCE,
        NO_TRANSITION,
        TRANSITION_WITHIN_TOLERANCE,
        MATERIAL_TRANSITION_TO_PRESSURE
    }

    public static Assessment assess(
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

        var preTier = teamContext.pressure().tier();
        if (!context.flexible().flexiblePressureAvailable()) {
            if (preTier != LeagueFlexibleSlotPressurePolicy.Tier.INSUFFICIENT_EVIDENCE) {
                throw new IllegalStateException("unavailable flexible pressure contains classified trade-team tier");
            }
            return Assessment.insufficient(context.flexible().flexiblePressureInsufficiencyReason());
        }
        if (preTier == LeagueFlexibleSlotPressurePolicy.Tier.INSUFFICIENT_EVIDENCE) {
            throw new IllegalStateException("available flexible pressure contains insufficient trade-team tier");
        }
        if (context.flexible().flexSlots() + context.flexible().superFlexSlots() == 0) {
            if (preTier != LeagueFlexibleSlotPressurePolicy.Tier.NO_FLEXIBLE_REQUIREMENT) {
                throw new IllegalStateException("zero flexible exposure requires NO_FLEXIBLE_REQUIREMENT tier");
            }
            return Assessment.noFlexibleRequirement(preTier);
        }
        if (preTier == LeagueFlexibleSlotPressurePolicy.Tier.NO_FLEXIBLE_REQUIREMENT) {
            throw new IllegalStateException("active flexible exposure cannot carry NO_FLEXIBLE_REQUIREMENT tier");
        }

        var postDepth = TradeFlexiblePostTradeDepthAnalyzer.apply(
            context, teamContext, outgoing, incoming);
        var postCoverage = LeagueFlexibleSlotCoverageAnalyzer.compose(context.lineup(), postDepth.leagueDepth());
        var postPressure = LeagueFlexibleSlotPressureAnalyzer.classify(postCoverage);
        if (!postPressure.available()) {
            return Assessment.insufficient(postPressure.insufficiencyReason());
        }
        var postTeam = postPressure.teams().stream()
            .filter(team -> team.teamId().equals(teamContext.identity().teamId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "post-trade flexible pressure missing selected team: " + teamContext.identity().teamId()));
        if (!postTeam.teamName().equals(teamContext.identity().teamName())) {
            throw new IllegalStateException("post-trade flexible pressure team-name mismatch");
        }

        double preCoverage = teamContext.pressure().flexibleCoverageValue();
        double postCoverageValue = postTeam.flexibleCoverageValue();
        var flow = new TradeProtectedValueFlowAnalyzer.ValueFlow(preCoverage, postCoverageValue);
        var materiality = TradeProtectedValueMaterialityPolicy.classify(flow);
        boolean transition = preTier != LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE
            && postTeam.tier() == LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE;
        AssessmentState state = !transition
            ? AssessmentState.NO_TRANSITION
            : materiality == TradeProtectedValueMaterialityPolicy.Classification.MATERIAL_LOSS
                ? AssessmentState.MATERIAL_TRANSITION_TO_PRESSURE
                : AssessmentState.TRANSITION_WITHIN_TOLERANCE;
        return new Assessment(
            POLICY_ID,
            TradeFlexiblePostTradeDepthAnalyzer.POLICY_ID,
            TradeProtectedValueMaterialityPolicy.POLICY_ID,
            state,
            null,
            preTier,
            postTeam.tier(),
            preCoverage,
            postCoverageValue,
            flow.lossFraction());
    }

    public record Assessment(
        String policyId,
        String postTradeDepthPolicyId,
        String materialityPolicyId,
        AssessmentState state,
        String insufficiencyReason,
        LeagueFlexibleSlotPressurePolicy.Tier preTradeTier,
        LeagueFlexibleSlotPressurePolicy.Tier postTradeTier,
        Double preTradeCoverageValue,
        Double postTradeCoverageValue,
        Double lossFraction) {
        public Assessment {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!TradeFlexiblePostTradeDepthAnalyzer.POLICY_ID.equals(postTradeDepthPolicyId)) {
                throw new IllegalArgumentException("unexpected postTradeDepthPolicyId");
            }
            if (!TradeProtectedValueMaterialityPolicy.POLICY_ID.equals(materialityPolicyId)) {
                throw new IllegalArgumentException("unexpected materialityPolicyId");
            }
            Objects.requireNonNull(state, "state must not be null");
            if (state == AssessmentState.INSUFFICIENT_EVIDENCE) {
                if (insufficiencyReason == null || insufficiencyReason.isBlank()) {
                    throw new IllegalArgumentException("insufficient transition assessment requires reason");
                }
                if (preTradeTier != LeagueFlexibleSlotPressurePolicy.Tier.INSUFFICIENT_EVIDENCE
                    || postTradeTier != null
                    || preTradeCoverageValue != null || postTradeCoverageValue != null || lossFraction != null) {
                    throw new IllegalArgumentException("invalid insufficient transition assessment");
                }
                return;
            }
            if (insufficiencyReason != null) {
                throw new IllegalArgumentException("available transition assessment cannot carry insufficiency reason");
            }
            Objects.requireNonNull(preTradeTier, "preTradeTier must not be null");
            if (state == AssessmentState.NO_FLEXIBLE_REQUIREMENT) {
                if (preTradeTier != LeagueFlexibleSlotPressurePolicy.Tier.NO_FLEXIBLE_REQUIREMENT
                    || postTradeTier != LeagueFlexibleSlotPressurePolicy.Tier.NO_FLEXIBLE_REQUIREMENT
                    || preTradeCoverageValue != null || postTradeCoverageValue != null || lossFraction != null) {
                    throw new IllegalArgumentException("invalid no-flexible-requirement transition assessment");
                }
                return;
            }
            Objects.requireNonNull(postTradeTier, "postTradeTier must not be null");
            validateMeasured(preTradeCoverageValue, postTradeCoverageValue, lossFraction);
            boolean transitioned = preTradeTier != LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE
                && postTradeTier == LeagueFlexibleSlotPressurePolicy.Tier.FLEXIBLE_PRESSURE;
            if (state == AssessmentState.NO_TRANSITION && transitioned) {
                throw new IllegalArgumentException("NO_TRANSITION cannot carry transition-to-pressure tiers");
            }
            if (state != AssessmentState.NO_TRANSITION && !transitioned) {
                throw new IllegalArgumentException("transition state requires move into FLEXIBLE_PRESSURE");
            }
            var flow = new TradeProtectedValueFlowAnalyzer.ValueFlow(preTradeCoverageValue, postTradeCoverageValue);
            var classification = TradeProtectedValueMaterialityPolicy.classify(flow);
            if (state == AssessmentState.MATERIAL_TRANSITION_TO_PRESSURE
                && classification != TradeProtectedValueMaterialityPolicy.Classification.MATERIAL_LOSS) {
                throw new IllegalArgumentException("material transition requires material coverage loss");
            }
            if (state == AssessmentState.TRANSITION_WITHIN_TOLERANCE
                && classification == TradeProtectedValueMaterialityPolicy.Classification.MATERIAL_LOSS) {
                throw new IllegalArgumentException("within-tolerance transition cannot carry material coverage loss");
            }
        }

        public boolean available() {
            return state != AssessmentState.INSUFFICIENT_EVIDENCE;
        }

        public boolean transitionedToPressure() {
            return state == AssessmentState.TRANSITION_WITHIN_TOLERANCE
                || state == AssessmentState.MATERIAL_TRANSITION_TO_PRESSURE;
        }

        public boolean materialTransitionToPressure() {
            return state == AssessmentState.MATERIAL_TRANSITION_TO_PRESSURE;
        }

        static Assessment insufficient(String reason) {
            return new Assessment(
                POLICY_ID,
                TradeFlexiblePostTradeDepthAnalyzer.POLICY_ID,
                TradeProtectedValueMaterialityPolicy.POLICY_ID,
                AssessmentState.INSUFFICIENT_EVIDENCE,
                reason,
                LeagueFlexibleSlotPressurePolicy.Tier.INSUFFICIENT_EVIDENCE,
                null,
                null,
                null,
                null);
        }

        static Assessment noFlexibleRequirement(LeagueFlexibleSlotPressurePolicy.Tier tier) {
            return new Assessment(
                POLICY_ID,
                TradeFlexiblePostTradeDepthAnalyzer.POLICY_ID,
                TradeProtectedValueMaterialityPolicy.POLICY_ID,
                AssessmentState.NO_FLEXIBLE_REQUIREMENT,
                null,
                tier,
                tier,
                null,
                null,
                null);
        }

        private static void validateMeasured(Double before, Double after, Double lossFraction) {
            if (before == null || after == null || lossFraction == null
                || !Double.isFinite(before) || before < 0.0
                || !Double.isFinite(after) || after < 0.0
                || !Double.isFinite(lossFraction) || lossFraction < 0.0 || lossFraction > 1.0) {
                throw new IllegalArgumentException("measured transition requires valid coverage values");
            }
            var flow = new TradeProtectedValueFlowAnalyzer.ValueFlow(before, after);
            if (Math.abs(flow.lossFraction() - lossFraction) > VALUE_TOLERANCE) {
                throw new IllegalArgumentException("lossFraction must match transition coverage values");
            }
        }
    }
}
