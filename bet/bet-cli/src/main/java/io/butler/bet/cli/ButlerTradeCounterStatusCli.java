package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.integration.sleeper.SleeperCounterTradeExpectationSnapshotRepository;
import io.butler.bet.integration.sleeper.SleeperCounterTradeOutcomeCoordinator;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffService;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

/** Local-only lifecycle inspection for one governed manual Sleeper counter trade. */
public final class ButlerTradeCounterStatusCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeCounterStatusCli() {}

    public static void main(String[] args) {
        String grantId;
        try {
            grantId = parseGrantId(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
            return;
        }

        try {
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            var handoff = findHandoffByGrantId(database, grantId);
            if (handoff == null
                || handoff.action() != TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE
                || handoff.reconciliationMode()
                    != SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK) {
                printUnavailable(grantId);
                return;
            }

            var snapshot = new SleeperCounterTradeExpectationSnapshotRepository(database)
                .findByClaimId(handoff.claimId()).orElse(null);
            var outcome = new SleeperCounterTradeOutcomeCoordinator(database)
                .findByClaimId(handoff.claimId()).orElse(null);
            print(inspect(handoff, snapshot, outcome));
        } catch (SQLException e) {
            System.err.println("Database error while inspecting manual trade lifecycle: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static String parseGrantId(String[] args) {
        if (!isCommand(args) || args.length != 3) {
            throw new IllegalArgumentException(
                "trade counter-status requires exactly one trusted grant ID");
        }
        return requireText(args[2], "trusted-grant-id");
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "trade".equalsIgnoreCase(args[0])
            && "counter-status".equalsIgnoreCase(args[1]);
    }

    static LifecycleStatus inspect(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperCounterTradeExpectationSnapshotRepository.Snapshot snapshot,
        SleeperCounterTradeOutcomeCoordinator.StoredOutcome outcome) {
        Objects.requireNonNull(handoff, "handoff must not be null");
        if (handoff.action() != TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE
            || handoff.destination().type() != TradeCounterAuthorizationPolicy.DestinationType.LEAGUE
            || handoff.reconciliationMode()
                != SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK) {
            throw new IllegalStateException("trusted handoff is not a manual Sleeper trade handoff");
        }

        if (snapshot != null) requireSnapshotMatches(handoff, snapshot);
        if (outcome != null) {
            if (snapshot == null) {
                throw new IllegalStateException(
                    "terminal manual-trade outcome exists without provider expectation snapshot evidence");
            }
            requireOutcomeMatches(handoff, snapshot, outcome);
        }

        State state = outcome != null
            ? State.FINALIZED
            : snapshot != null
                ? State.LOCAL_UNFINALIZED
                : State.SNAPSHOT_MISSING;
        return new LifecycleStatus(state, handoff, snapshot, outcome);
    }

    static void print(LifecycleStatus status) {
        Objects.requireNonNull(status, "status must not be null");
        var handoff = status.handoff();

        System.out.println("Trade counter manual trade local lifecycle status");
        System.out.println("Trusted grant ID: " + handoff.grantId());
        System.out.println("Execution claim ID: " + handoff.claimId());
        System.out.println("Execution attempt ID: " + handoff.attemptId());
        System.out.println("Handoff presentation ID: " + handoff.handoffId());
        System.out.println("Butler league destination ID: " + handoff.destination().id());
        System.out.println("First presented at: " + handoff.presentedAt());
        System.out.println("Local lifecycle state: " + status.state());

        if (status.snapshot() == null) {
            System.out.println("Provider expectation snapshot: NOT_RECORDED");
            System.out.println("Local terminal outcome: NOT_RECORDED");
            System.out.println("External Sleeper completion: NOT_INFERRED");
            System.out.println("Next safe action: reconstruct a governed handoff that includes a provider expectation snapshot before reconciliation.");
        } else {
            var snapshot = status.snapshot();
            System.out.println("Provider expectation snapshot: RECORDED");
            System.out.println("Sleeper league ID snapshot: " + snapshot.sleeperLeagueId());
            System.out.println("Frozen movement SHA-256: " + snapshot.movementSha256());
            System.out.println("Snapshot captured at: " + snapshot.snapshottedAt());

            if (status.outcome() == null) {
                System.out.println("Local terminal outcome: NOT_RECORDED");
                System.out.println("External Sleeper completion: NOT_INFERRED");
                System.out.println("Next safe action: run trade counter-reconcile with an explicit Sleeper week for live GET-only evidence.");
            } else {
                var outcome = status.outcome();
                System.out.println("Local terminal outcome: RECORDED");
                System.out.println("Completed Sleeper transaction ID: " + outcome.sleeperTransactionId());
                System.out.println("Sleeper week used for finalization: " + outcome.sleeperWeek());
                System.out.println("Terminal execution state: " + outcome.terminalState());
                System.out.println("Authorization disposition: " + outcome.grantDisposition());
                System.out.println("Finalization applied at: " + outcome.appliedAt());
                System.out.println("Local Butler lifecycle is complete from previously verified exact completed readback.");
            }
        }

        System.out.println("Local inspection only; this command performs no Sleeper request.");
        System.out.println("This command does not submit, accept, reject, alter, reconcile, or finalize a trade.");
        System.out.println("This command does not change execution state or consume authorization.");
    }

    static void printUnavailable(String grantId) {
        grantId = requireText(grantId, "grantId");
        System.out.println("Trade counter manual trade local lifecycle status unavailable");
        System.out.println("Trusted grant ID: " + grantId);
        System.out.println("Reason: no matching durable manual trade handoff was found.");
        System.out.println("Local inspection only; no Sleeper request or local lifecycle mutation occurred.");
    }

    static void printUsage() {
        System.out.println("  butler trade counter-status <trusted-grant-id>");
        System.out.println("  Reads only local persisted trade handoff, provider snapshot, and terminal-outcome evidence.");
        System.out.println("  Reports SNAPSHOT_MISSING, LOCAL_UNFINALIZED, or FINALIZED without inferring current Sleeper state.");
        System.out.println("  This command performs no Sleeper request and does not reconcile or finalize anything.");
    }

    private static SleeperManualCounterHandoffRepository.PresentedHandoff findHandoffByGrantId(
        Database database,
        String grantId) throws SQLException {
        grantId = requireText(grantId, "grantId");
        new SleeperManualCounterHandoffRepository(database).initialize();
        try (var connection = database.openConnection();
             var statement = connection.prepareStatement(
                 "SELECT * FROM sleeper_manual_counter_handoffs WHERE grant_id = ?")) {
            statement.setString(1, grantId);
            try (var rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                return new SleeperManualCounterHandoffRepository.PresentedHandoff(
                    rs.getString("handoff_id"),
                    rs.getString("journal_policy_id"),
                    rs.getString("handoff_service_id"),
                    rs.getString("capability_policy_id"),
                    rs.getString("execution_request_policy_id"),
                    rs.getString("claim_id"),
                    rs.getString("attempt_id"),
                    rs.getString("grant_id"),
                    rs.getString("proposal_fingerprint"),
                    TradeCounterAuthorizationPolicy.Action.valueOf(rs.getString("action")),
                    new TradeCounterAuthorizationPolicy.Destination(
                        TradeCounterAuthorizationPolicy.DestinationType.valueOf(
                            rs.getString("destination_type")),
                        rs.getString("destination_id")),
                    rs.getString("payload_kind"),
                    rs.getString("payload_sha256"),
                    SleeperManualCounterHandoffService.ReconciliationMode.valueOf(
                        rs.getString("reconciliation_mode")),
                    Instant.parse(rs.getString("presented_at")));
            }
        }
    }

    private static void requireSnapshotMatches(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperCounterTradeExpectationSnapshotRepository.Snapshot snapshot) {
        if (!handoff.claimId().equals(snapshot.claimId())
            || !handoff.handoffId().equals(snapshot.handoffId())
            || !handoff.destination().id().equals(snapshot.butlerLeagueId())) {
            throw new IllegalStateException(
                "provider expectation snapshot does not match trusted manual trade handoff coordinates");
        }
    }

    private static void requireOutcomeMatches(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperCounterTradeExpectationSnapshotRepository.Snapshot snapshot,
        SleeperCounterTradeOutcomeCoordinator.StoredOutcome outcome) {
        if (!handoff.claimId().equals(outcome.claimId())
            || !handoff.handoffId().equals(outcome.handoffId())
            || !handoff.attemptId().equals(outcome.attemptId())
            || !handoff.grantId().equals(outcome.grantId())
            || !snapshot.movementSha256().equals(outcome.movementSha256())
            || outcome.terminalState() != TradeCounterExecutionAttemptRepository.State.SUCCEEDED
            || !"CONSUME".equals(outcome.grantDisposition())) {
            throw new IllegalStateException(
                "terminal manual-trade outcome does not match trusted local lifecycle coordinates");
        }
    }

    enum State {
        SNAPSHOT_MISSING,
        LOCAL_UNFINALIZED,
        FINALIZED
    }

    record LifecycleStatus(
        State state,
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperCounterTradeExpectationSnapshotRepository.Snapshot snapshot,
        SleeperCounterTradeOutcomeCoordinator.StoredOutcome outcome) {
        LifecycleStatus {
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(handoff, "handoff must not be null");
            if ((state == State.SNAPSHOT_MISSING) != (snapshot == null)) {
                throw new IllegalArgumentException(
                    "snapshot-missing is the only lifecycle state without provider snapshot evidence");
            }
            if ((state == State.FINALIZED) != (outcome != null)) {
                throw new IllegalArgumentException(
                    "only finalized lifecycle state may carry a terminal outcome");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
