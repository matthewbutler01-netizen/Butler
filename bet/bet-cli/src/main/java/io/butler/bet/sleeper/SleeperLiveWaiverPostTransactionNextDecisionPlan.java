package io.butler.bet.sleeper;

import java.util.List;
import java.util.Objects;

/** BF-640 instructions-only next governed decision cycle after BF-639 roster convergence. */
public final class SleeperLiveWaiverPostTransactionNextDecisionPlan {
    public static final String POLICY_ID =
        "sleeper-live-waiver-post-transaction-next-decision-plan-v1-bf638-bf639-explicit-operator-only-no-execution";

    private SleeperLiveWaiverPostTransactionNextDecisionPlan() {}

    public static PlanReport plan(
        SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport summary,
        SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceReport convergence) {
        Objects.requireNonNull(summary, "BF-640 summary must not be null");
        Objects.requireNonNull(convergence, "BF-640 convergence report must not be null");
        validate(summary, convergence);

        String leagueId = requireText(summary.leagueId(), "leagueId");
        if (summary.state()
            != SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_ALREADY_COMPLETE
            || convergence.state()
            != SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState.POST_TRANSACTION_ROSTER_CONVERGED) {
            return new PlanReport(POLICY_ID, leagueId, PlanState.NOT_REQUIRED, List.of());
        }

        List<PlanStep> steps = List.of(
            step(1, "BF-602", "sleeperLiveWaiverSnapshotSync", StepMode.BUTLER_WRITE, leagueId,
                "Persist a new immutable live waiver identity and league-eligibility snapshot from the converged post-transaction roster state."),
            step(2, "BF-603", "sleeperLiveWaiverMarketAttentionSync", StepMode.BUTLER_WRITE, leagueId,
                "Persist fresh add/drop market-attention evidence against the new BF-602 snapshot."),
            step(3, "BF-605", "sleeperLiveWaiverProductionHydration", StepMode.BUTLER_WRITE, leagueId,
                "Guardedly hydrate exact 2025 production for the new BF-603 candidate frame; unmatched production remains missing."),
            step(4, "BF-606", "sleeperLiveWaiverAvailabilitySync", StepMode.BUTLER_WRITE, leagueId,
                "Persist current availability and depth metadata for the exact new BF-603 frame."),
            step(5, "BF-607", "sleeperLiveWaiverCurrentWeekStatSync", StepMode.BUTLER_WRITE, leagueId,
                "Persist current-week raw-stat evidence tied to the new BF-603 and BF-606 snapshots."),
            step(6, "BF-612", "sleeperLiveWaiverTargetRosterProductionHydration", StepMode.BUTLER_WRITE, leagueId,
                "After the new BF-609/BF-610/BF-611 frame reconciles, hydrate only missing exact target-roster 2025 production."),
            step(7, "BF-618/BF-620", "sleeperLiveWaiverFinalRecommendationBundle", StepMode.READ_ONLY, leagueId,
                "Build the next governed comparison, selection, and final recommendation from the new post-transaction evidence frame."),
            step(8, "BF-627", "sleeperLiveWaiverRecommendationAuditCapture", StepMode.BUTLER_WRITE, leagueId,
                "Explicitly capture the next governed outcome as a new immutable Butler audit record."),
            step(9, "BF-629/BF-631/BF-633/BF-635/BF-638/BF-639", "sleeperLiveWaiverLatestGovernedDecisionSummary", StepMode.READ_ONLY, leagueId,
                "Revalidate the new latest audit for transaction-aware live actionability, evidence lineage, evidence age, lifecycle state, and post-transaction roster convergence when applicable."));
        return new PlanReport(POLICY_ID, leagueId, PlanState.NEXT_DECISION_PLAN_READY, steps);
    }

    private static void validate(
        SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport summary,
        SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceReport convergence) {
        if (!SleeperLiveWaiverLatestGovernedDecisionSummary.POLICY_ID.equals(summary.policyId())) {
            throw new IllegalStateException("BF-640 BLOCKED: unexpected compact governed-decision policy");
        }
        if (!SleeperLiveWaiverPostTransactionRosterConvergence.POLICY_ID.equals(convergence.policyId())) {
            throw new IllegalStateException("BF-640 BLOCKED: unexpected BF-639 convergence policy");
        }
        if (!Objects.equals(summary.leagueId(), convergence.leagueId())
            || !Objects.equals(summary.sleeperOwnerId(), convergence.sleeperOwnerId())
            || !Objects.equals(summary.sleeperLeagueId(), convergence.sleeperLeagueId())
            || summary.rosterId() != convergence.rosterId()
            || !Objects.equals(summary.auditId(), convergence.auditId())) {
            throw new IllegalStateException("BF-640 BLOCKED: BF-639 convergence does not reconcile to compact summary identity/audit");
        }
        boolean completedSummary = summary.state()
            == SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_ALREADY_COMPLETE;
        boolean converged = convergence.state()
            == SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceState.POST_TRANSACTION_ROSTER_CONVERGED;
        if (converged && !completedSummary) {
            throw new IllegalStateException("BF-640 BLOCKED: BF-639 reports post-transaction convergence without a completed compact transaction lifecycle");
        }
        if (completedSummary) {
            if (!Objects.equals(summary.addPlayer().sleeperPlayerId(), convergence.addSleeperPlayerId())
                || !Objects.equals(summary.dropPlayer().sleeperPlayerId(), convergence.dropSleeperPlayerId())) {
                throw new IllegalStateException("BF-640 BLOCKED: BF-639 convergence add/drop identity does not match completed compact transaction");
            }
        }
    }

    private static PlanStep step(
        int order,
        String bf,
        String taskName,
        StepMode mode,
        String leagueId,
        String purpose) {
        String task = requireText(taskName, "taskName");
        return new PlanStep(
            order,
            requireText(bf, "bf"),
            task,
            mode,
            ".\\gradlew.bat :bet:bet-cli:" + task + " --args=\"" + leagueId + "\"",
            requireText(purpose, "purpose"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("BF-640 " + field + " must not be blank");
        }
        return value.trim();
    }

    public enum PlanState {
        NOT_REQUIRED,
        NEXT_DECISION_PLAN_READY
    }

    public enum StepMode {
        BUTLER_WRITE,
        READ_ONLY
    }

    public record PlanStep(
        int order,
        String bf,
        String taskName,
        StepMode mode,
        String command,
        String purpose) {
        public PlanStep {
            if (order <= 0) throw new IllegalArgumentException("order must be positive");
            bf = requireText(bf, "bf");
            taskName = requireText(taskName, "taskName");
            mode = Objects.requireNonNull(mode, "mode must not be null");
            command = requireText(command, "command");
            purpose = requireText(purpose, "purpose");
        }
    }

    public record PlanReport(
        String policyId,
        String leagueId,
        PlanState state,
        List<PlanStep> steps) {
        public PlanReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-640 policyId");
            leagueId = requireText(leagueId, "leagueId");
            state = Objects.requireNonNull(state, "state must not be null");
            steps = List.copyOf(Objects.requireNonNull(steps, "steps must not be null"));
            if (state == PlanState.NEXT_DECISION_PLAN_READY && steps.size() != 9) {
                throw new IllegalArgumentException("BF-640 ready plan must contain exactly nine steps");
            }
            if (state == PlanState.NOT_REQUIRED && !steps.isEmpty()) {
                throw new IllegalArgumentException("BF-640 not-required plan must not contain steps");
            }
        }
    }
}
