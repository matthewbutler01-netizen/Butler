package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;

import java.io.IOException;
import java.time.Duration;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** BF-629 read-only live actionability revalidation of the latest BF-628-verified recommendation audit. */
public final class SleeperLiveWaiverRecommendationActionabilityRevalidation {
    public static final String POLICY_ID =
        "sleeper-live-waiver-recommendation-actionability-v2-bf623-bf628-live-roster-and-transaction-read-only";

    private final HistorySource historySource;
    private final RosterSource rosterSource;
    private final TransactionSource transactionSource;
    private final CurrentWeekSource currentWeekSource;
    private final ObjectMapper mapper = new ObjectMapper();

    public SleeperLiveWaiverRecommendationActionabilityRevalidation(Database database) {
        this(
            target -> new SleeperLiveWaiverRecommendationAuditHistory(database).inspect(target),
            leagueId -> new SleeperApiGateway().fetchRosters(leagueId),
            (leagueId, round) -> new SleeperClient().getLeagueTransactions(leagueId, round),
            () -> new SleeperClient().getNflState(Duration.ofSeconds(4)));
    }

    SleeperLiveWaiverRecommendationActionabilityRevalidation(
        HistorySource historySource,
        RosterSource rosterSource,
        TransactionSource transactionSource,
        CurrentWeekSource currentWeekSource) {
        this.historySource = Objects.requireNonNull(historySource, "historySource must not be null");
        this.rosterSource = Objects.requireNonNull(rosterSource, "rosterSource must not be null");
        this.transactionSource = Objects.requireNonNull(transactionSource, "transactionSource must not be null");
        this.currentWeekSource = Objects.requireNonNull(currentWeekSource, "currentWeekSource must not be null");
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

        TransactionMatch transactionMatch = exactAuditedTransaction(target, latest, addId, dropId);
        if (transactionMatch == TransactionMatch.COMPLETE) {
            return report(target, latest, ActionabilityState.AUDITED_TRANSACTION_COMPLETE,
                addId, dropId, null, null, 0);
        }
        if (transactionMatch == TransactionMatch.PENDING) {
            return report(target, latest, ActionabilityState.AUDITED_TRANSACTION_PENDING,
                addId, dropId, null, null, 0);
        }

        // BF-1528: an exact audited add/drop can outlive its scoring week.
        // Duplicate transaction evidence is checked above, but a still-available
        // player pair is not proof that a Week 5 recommendation is valid in Week 6.
        // Current-week proof is independent of the saved BF-628/BF-603 frame.
        CurrentWeekProof weekProof = verifyAuditedWeek(latest);
        if (weekProof != CurrentWeekProof.MATCH) {
            ActionabilityState hold = switch (weekProof) {
                case NEXT_REGULAR_WEEK -> ActionabilityState.AUDITED_WEEK_ROLLOVER_DO_NOT_ACT;
                case MISMATCH -> ActionabilityState.AUDITED_WEEK_MISMATCH_DO_NOT_ACT;
                case UNVERIFIED -> ActionabilityState.AUDITED_WEEK_UNVERIFIED_DO_NOT_ACT;
                case MATCH -> throw new IllegalStateException("BF-1529 unexpected matching rollover hold");
            };
            return report(target, latest, hold, addId, dropId, null, null, 0);
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

    private CurrentWeekProof verifyAuditedWeek(
        SleeperLiveWaiverRecommendationAuditHistory.HistoryEntry audit) throws InterruptedException {
        if (audit.providerSeason() != SleeperPersonalizedTargetService.TARGET_SEASON
            || audit.providerLeg() == null || audit.providerLeg() < 1 || audit.providerLeg() > 18
            || !"in_season".equals(audit.providerStatus())) {
            return CurrentWeekProof.UNVERIFIED;
        }
        final String raw;
        try {
            raw = currentWeekSource.fetch();
        } catch (IOException | RuntimeException error) {
            // An unavailable/invalid public state is a hard hold, not an excuse
            // to treat saved provider leg as the current week.
            return CurrentWeekProof.UNVERIFIED;
        }
        if (raw == null || raw.isBlank()) return CurrentWeekProof.UNVERIFIED;

        try {
            JsonNode state = mapper.readTree(raw);
            if (state == null || !state.isObject()) return CurrentWeekProof.UNVERIFIED;
            String season = text(state.get("season"));
            String seasonType = text(state.get("season_type"));
            JsonNode week = state.get("week");
            if (season == null || !season.matches("20[0-9]{2}")
                || seasonType == null || seasonType.isBlank()
                || week == null || !week.isIntegralNumber()) {
                return CurrentWeekProof.UNVERIFIED;
            }
            int currentWeek = week.intValue();
            if (currentWeek < 1 || currentWeek > 18) return CurrentWeekProof.UNVERIFIED;
            boolean sameRegularSeason = Integer.toString(audit.providerSeason()).equals(season)
                && "regular".equals(seasonType);
            if (!sameRegularSeason) return CurrentWeekProof.MISMATCH;
            if (audit.providerLeg() == currentWeek) return CurrentWeekProof.MATCH;
            // BF-1529 authorizes *only a new manual governed evidence cycle* when
            // public source explicitly proves the NEXT regular-season week.
            // This never makes the old add/drop actionable.
            if (audit.providerLeg() + 1 == currentWeek) return CurrentWeekProof.NEXT_REGULAR_WEEK;
            return CurrentWeekProof.MISMATCH;
        } catch (IOException | RuntimeException malformed) {
            return CurrentWeekProof.UNVERIFIED;
        }
    }

    private TransactionMatch exactAuditedTransaction(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverRecommendationAuditHistory.HistoryEntry latest,
        String addId,
        String dropId) throws IOException, InterruptedException {
        Integer providerLeg = latest.providerLeg();
        if (providerLeg == null) return TransactionMatch.NONE;
        if (providerLeg < 1 || providerLeg > 18) {
            throw new IllegalStateException("BF-629 BLOCKED: audited provider leg is outside Sleeper transaction rounds: "
                + providerLeg);
        }

        String payload = transactionSource.fetch(target.sleeperLeagueId(), providerLeg);
        if (payload == null || payload.isBlank()) {
            throw new IllegalStateException("BF-629 BLOCKED: Sleeper transaction payload is blank");
        }
        JsonNode root = mapper.readTree(payload);
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("BF-629 BLOCKED: Sleeper transaction payload must be an array");
        }

        boolean pending = false;
        for (JsonNode transaction : root) {
            if (transaction == null || !transaction.isObject()) {
                throw new IllegalStateException("BF-629 BLOCKED: Sleeper transaction row must be an object");
            }
            String type = text(transaction.get("type"));
            if (!"free_agent".equals(type) && !"waiver".equals(type)) continue;
            if (!exactPlayerRosterMap(transaction.get("adds"), addId, target.rosterId())) continue;
            if (!exactPlayerRosterMap(transaction.get("drops"), dropId, target.rosterId())) continue;
            if (!exactRosterIdsIfPresent(transaction.get("roster_ids"), target.rosterId())) continue;

            String status = text(transaction.get("status"));
            if ("complete".equals(status)) return TransactionMatch.COMPLETE;
            if ("pending".equals(status)) pending = true;
        }
        return pending ? TransactionMatch.PENDING : TransactionMatch.NONE;
    }

    private static boolean exactPlayerRosterMap(JsonNode node, String playerId, int rosterId) {
        if (node == null || !node.isObject() || node.size() != 1 || !node.has(playerId)) return false;
        JsonNode roster = node.get(playerId);
        return roster != null && roster.canConvertToInt() && roster.intValue() == rosterId;
    }

    private static boolean exactRosterIdsIfPresent(JsonNode node, int rosterId) {
        if (node == null || node.isNull()) return true;
        return node.isArray()
            && node.size() == 1
            && node.get(0).canConvertToInt()
            && node.get(0).intValue() == rosterId;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
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

    @FunctionalInterface
    interface TransactionSource {
        String fetch(String sleeperLeagueId, int round) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface CurrentWeekSource {
        String fetch() throws IOException, InterruptedException;
    }

    private enum CurrentWeekProof { MATCH, NEXT_REGULAR_WEEK, MISMATCH, UNVERIFIED }
    private enum TransactionMatch { NONE, PENDING, COMPLETE }

    public enum ActionabilityState {
        NO_AUDITED_DECISION,
        NO_TRANSACTION_TO_REVALIDATE,
        LIVE_ACTIONABLE_VERIFIED,
        AUDITED_WEEK_MISMATCH_DO_NOT_ACT,
        AUDITED_WEEK_ROLLOVER_DO_NOT_ACT,
        AUDITED_WEEK_UNVERIFIED_DO_NOT_ACT,
        AUDITED_TRANSACTION_PENDING,
        AUDITED_TRANSACTION_COMPLETE,
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
