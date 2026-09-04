package io.butler.bet.integration.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionClaimRepository;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Reconciles one durably presented counter trade from frozen BF-406 provider movement using only
 * Sleeper's documented read-only transactions endpoint. This service never mutates execution state.
 */
public final class SleeperCounterTradeSnapshotReconciliationService {
    public static final String SERVICE_ID =
        "sleeper-counter-trade-snapshot-reconciliation-v1-explicit-week-read-only";

    private final TradeCounterExecutionAttemptRepository attempts;
    private final TradeCounterExecutionClaimRepository claims;
    private final SleeperManualCounterHandoffRepository handoffs;
    private final SleeperCounterTradeExpectationSnapshotRepository snapshots;
    private final SleeperReadOnlyClient client;

    public SleeperCounterTradeSnapshotReconciliationService(
        Database database,
        SleeperReadOnlyClient client) {
        Objects.requireNonNull(database, "database must not be null");
        this.attempts = new TradeCounterExecutionAttemptRepository(database);
        this.claims = new TradeCounterExecutionClaimRepository(database);
        this.handoffs = new SleeperManualCounterHandoffRepository(database);
        this.snapshots = new SleeperCounterTradeExpectationSnapshotRepository(database);
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    public Report reconcile(String grantId, int week)
        throws SQLException, IOException, InterruptedException {
        grantId = requireText(grantId, "grantId");
        requireWeek(week);

        var attempt = attempts.findByGrantId(grantId).orElse(null);
        if (attempt == null) {
            return unavailable(grantId, week,
                "Trusted authorization grant has no durable execution attempt.");
        }
        var claim = claims.findByAttemptId(attempt.attemptId()).orElse(null);
        if (claim == null) {
            return unavailable(grantId, week,
                "Trusted execution attempt has no durable READY claim.");
        }
        if (!grantId.equals(claim.grantId())) {
            throw new IllegalStateException("execution attempt and claim reference different grants");
        }
        var handoff = handoffs.findByClaimId(claim.claimId()).orElse(null);
        if (handoff == null) {
            return unavailable(grantId, week,
                "Trusted execution claim has no durable manual handoff presentation.");
        }
        var snapshot = snapshots.findByClaimId(claim.claimId()).orElse(null);
        if (snapshot == null) {
            return unavailable(grantId, week,
                "Trade handoff has no immutable Sleeper provider expectation snapshot.");
        }
        return reconcile(snapshot, handoff, week, client);
    }

    public static Report reconcile(
        SleeperCounterTradeExpectationSnapshotRepository.Snapshot snapshot,
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        int week,
        SleeperReadOnlyClient client) throws IOException, InterruptedException {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(handoff, "handoff must not be null");
        Objects.requireNonNull(client, "client must not be null");
        requireWeek(week);
        requireMatchingSnapshotAndHandoff(snapshot, handoff);

        var expected = new SleeperTradeReconciliationPolicy.ExpectedTrade(
            snapshot.sleeperLeagueId(),
            week,
            snapshot.rosterIds(),
            snapshot.playerAdds(),
            snapshot.playerDrops(),
            snapshot.draftPicks(),
            null,
            handoff.presentedAt().toEpochMilli());
        List<SleeperReadOnlyClient.SleeperTransaction> transactions =
            client.transactions(snapshot.sleeperLeagueId(), week);
        var reconciliation = SleeperTradeReconciliationPolicy.reconcile(expected, transactions);
        return new Report(
            SERVICE_ID,
            State.RECONCILED,
            handoff.grantId(),
            handoff.claimId(),
            handoff.handoffId(),
            snapshot.movementSha256(),
            week,
            handoff.presentedAt().toEpochMilli(),
            reconciliation,
            transactions,
            "Official Sleeper transaction evidence was read after the immutable handoff boundary and evaluated by exact governed matching.");
    }

    private static void requireMatchingSnapshotAndHandoff(
        SleeperCounterTradeExpectationSnapshotRepository.Snapshot snapshot,
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff) {
        boolean matching = snapshot.claimId().equals(handoff.claimId())
            && snapshot.handoffId().equals(handoff.handoffId())
            && snapshot.butlerLeagueId().equals(handoff.destination().id())
            && handoff.action() == TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE
            && handoff.destination().type() == TradeCounterAuthorizationPolicy.DestinationType.LEAGUE
            && handoff.reconciliationMode()
                == SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK;
        if (!matching) {
            throw new IllegalArgumentException(
                "Sleeper trade expectation snapshot and trusted handoff do not match");
        }
    }

    private static Report unavailable(String grantId, int week, String reason) {
        return new Report(
            SERVICE_ID,
            State.NOT_AVAILABLE,
            requireText(grantId, "grantId"),
            null,
            null,
            null,
            week,
            null,
            null,
            List.of(),
            reason);
    }

    public enum State {
        RECONCILED,
        NOT_AVAILABLE
    }

    public record Report(
        String serviceId,
        State state,
        String grantId,
        String claimId,
        String handoffId,
        String movementSha256,
        int week,
        Long notBeforeEpochMillis,
        SleeperTradeReconciliationPolicy.Result reconciliation,
        List<SleeperReadOnlyClient.SleeperTransaction> observedTransactions,
        String reason) {
        public Report {
            if (!SERVICE_ID.equals(serviceId)) throw new IllegalArgumentException("unexpected serviceId");
            Objects.requireNonNull(state, "state must not be null");
            grantId = requireText(grantId, "grantId");
            requireWeek(week);
            observedTransactions = List.copyOf(Objects.requireNonNull(
                observedTransactions, "observedTransactions must not be null"));
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");

            if (state == State.RECONCILED) {
                claimId = requireText(claimId, "claimId");
                handoffId = requireText(handoffId, "handoffId");
                requireFingerprint(movementSha256, "movementSha256");
                if (notBeforeEpochMillis == null || notBeforeEpochMillis < 0) {
                    throw new IllegalArgumentException("RECONCILED requires nonnegative notBeforeEpochMillis");
                }
                Objects.requireNonNull(reconciliation, "RECONCILED requires reconciliation");
                if (reconciliation.expected().round() != week
                    || reconciliation.expected().notBeforeEpochMillis() != notBeforeEpochMillis) {
                    throw new IllegalArgumentException(
                        "reconciliation expected coordinates must match service week/not-before boundary");
                }
            } else if (claimId != null || handoffId != null || movementSha256 != null
                || notBeforeEpochMillis != null || reconciliation != null
                || !observedTransactions.isEmpty()) {
                throw new IllegalArgumentException(
                    "NOT_AVAILABLE cannot carry reconciliation evidence");
            }
        }
    }

    private static void requireWeek(int week) {
        if (week < 1 || week > 30) {
            throw new IllegalArgumentException("Sleeper week must be between 1 and 30");
        }
    }

    private static void requireFingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
