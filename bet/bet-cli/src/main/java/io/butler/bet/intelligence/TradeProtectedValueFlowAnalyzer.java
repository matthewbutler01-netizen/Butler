package io.butler.bet.intelligence;

import java.util.Locale;
import java.util.Objects;

/**
 * Computes neutral protected-asset value flow for future picks and same-position players.
 * This class does not decide whether any loss is material and does not emit a veto.
 */
public final class TradeProtectedValueFlowAnalyzer {
    public static final String POLICY_ID = "trade-protected-value-flow-v1-current-valued-assets";

    private TradeProtectedValueFlowAnalyzer() {}

    public static ValueFlow draftPickFlow(TradeAssetAnalyzer.TradeSide outgoing,
                                          TradeAssetAnalyzer.TradeSide incoming) {
        Objects.requireNonNull(outgoing, "outgoing must not be null");
        Objects.requireNonNull(incoming, "incoming must not be null");
        return new ValueFlow(
            outgoing.draftPicks().stream().mapToDouble(TradeProtectedValueFlowAnalyzer::value).sum(),
            incoming.draftPicks().stream().mapToDouble(TradeProtectedValueFlowAnalyzer::value).sum());
    }

    public static ValueFlow positionFlow(String position,
                                         TradeAssetAnalyzer.TradeSide outgoing,
                                         TradeAssetAnalyzer.TradeSide incoming) {
        String normalized = normalizePosition(position);
        Objects.requireNonNull(outgoing, "outgoing must not be null");
        Objects.requireNonNull(incoming, "incoming must not be null");
        return new ValueFlow(
            outgoing.players().stream()
                .filter(player -> normalized.equalsIgnoreCase(player.position()))
                .mapToDouble(TradeProtectedValueFlowAnalyzer::value)
                .sum(),
            incoming.players().stream()
                .filter(player -> normalized.equalsIgnoreCase(player.position()))
                .mapToDouble(TradeProtectedValueFlowAnalyzer::value)
                .sum());
    }

    private static double value(TradeAssetAnalyzer.TradePlayer player) {
        if (player.stale()) throw new IllegalArgumentException("protected player value must be current: " + player.playerId());
        if (player.value() == null || !Double.isFinite(player.value()) || player.value() < 0.0) {
            throw new IllegalArgumentException("protected player value must be finite and non-negative: " + player.playerId());
        }
        return player.value();
    }

    private static double value(TradeAssetAnalyzer.TradeDraftPick pick) {
        if (pick.stale()) throw new IllegalArgumentException("protected draft-pick value must be current: " + pick.draftPickId());
        if (pick.value() == null || !Double.isFinite(pick.value()) || pick.value() < 0.0) {
            throw new IllegalArgumentException("protected draft-pick value must be finite and non-negative: " + pick.draftPickId());
        }
        return pick.value();
    }

    private static String normalizePosition(String position) {
        if (position == null || position.isBlank()) throw new IllegalArgumentException("position must not be blank");
        return position.trim().toUpperCase(Locale.ROOT);
    }

    public record ValueFlow(double outgoingValue, double incomingValue) {
        public ValueFlow {
            if (!Double.isFinite(outgoingValue) || outgoingValue < 0.0) {
                throw new IllegalArgumentException("outgoingValue must be finite and non-negative");
            }
            if (!Double.isFinite(incomingValue) || incomingValue < 0.0) {
                throw new IllegalArgumentException("incomingValue must be finite and non-negative");
            }
        }

        public double netLoss() {
            return Math.max(0.0, outgoingValue - incomingValue);
        }

        public double lossFraction() {
            return outgoingValue == 0.0 ? 0.0 : netLoss() / outgoingValue;
        }
    }
}
