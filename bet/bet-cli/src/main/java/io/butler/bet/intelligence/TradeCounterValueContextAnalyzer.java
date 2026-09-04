package io.butler.bet.intelligence;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Attaches governed counter-value targets to complete, fresh trade market-value evidence.
 * Missing or stale values fail closed; this remains evidence only and does not emit COUNTER or
 * modify the live v5 recommendation path.
 */
public final class TradeCounterValueContextAnalyzer {
    public static final String POLICY_ID = "trade-counter-value-context-v1-comparable-market-evidence";

    private TradeCounterValueContextAnalyzer() {}

    public static CounterValueContextReport compose(TradeAssetAnalyzer.TradeReport trade) {
        Objects.requireNonNull(trade, "trade must not be null");
        String leagueId = requireText(trade.leagueId(), "trade.leagueId");
        String source = requireText(trade.source(), "trade.source");

        if (!trade.complete()) {
            return unavailable(
                leagueId,
                source,
                trade.minimumAsOfDate(),
                "Trade counter value target requires complete market-value coverage.");
        }
        if (!trade.fresh()) {
            return unavailable(
                leagueId,
                source,
                trade.minimumAsOfDate(),
                "Trade counter value target requires fresh market-value evidence.");
        }

        var target = TradeCounterValueTargetAnalyzer.analyze(
            trade.sideA().totalValue(),
            trade.sideB().totalValue());
        return new CounterValueContextReport(
            POLICY_ID,
            TradeCounterValueTargetAnalyzer.POLICY_ID,
            leagueId,
            source,
            trade.minimumAsOfDate(),
            true,
            null,
            target);
    }

    private static CounterValueContextReport unavailable(
        String leagueId,
        String source,
        LocalDate minimumAsOfDate,
        String reason) {
        return new CounterValueContextReport(
            POLICY_ID,
            TradeCounterValueTargetAnalyzer.POLICY_ID,
            leagueId,
            source,
            minimumAsOfDate,
            false,
            reason,
            null);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public record CounterValueContextReport(
        String policyId,
        String targetPolicyId,
        String leagueId,
        String source,
        LocalDate minimumAsOfDate,
        boolean available,
        String insufficiencyReason,
        TradeCounterValueTargetAnalyzer.CounterValueTarget target) {
        public CounterValueContextReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!TradeCounterValueTargetAnalyzer.POLICY_ID.equals(targetPolicyId)) {
                throw new IllegalArgumentException("unexpected targetPolicyId");
            }
            leagueId = requireText(leagueId, "leagueId");
            source = requireText(source, "source");
            if (available) {
                if (insufficiencyReason != null) {
                    throw new IllegalArgumentException("available counter context cannot carry insufficiency reason");
                }
                Objects.requireNonNull(target, "available counter context requires target");
            } else {
                if (insufficiencyReason == null || insufficiencyReason.isBlank()) {
                    throw new IllegalArgumentException("unavailable counter context requires insufficiency reason");
                }
                if (target != null) {
                    throw new IllegalArgumentException("unavailable counter context cannot carry target");
                }
            }
        }
    }
}
