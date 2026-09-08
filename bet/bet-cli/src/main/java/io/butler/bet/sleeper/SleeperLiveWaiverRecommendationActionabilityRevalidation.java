package io.butler.bet.sleeper;

import io.butler.bet.data.Database;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** BF-629 read-only live actionability revalidation of the latest BF-628-verified recommendation audit. */
public final class SleeperLiveWaiverRecommendationActionabilityRevalidation {
    public static final String POLICY_ID =
        "sleeper-live-waiver-recommendation-actionability-v1-bf623-bf628-live-roster-read-only";

    private final HistorySource historySource;
    private final RosterSource rosterSource;

    public SleeperLiveWaiverRecommendationActionabilityRevalidation(Database database) {
        this(
            target -> new SleeperLiveWaiverRecommendationAuditHistory(database).inspect(target),
            leagueId -> new SleeperApiGateway().fetchRosters(leagueId));
    }

    SleeperLiveWaiverRecommendationActionabilityRevalidation(
        HistorySource historySource,
        RosterSource rosterSource) {
        this.historySource = Objects.requireNonNull(historySource, "historySource must not be null");
        this.rosterSource = Objects.requireNonNull(rosterSource, "rosterSource must not be null");
    }

    public RevalidationReport revalidate(SleeperPersonalizedTargetService.VerifiedTarget target)
        throws SQLException, IOException, InterruptedException {
        validateVerifiedTarget(target);
        var history = historySource.inspect(target);
        validateHistory(target, history);

        if (history.entries().isEmpty()) {
            return report(target, null, ActionabilityState.NO_AUDITED_DECISION, null, null, null, null, 0);
        }

        var latest = history.entries().get(history.entries().size() - 1);
        if (!"RECOMMEND_ADD_DROP".equals(latest.recommendationState())) {
            return report(target, latest, ActionabilityState.NO_TRANSACTION_TO_REVALIDATE,
                latest.addSleeperPlayerId(), latest.dropSleeperPlayerId(), null, null, 0);
        }

        String addId = requireText(latest.addSleeperPlayerId(), "latest addSleeperPlayerId");
        String dropId = requireText(latest.dropSleeperPlayerId(), "latest dropSleeperPlayerId");
        if (addId.equals(dropId)) {
            throw new IllegalStateException("BF-629 BLOCKED: audited add and drop player ids are identical");
        }

        List<SleeperJsonParser.SleeperRoster> rosters = rosterSource.fetch(target.sleeperLeagueId());
        if (rosters == null || rosters.isEmpty()) {
            throw new IllegalStateException("BF-629 BLOCKED: current Sleeper rosters are empty");
        }

        SleeperJsonParser.SleeperRoster targetRoster = null;
        Map<String, Integer> rosterByPlayer = new HashMap<>();
        for (var roster : rosters) {
            if (roster.rosterId() == target.rosterId()) {
                if (targetRoster != null) {
                    throw new IllegalStateException("BF-629 BLOCKED: duplicate current target roster id " + target.rosterId());
                }
                targetRoster = roster;
            }
            for (String playerId : roster.playerIds()) {
                Integer prior = rosterByPlayer.putIfAbsent(playerId, roster.rosterId());
                if (prior != null && prior != roster.rosterId()) {
                    throw new IllegalStateException("BF-629 BLOCKED: current Sleeper player " + playerId
                        + " appears on multiple rosters: " + prior + " and " + roster.rosterId());
                }
            }
        }
        if (targetRoster == null) {
            throw new IllegalStateException("BF-629 BLOCKED: BF-623 target roster is absent from current Sleeper rosters");
        }
        if (target.membershipRole() == SleeperPersonalizedTargetService.MembershipRole.OWNER
            && !target.sleeperUserId().equals(targetRoster.ownerId())) {
            throw new IllegalStateException("BF-629 BLOCKED: current target roster owner no longer matches BF-623 owner binding");
        }

        Integer addRosterId = rosterByPlayer.get(addId);
        Integer dropRosterId = rosterByPlayer.get(dropId);
        boolean addAvailable = addRosterId == null;
        boolean dropOnTarget = dropRosterId != null && dropRosterId == target.rosterId();

        ActionabilityState state;
        if (!addAvailable && !dropOnTarget) state = ActionabilityState.ADD_AND_DROP_NO_LONGER_ACTIONABLE;
        else if (!addAvailable) state = ActionabilityState.ADD_NO_LONGER_AVAILABLE;
        else if (!dropOnTarget) state = ActionabilityState.DROP_NO_LONGER_ON_TARGET_ROSTER;
        else state = ActionabilityState.LIVE_ACTIONABLE_VERIFIED;

        return report(target, latest, state, addId, dropId, addRosterId, dropRosterId, rosters.size());
    }

    private static RevalidationReport report(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverRecommendationAuditHistory.HistoryEntry latest,
        ActionabilityState state,
        String addId,
        String dropId,
        Integer addRosterId,
        Integer dropRosterId,
        int currentRosterCount) {
        return new RevalidationReport(
            POLICY_ID,
            target.butlerLeagueId(),
            target.sleeperUserId(),
            target.sleeperLeagueId(),
            target.rosterId(),
            latest == null ? null : latest.auditId(),
            latest == null ? null : latest.capturedAtUtc(),
            latest == null ? null : latest.recommendationState(),
            addId,
            dropId,
            addRosterId,
            dropRosterId,
            currentRosterCount,
            state);
    }

    private static void validateVerifiedTarget(SleeperPersonalizedTargetService.VerifiedTarget target) {
        Objects.requireNonNull(target, "target must not be null");
        if (!SleeperPersonalizedTargetService.BF623_POLICY_ID.equals(target.policyId())
            || target.state() != SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED) {
            throw new IllegalStateException("BF-629 BLOCKED: target is not BF-623 live verified");
        }
    }

    private static void validateHistory(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverRecommendationAuditHistory.HistoryReport history) {
        Objects.requireNonNull(history, "history must not be null");
        if (!SleeperLiveWaiverRecommendationAuditHistory.POLICY_ID.equals(history.policyId())
            || !target.butlerLeagueId().equals(history.leagueId())
            || !target.sleeperUserId().equals(history.sleeperOwnerId())
            || !target.sleeperLeagueId().equals(history.sleeperLeagueId())
            || target.rosterId() != history.rosterId()) {
            throw new IllegalStateException("BF-629 BLOCKED: BF-628 history does not reconcile to BF-623 target");
        }
        if (!history.entries().isEmpty()
            && history.state() != SleeperLiveWaiverRecommendationAuditHistory.HistoryState.HISTORY_INTEGRITY_VERIFIED) {
            throw new IllegalStateException("BF-629 BLOCKED: non-empty BF-628 history is not integrity verified");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalStateException("BF-629 BLOCKED: " + field + " is blank");
        return value.trim();
    }

    @FunctionalInterface
    interface HistorySource {
        SleeperLiveWaiverRecommendationAuditHistory.HistoryReport inspect(
            SleeperPersonalizedTargetService.VerifiedTarget target) throws SQLException;
    }

    @FunctionalInterface
    interface RosterSource {
        List<SleeperJsonParser.SleeperRoster> fetch(String sleeperLeagueId)
            throws IOException, InterruptedException;
    }

    public enum ActionabilityState {
        NO_AUDITED_DECISION,
        NO_TRANSACTION_TO_REVALIDATE,
        LIVE_ACTIONABLE_VERIFIED,
        ADD_NO_LONGER_AVAILABLE,
        DROP_NO_LONGER_ON_TARGET_ROSTER,
        ADD_AND_DROP_NO_LONGER_ACTIONABLE
    }

    public record RevalidationReport(
        String policyId,
        String leagueId,
        String sleeperOwnerId,
        String sleeperLeagueId,
        int rosterId,
        String auditId,
        String capturedAtUtc,
        String recommendationState,
        String addSleeperPlayerId,
        String dropSleeperPlayerId,
        Integer addCurrentRosterId,
        Integer dropCurrentRosterId,
        int currentRosterCount,
        ActionabilityState state) {
        public RevalidationReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-629 policyId");
            Objects.requireNonNull(state, "state must not be null");
            if (currentRosterCount < 0) throw new IllegalArgumentException("currentRosterCount must not be negative");
        }
    }
}
