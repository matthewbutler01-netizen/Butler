package io.butler.bet.sleeper;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** BF-639 read-only roster convergence gate after an exact audited Sleeper transaction completes. */
public final class SleeperLiveWaiverPostTransactionRosterConvergence {
    public static final String POLICY_ID =
        "sleeper-live-waiver-post-transaction-roster-convergence-v1-bf623-bf638-live-roster-read-only";

    private final RosterSource rosterSource;

    public SleeperLiveWaiverPostTransactionRosterConvergence() {
        this(leagueId -> new SleeperApiGateway().fetchRosters(leagueId));
    }

    SleeperLiveWaiverPostTransactionRosterConvergence(RosterSource rosterSource) {
        this.rosterSource = Objects.requireNonNull(rosterSource, "rosterSource must not be null");
    }

    public ConvergenceReport inspect(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport summary)
        throws IOException, InterruptedException {
        validateTarget(target);
        validateSummary(target, summary);

        if (summary.state()
            != SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_ALREADY_COMPLETE) {
            return report(target, summary, ConvergenceState.NOT_APPLICABLE, null, null, 0);
        }

        String addId = requireText(summary.addPlayer().sleeperPlayerId(), "add Sleeper player id");
        String dropId = requireText(summary.dropPlayer().sleeperPlayerId(), "drop Sleeper player id");
        if (addId.equals(dropId)) {
            throw new IllegalStateException("BF-639 BLOCKED: completed audited add and drop player ids are identical");
        }

        List<SleeperJsonParser.SleeperRoster> rosters = rosterSource.fetch(target.sleeperLeagueId());
        if (rosters == null || rosters.isEmpty()) {
            throw new IllegalStateException("BF-639 BLOCKED: current Sleeper rosters are empty");
        }

        SleeperJsonParser.SleeperRoster targetRoster = null;
        Map<String, Integer> rosterByPlayer = new HashMap<>();
        for (var roster : rosters) {
            if (roster.rosterId() == target.rosterId()) {
                if (targetRoster != null) {
                    throw new IllegalStateException("BF-639 BLOCKED: duplicate current target roster id " + target.rosterId());
                }
                targetRoster = roster;
            }
            for (String playerId : roster.playerIds()) {
                Integer prior = rosterByPlayer.putIfAbsent(playerId, roster.rosterId());
                if (prior != null && prior != roster.rosterId()) {
                    throw new IllegalStateException("BF-639 BLOCKED: current Sleeper player " + playerId
                        + " appears on multiple rosters: " + prior + " and " + roster.rosterId());
                }
            }
        }

        if (targetRoster == null) {
            throw new IllegalStateException("BF-639 BLOCKED: BF-623 target roster is absent from current Sleeper rosters");
        }
        if (target.membershipRole() == SleeperPersonalizedTargetService.MembershipRole.OWNER
            && !target.sleeperUserId().equals(targetRoster.ownerId())) {
            throw new IllegalStateException("BF-639 BLOCKED: current target roster owner no longer matches BF-623 owner binding");
        }

        Integer addRosterId = rosterByPlayer.get(addId);
        Integer dropRosterId = rosterByPlayer.get(dropId);
        boolean addOnTarget = addRosterId != null && addRosterId == target.rosterId();
        boolean dropAbsentFromTarget = dropRosterId == null || dropRosterId != target.rosterId();
        ConvergenceState state = addOnTarget && dropAbsentFromTarget
            ? ConvergenceState.POST_TRANSACTION_ROSTER_CONVERGED
            : ConvergenceState.POST_TRANSACTION_ROSTER_PROPAGATION_PENDING;

        return report(target, summary, state, addRosterId, dropRosterId, rosters.size());
    }

    private static ConvergenceReport report(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport summary,
        ConvergenceState state,
        Integer addRosterId,
        Integer dropRosterId,
        int rosterCount) {
        return new ConvergenceReport(
            POLICY_ID,
            target.butlerLeagueId(),
            target.sleeperUserId(),
            target.sleeperLeagueId(),
            target.rosterId(),
            summary.auditId(),
            summary.addPlayer() == null ? null : summary.addPlayer().sleeperPlayerId(),
            summary.dropPlayer() == null ? null : summary.dropPlayer().sleeperPlayerId(),
            addRosterId,
            dropRosterId,
            rosterCount,
            state);
    }

    private static void validateTarget(SleeperPersonalizedTargetService.VerifiedTarget target) {
        Objects.requireNonNull(target, "target must not be null");
        if (!SleeperPersonalizedTargetService.BF623_POLICY_ID.equals(target.policyId())
            || target.state() != SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED) {
            throw new IllegalStateException("BF-639 BLOCKED: target is not BF-623 live verified");
        }
    }

    private static void validateSummary(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport summary) {
        Objects.requireNonNull(summary, "summary must not be null");
        if (!SleeperLiveWaiverLatestGovernedDecisionSummary.POLICY_ID.equals(summary.policyId())
            || !target.butlerLeagueId().equals(summary.leagueId())
            || !target.sleeperUserId().equals(summary.sleeperOwnerId())
            || !target.sleeperLeagueId().equals(summary.sleeperLeagueId())
            || target.rosterId() != summary.rosterId()) {
            throw new IllegalStateException("BF-639 BLOCKED: compact summary does not reconcile to BF-623 target");
        }
        if (summary.state()
            == SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryState.TRANSACTION_ALREADY_COMPLETE) {
            if (summary.bf629State()
                != SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.AUDITED_TRANSACTION_COMPLETE) {
                throw new IllegalStateException("BF-639 BLOCKED: completed compact state is not backed by BF-629 completed transaction evidence");
            }
            if (summary.addPlayer() == null || summary.dropPlayer() == null) {
                throw new IllegalStateException("BF-639 BLOCKED: completed compact state is missing exact add/drop identity");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("BF-639 BLOCKED: " + field + " is blank");
        }
        return value.trim();
    }

    @FunctionalInterface
    interface RosterSource {
        List<SleeperJsonParser.SleeperRoster> fetch(String sleeperLeagueId)
            throws IOException, InterruptedException;
    }

    public enum ConvergenceState {
        NOT_APPLICABLE,
        POST_TRANSACTION_ROSTER_PROPAGATION_PENDING,
        POST_TRANSACTION_ROSTER_CONVERGED
    }

    public record ConvergenceReport(
        String policyId,
        String leagueId,
        String sleeperOwnerId,
        String sleeperLeagueId,
        int rosterId,
        String auditId,
        String addSleeperPlayerId,
        String dropSleeperPlayerId,
        Integer addCurrentRosterId,
        Integer dropCurrentRosterId,
        int currentRosterCount,
        ConvergenceState state) {
        public ConvergenceReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-639 policyId");
            Objects.requireNonNull(state, "state must not be null");
            if (currentRosterCount < 0) throw new IllegalArgumentException("currentRosterCount must not be negative");
        }
    }
}
