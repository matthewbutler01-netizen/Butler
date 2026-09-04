package io.butler.bet.intelligence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Detects only explicit governed strategic conflicts that are strong enough to veto a directional
 * market recommendation. No weights, scores, inferred age strategy, or posture-only vetoes are used.
 */
public final class TradeStrategicVetoDetector {
    public static final String POLICY_ID = "trade-strategic-veto-v1-explicit-weakness-protection";
    private static final List<String> CORE_POSITIONS = List.of("QB", "RB", "WR", "TE");

    private TradeStrategicVetoDetector() {}

    public enum ReasonCode {
        LOW_FUTURE_CAPITAL_OUTGOING_PICKS_WITHOUT_PICK_RETURN,
        POSITION_PRESSURE_OUTGOING_WITHOUT_SAME_POSITION_RETURN
    }

    public record VetoReason(ReasonCode code, String position) {
        public VetoReason {
            Objects.requireNonNull(code, "code must not be null");
            if (code == ReasonCode.POSITION_PRESSURE_OUTGOING_WITHOUT_SAME_POSITION_RETURN) {
                if (position == null || position.isBlank()) {
                    throw new IllegalArgumentException("position-pressure veto requires position");
                }
                position = position.trim().toUpperCase(Locale.ROOT);
            } else if (position != null) {
                throw new IllegalArgumentException("future-capital veto must not carry position");
            }
        }
    }

    public record VetoAssessment(TradeRecommendationVetoPolicy.VetoState state, List<VetoReason> reasons) {
        public VetoAssessment {
            Objects.requireNonNull(state, "state must not be null");
            reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons must not be null"));
            if (state == TradeRecommendationVetoPolicy.VetoState.CLEAR && !reasons.isEmpty()) {
                throw new IllegalArgumentException("clear veto assessment cannot contain reasons");
            }
            if (state == TradeRecommendationVetoPolicy.VetoState.BLOCKED && reasons.isEmpty()) {
                throw new IllegalArgumentException("blocked veto assessment requires reasons");
            }
        }
    }

    public static VetoAssessment assess(
        TradeAssetStrategicContextAnalyzer.TeamStrategicContext team,
        TradeAssetPositionalContextAnalyzer.TeamPositionalContext positional,
        TradeAssetAnalyzer.TradeSide outgoing,
        TradeAssetAnalyzer.TradeSide incoming) {
        Objects.requireNonNull(team, "team must not be null");
        Objects.requireNonNull(positional, "positional must not be null");
        Objects.requireNonNull(outgoing, "outgoing must not be null");
        Objects.requireNonNull(incoming, "incoming must not be null");
        if (!team.identity().equals(positional.identity())) {
            throw new IllegalArgumentException("strategic and positional veto context must reference the same team");
        }

        List<VetoReason> reasons = new ArrayList<>();
        if (team.futureCapital().tier() == LeagueFutureCapitalTierPolicy.Tier.LOW_FUTURE_CAPITAL
            && !outgoing.draftPicks().isEmpty() && incoming.draftPicks().isEmpty()) {
            reasons.add(new VetoReason(ReasonCode.LOW_FUTURE_CAPITAL_OUTGOING_PICKS_WITHOUT_PICK_RETURN, null));
        }

        for (String position : CORE_POSITIONS) {
            var pressure = positional.positions().get(position);
            if (pressure.tier() != LeaguePositionalPressurePolicy.Tier.POSITION_PRESSURE) continue;
            boolean sendsPosition = outgoing.players().stream()
                .anyMatch(player -> position.equalsIgnoreCase(player.position()));
            boolean receivesPosition = incoming.players().stream()
                .anyMatch(player -> position.equalsIgnoreCase(player.position()));
            if (sendsPosition && !receivesPosition) {
                reasons.add(new VetoReason(
                    ReasonCode.POSITION_PRESSURE_OUTGOING_WITHOUT_SAME_POSITION_RETURN, position));
            }
        }

        return reasons.isEmpty()
            ? new VetoAssessment(TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of())
            : new VetoAssessment(TradeRecommendationVetoPolicy.VetoState.BLOCKED, reasons);
    }
}
