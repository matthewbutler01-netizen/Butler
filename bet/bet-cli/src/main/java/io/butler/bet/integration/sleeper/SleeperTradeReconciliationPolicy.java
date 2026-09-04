package io.butler.bet.integration.sleeper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure exact-match policy for a Butler counter trade against read-only Sleeper transaction evidence. */
public final class SleeperTradeReconciliationPolicy {
    public static final String POLICY_ID =
        "sleeper-trade-reconciliation-v1-exact-assets-rosters-created-after";

    private SleeperTradeReconciliationPolicy() {}

    public static Result reconcile(
        ExpectedTrade expected,
        List<SleeperReadOnlyClient.SleeperTransaction> transactions) {
        Objects.requireNonNull(expected, "expected must not be null");
        Objects.requireNonNull(transactions, "transactions must not be null");

        List<SleeperReadOnlyClient.SleeperTransaction> exactEligible = new ArrayList<>();
        List<SleeperReadOnlyClient.SleeperTransaction> exactUnsupportedStatus = new ArrayList<>();
        boolean exactMissingCreated = false;

        for (var transaction : transactions) {
            if (transaction == null || !transaction.trade()) continue;
            if (!exactCoordinates(expected, transaction)) continue;
            if (expected.creatorUserId() != null
                && !expected.creatorUserId().equals(transaction.creatorUserId())) {
                continue;
            }
            if (transaction.createdEpochMillis() == null) {
                exactMissingCreated = true;
                continue;
            }
            if (transaction.createdEpochMillis() < expected.notBeforeEpochMillis()) continue;

            String status = transaction.status().toLowerCase();
            if (status.equals("pending") || status.equals("complete")) {
                exactEligible.add(transaction);
            } else {
                exactUnsupportedStatus.add(transaction);
            }
        }

        if (exactEligible.size() == 1) {
            var match = exactEligible.getFirst();
            State state = match.status().equalsIgnoreCase("complete")
                ? State.MATCH_COMPLETE
                : State.MATCH_PENDING;
            return new Result(
                POLICY_ID,
                state,
                expected,
                List.of(match.transactionId()),
                false,
                "Exactly one Sleeper trade matched the governed roster, asset, creator, and creation-time coordinates.");
        }
        if (exactEligible.size() > 1) {
            return new Result(
                POLICY_ID,
                State.AMBIGUOUS,
                expected,
                distinctIds(exactEligible),
                false,
                "Multiple Sleeper trades exactly matched the governed coordinates; Butler must not choose one arbitrarily.");
        }
        if (!exactUnsupportedStatus.isEmpty()) {
            return new Result(
                POLICY_ID,
                State.INCONCLUSIVE,
                expected,
                distinctIds(exactUnsupportedStatus),
                true,
                "Exact Sleeper trade evidence exists but its transaction status is outside the governed pending/complete reconciliation states.");
        }
        if (exactMissingCreated) {
            return new Result(
                POLICY_ID,
                State.INCONCLUSIVE,
                expected,
                List.of(),
                true,
                "Exact Sleeper trade evidence lacked a creation timestamp, so the not-before boundary could not be verified.");
        }
        return new Result(
            POLICY_ID,
            State.NO_MATCH,
            expected,
            List.of(),
            false,
            "No Sleeper trade after the governed not-before boundary exactly matched the expected roster and asset movements.");
    }

    private static boolean exactCoordinates(
        ExpectedTrade expected,
        SleeperReadOnlyClient.SleeperTransaction transaction) {
        return new HashSet<>(transaction.rosterIds()).equals(expected.rosterIds())
            && transaction.adds().equals(expected.playerAdds())
            && transaction.drops().equals(expected.playerDrops())
            && new HashSet<>(transaction.draftPicks()).equals(expected.draftPicks());
    }

    private static List<String> distinctIds(
        List<SleeperReadOnlyClient.SleeperTransaction> transactions) {
        Set<String> ids = new LinkedHashSet<>();
        for (var transaction : transactions) ids.add(transaction.transactionId());
        return List.copyOf(ids);
    }

    public enum State {
        MATCH_PENDING,
        MATCH_COMPLETE,
        NO_MATCH,
        AMBIGUOUS,
        INCONCLUSIVE
    }

    public record ExpectedTrade(
        String leagueId,
        int round,
        Set<Integer> rosterIds,
        Map<String, Integer> playerAdds,
        Map<String, Integer> playerDrops,
        Set<SleeperReadOnlyClient.DraftPick> draftPicks,
        String creatorUserId,
        long notBeforeEpochMillis) {
        public ExpectedTrade {
            if (leagueId == null || !leagueId.matches("[0-9]+")) {
                throw new IllegalArgumentException("leagueId must be a Sleeper numeric league id");
            }
            if (round < 1 || round > 30) throw new IllegalArgumentException("round must be between 1 and 30");
            rosterIds = Set.copyOf(Objects.requireNonNull(rosterIds, "rosterIds must not be null"));
            playerAdds = Map.copyOf(Objects.requireNonNull(playerAdds, "playerAdds must not be null"));
            playerDrops = Map.copyOf(Objects.requireNonNull(playerDrops, "playerDrops must not be null"));
            draftPicks = Set.copyOf(Objects.requireNonNull(draftPicks, "draftPicks must not be null"));
            if (rosterIds.size() != 2 || rosterIds.stream().anyMatch(id -> id == null || id < 1)) {
                throw new IllegalArgumentException("counter reconciliation requires exactly two positive Sleeper roster ids");
            }
            if (playerAdds.isEmpty() && playerDrops.isEmpty() && draftPicks.isEmpty()) {
                throw new IllegalArgumentException("expected trade must contain at least one governed asset movement");
            }
            requirePlayerMap(playerAdds, rosterIds, "playerAdds");
            requirePlayerMap(playerDrops, rosterIds, "playerDrops");
            if (creatorUserId != null && creatorUserId.isBlank()) {
                throw new IllegalArgumentException("creatorUserId must be null or non-blank");
            }
            if (notBeforeEpochMillis < 0) throw new IllegalArgumentException("notBeforeEpochMillis must not be negative");
        }
    }

    public record Result(
        String policyId,
        State state,
        ExpectedTrade expected,
        List<String> matchingTransactionIds,
        boolean reconciliationEvidenceIncomplete,
        String reason) {
        public Result {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(expected, "expected must not be null");
            matchingTransactionIds = List.copyOf(Objects.requireNonNull(
                matchingTransactionIds, "matchingTransactionIds must not be null"));
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
            if ((state == State.MATCH_PENDING || state == State.MATCH_COMPLETE)
                && matchingTransactionIds.size() != 1) {
                throw new IllegalArgumentException("matched reconciliation must identify exactly one transaction");
            }
            if (state == State.AMBIGUOUS && matchingTransactionIds.size() < 2) {
                throw new IllegalArgumentException("ambiguous reconciliation requires multiple matching transactions");
            }
            if (state == State.NO_MATCH && !matchingTransactionIds.isEmpty()) {
                throw new IllegalArgumentException("NO_MATCH cannot carry matching transaction ids");
            }
            if (reconciliationEvidenceIncomplete != (state == State.INCONCLUSIVE)) {
                throw new IllegalArgumentException("incomplete evidence flag must match INCONCLUSIVE state");
            }
        }
    }

    private static void requirePlayerMap(
        Map<String, Integer> players,
        Set<Integer> rosterIds,
        String field) {
        for (var entry : players.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException(field + " player id must not be blank");
            }
            if (entry.getValue() == null || !rosterIds.contains(entry.getValue())) {
                throw new IllegalArgumentException(field + " roster destination/source must be one of the trade roster ids");
            }
        }
    }
}
