package io.butler.bet.integration.sleeper;

import java.util.List;
import java.util.Objects;

/**
 * Pure governance mapping from read-only Sleeper trade reconciliation evidence to terminal-outcome
 * eligibility. It never mutates an execution attempt or authorization grant.
 */
public final class SleeperCounterTradeReconciliationOutcomePolicy {
    public static final String POLICY_ID =
        "sleeper-counter-trade-reconciliation-outcome-v1-complete-only-success-no-negative-inference";

    private SleeperCounterTradeReconciliationOutcomePolicy() {}

    public static Decision classify(SleeperCounterTradeSnapshotReconciliationService.Report report) {
        Objects.requireNonNull(report, "report must not be null");
        if (!SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID.equals(report.serviceId())) {
            throw new IllegalArgumentException("unexpected reconciliation service provenance");
        }

        if (report.state() == SleeperCounterTradeSnapshotReconciliationService.State.NOT_AVAILABLE) {
            return decision(
                report,
                State.INCONCLUSIVE,
                ReasonCode.TRUSTED_RECONCILIATION_UNAVAILABLE,
                TerminalOutcomeEligibility.NONE,
                List.of(),
                "Trusted reconciliation evidence is unavailable; no execution outcome may be inferred.");
        }

        var reconciliation = Objects.requireNonNull(
            report.reconciliation(), "RECONCILED report requires reconciliation evidence");
        if (!SleeperTradeReconciliationPolicy.POLICY_ID.equals(reconciliation.policyId())) {
            throw new IllegalArgumentException("unexpected Sleeper reconciliation policy provenance");
        }

        return switch (reconciliation.state()) {
            case MATCH_COMPLETE -> decision(
                report,
                State.CONFIRMED_SUCCESS_EVIDENCE,
                ReasonCode.EXACT_COMPLETE_TRADE_CONFIRMED,
                TerminalOutcomeEligibility.CONFIRMED_SUCCESS,
                reconciliation.matchingTransactionIds(),
                "Exactly one complete Sleeper trade matched the frozen handoff coordinates after the presentation boundary; this is eligible confirmed-success evidence.");
            case MATCH_PENDING -> decision(
                report,
                State.PENDING,
                ReasonCode.EXACT_TRADE_PENDING,
                TerminalOutcomeEligibility.NONE,
                reconciliation.matchingTransactionIds(),
                "An exact Sleeper trade is pending; Butler must not finalize success or failure while the platform transaction remains pending.");
            case NO_MATCH -> decision(
                report,
                State.NO_TERMINAL_OUTCOME,
                ReasonCode.NO_EXACT_TRADE_OBSERVED,
                TerminalOutcomeEligibility.NONE,
                List.of(),
                "No exact trade was observed in the explicitly requested Sleeper week after handoff; absence is not proof of remote failure and creates no terminal outcome.");
            case AMBIGUOUS -> decision(
                report,
                State.INCONCLUSIVE,
                ReasonCode.AMBIGUOUS_EXACT_TRADES,
                TerminalOutcomeEligibility.NONE,
                reconciliation.matchingTransactionIds(),
                "Multiple exact Sleeper trades matched the frozen coordinates; Butler must not choose one or infer a terminal execution outcome.");
            case INCONCLUSIVE -> decision(
                report,
                State.INCONCLUSIVE,
                ReasonCode.RECONCILIATION_EVIDENCE_INCONCLUSIVE,
                TerminalOutcomeEligibility.NONE,
                reconciliation.matchingTransactionIds(),
                "Sleeper reconciliation evidence is incomplete or unsupported; no terminal execution outcome may be inferred.");
        };
    }

    private static Decision decision(
        SleeperCounterTradeSnapshotReconciliationService.Report report,
        State state,
        ReasonCode reasonCode,
        TerminalOutcomeEligibility terminalOutcomeEligibility,
        List<String> transactionIds,
        String reason) {
        return new Decision(
            POLICY_ID,
            SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID,
            SleeperTradeReconciliationPolicy.POLICY_ID,
            report.grantId(),
            report.claimId(),
            report.handoffId(),
            report.movementSha256(),
            report.week(),
            state,
            reasonCode,
            terminalOutcomeEligibility,
            transactionIds,
            reason);
    }

    public enum State {
        CONFIRMED_SUCCESS_EVIDENCE,
        PENDING,
        NO_TERMINAL_OUTCOME,
        INCONCLUSIVE
    }

    public enum ReasonCode {
        EXACT_COMPLETE_TRADE_CONFIRMED,
        EXACT_TRADE_PENDING,
        NO_EXACT_TRADE_OBSERVED,
        AMBIGUOUS_EXACT_TRADES,
        RECONCILIATION_EVIDENCE_INCONCLUSIVE,
        TRUSTED_RECONCILIATION_UNAVAILABLE
    }

    public enum TerminalOutcomeEligibility {
        CONFIRMED_SUCCESS,
        NONE
    }

    public record Decision(
        String policyId,
        String reconciliationServiceId,
        String reconciliationPolicyId,
        String grantId,
        String claimId,
        String handoffId,
        String movementSha256,
        int week,
        State state,
        ReasonCode reasonCode,
        TerminalOutcomeEligibility terminalOutcomeEligibility,
        List<String> transactionIds,
        String reason) {
        public Decision {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!SleeperCounterTradeSnapshotReconciliationService.SERVICE_ID.equals(reconciliationServiceId)) {
                throw new IllegalArgumentException("unexpected reconciliationServiceId");
            }
            if (!SleeperTradeReconciliationPolicy.POLICY_ID.equals(reconciliationPolicyId)) {
                throw new IllegalArgumentException("unexpected reconciliationPolicyId");
            }
            grantId = requireText(grantId, "grantId");
            if (week < 1 || week > 30) throw new IllegalArgumentException("week must be from 1 through 30");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            Objects.requireNonNull(terminalOutcomeEligibility, "terminalOutcomeEligibility must not be null");
            transactionIds = List.copyOf(Objects.requireNonNull(transactionIds, "transactionIds must not be null"));
            reason = requireText(reason, "reason");

            if (state == State.CONFIRMED_SUCCESS_EVIDENCE) {
                requireText(claimId, "claimId");
                requireText(handoffId, "handoffId");
                requireFingerprint(movementSha256, "movementSha256");
                if (reasonCode != ReasonCode.EXACT_COMPLETE_TRADE_CONFIRMED
                    || terminalOutcomeEligibility != TerminalOutcomeEligibility.CONFIRMED_SUCCESS
                    || transactionIds.size() != 1) {
                    throw new IllegalArgumentException("confirmed success evidence requires one exact complete transaction");
                }
            } else {
                if (terminalOutcomeEligibility != TerminalOutcomeEligibility.NONE) {
                    throw new IllegalArgumentException("only confirmed success evidence may be terminal-outcome eligible");
                }
                if (state == State.PENDING) {
                    requireText(claimId, "claimId");
                    requireText(handoffId, "handoffId");
                    requireFingerprint(movementSha256, "movementSha256");
                    if (reasonCode != ReasonCode.EXACT_TRADE_PENDING || transactionIds.size() != 1) {
                        throw new IllegalArgumentException("PENDING requires exactly one pending transaction");
                    }
                }
                if (state == State.NO_TERMINAL_OUTCOME
                    && (reasonCode != ReasonCode.NO_EXACT_TRADE_OBSERVED || !transactionIds.isEmpty())) {
                    throw new IllegalArgumentException("NO_TERMINAL_OUTCOME requires NO_MATCH with no transaction IDs");
                }
                if (reasonCode == ReasonCode.TRUSTED_RECONCILIATION_UNAVAILABLE) {
                    if (claimId != null || handoffId != null || movementSha256 != null || !transactionIds.isEmpty()) {
                        throw new IllegalArgumentException("unavailable reconciliation cannot carry trusted read evidence coordinates");
                    }
                } else {
                    requireText(claimId, "claimId");
                    requireText(handoffId, "handoffId");
                    requireFingerprint(movementSha256, "movementSha256");
                }
            }
        }
    }

    private static void requireFingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
