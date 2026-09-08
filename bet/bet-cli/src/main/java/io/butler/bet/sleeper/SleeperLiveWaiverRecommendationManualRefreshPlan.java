package io.butler.bet.sleeper;

import java.util.List;
import java.util.Objects;

/** BF-636 read-only manual refresh instructions for a BF-635 refresh recommendation. */
public final class SleeperLiveWaiverRecommendationManualRefreshPlan {
    public static final String POLICY_ID =
        "sleeper-live-waiver-manual-refresh-plan-v1-bf635-explicit-operator-only-no-execution";

    private SleeperLiveWaiverRecommendationManualRefreshPlan() {}

    public static PlanReport plan(SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport summary) {
        Objects.requireNonNull(summary, "BF-635 summary must not be null");
        if (!SleeperLiveWaiverLatestGovernedDecisionSummary.POLICY_ID.equals(summary.policyId())) {
            throw new IllegalStateException("BF-636 BLOCKED: unexpected compact governed-decision policy");
        }
        String leagueId = requireText(summary.leagueId(), "leagueId");
        if (summary.state()
            != SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.CURRENT_REFRESH_RECOMMENDED) {
            return new PlanReport(POLICY_ID, leagueId, PlanState.NOT_REQUIRED, List.of());
        }

        List<PlanStep> steps = List.of(
            step(1, "BF-602", "sleeperLiveWaiverSnapshotSync", StepMode.BUTLER_WRITE, leagueId,
                "Persist a fresh immutable live waiver identity and league-eligibility snapshot."),
            step(2, "BF-603", "sleeperLiveWaiverMarketAttentionSync", StepMode.BUTLER_WRITE, leagueId,
                "Persist fresh add/drop market-attention evidence against the new BF-602 snapshot."),
            step(3, "BF-605", "sleeperLiveWaiverProductionHydration", StepMode.BUTLER_WRITE, leagueId,
                "Guardedly hydrate exact 2025 production for the refreshed BF-603 candidate frame; unmatched production remains missing."),
            step(4, "BF-606", "sleeperLiveWaiverAvailabilitySync", StepMode.BUTLER_WRITE, leagueId,
                "Persist current availability and depth metadata for the exact refreshed BF-603 frame."),
            step(5, "BF-607", "sleeperLiveWaiverCurrentWeekStatSync", StepMode.BUTLER_WRITE, leagueId,
                "Persist current-week raw-stat evidence tied to the refreshed BF-603 and BF-606 snapshots."),
            step(6, "BF-612", "sleeperLiveWaiverTargetRosterProductionHydration", StepMode.BUTLER_WRITE, leagueId,
                "After the refreshed BF-609/BF-610/BF-611 frame reconciles, hydrate only missing exact target-roster 2025 production."),
            step(7, "BF-618/BF-620", "sleeperLiveWaiverFinalRecommendationBundle", StepMode.READ_ONLY, leagueId,
                "Rebuild the governed comparison, selection, and final recommendation from the refreshed evidence frame."),
            step(8, "BF-627", "sleeperLiveWaiverRecommendationAuditCapture", StepMode.BUTLER_WRITE, leagueId,
                "Explicitly capture the refreshed governed outcome as a new immutable Butler audit record."),
            step(9, "BF-629/BF-631/BF-633/BF-635", "sleeperLiveWaiverLatestGovernedDecisionSummary", StepMode.READ_ONLY, leagueId,
                "Revalidate the latest audit for live roster actionability, evidence lineage, evidence age, and the approved freshness warning policy."));
        return new PlanReport(POLICY_ID, leagueId, PlanState.MANUAL_REFRESH_PLAN_READY, steps);
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
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public enum PlanState {
        NOT_REQUIRED,
        MANUAL_REFRESH_PLAN_READY
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
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-636 policyId");
            leagueId = requireText(leagueId, "leagueId");
            state = Objects.requireNonNull(state, "state must not be null");
            steps = List.copyOf(Objects.requireNonNull(steps, "steps must not be null"));
            if (state == PlanState.MANUAL_REFRESH_PLAN_READY && steps.size() != 9) {
                throw new IllegalArgumentException("BF-636 ready plan must contain exactly nine steps");
            }
            if (state == PlanState.NOT_REQUIRED && !steps.isEmpty()) {
                throw new IllegalArgumentException("BF-636 not-required plan must not contain steps");
            }
        }
    }
}
