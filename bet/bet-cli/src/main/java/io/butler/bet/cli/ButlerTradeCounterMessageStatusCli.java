package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffService;
import io.butler.bet.integration.sleeper.SleeperManualMessageAcknowledgmentRepository;
import io.butler.bet.integration.sleeper.SleeperManualMessageOutcomeCoordinator;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

/** Read-only lifecycle inspection for one trusted manual Sleeper negotiation message. */
public final class ButlerTradeCounterMessageStatusCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeCounterMessageStatusCli() {}

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
            var handoff = new SleeperManualCounterHandoffRepository(database)
                .findByGrantId(grantId).orElse(null);
            if (!isManualMessageHandoff(handoff)) {
                printUnavailable(grantId);
                return;
            }

            var acknowledgment = new SleeperManualMessageAcknowledgmentRepository(database)
                .findByClaimId(handoff.claimId()).orElse(null);
            var outcome = new SleeperManualMessageOutcomeCoordinator(database)
                .findByClaimId(handoff.claimId()).orElse(null);
            print(inspect(handoff, acknowledgment, outcome));
        } catch (SQLException e) {
            System.err.println("Database error while inspecting manual message lifecycle: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static String parseGrantId(String[] args) {
        if (!isCommand(args) || args.length != 3) {
            throw new IllegalArgumentException(
                "trade counter-message-status requires exactly one trusted grant ID");
        }
        return requireText(args[2], "trusted-grant-id");
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "trade".equalsIgnoreCase(args[0])
            && "counter-message-status".equalsIgnoreCase(args[1]);
    }

    static LifecycleStatus inspect(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperManualMessageAcknowledgmentRepository.StoredAcknowledgment acknowledgment,
        SleeperManualMessageOutcomeCoordinator.StoredOutcome outcome) {
        if (!isManualMessageHandoff(handoff)) {
            throw new IllegalStateException("trusted handoff is not a manual Sleeper negotiation-message handoff");
        }

        if (acknowledgment != null) {
            requireAcknowledgmentMatches(handoff, acknowledgment);
        }
        if (outcome != null) {
            if (acknowledgment == null) {
                throw new IllegalStateException(
                    "terminal manual-message outcome exists without durable acknowledgment evidence");
            }
            requireOutcomeMatches(handoff, acknowledgment, outcome);
        }

        State state = outcome != null
            ? State.FINALIZED
            : acknowledgment != null
                ? State.ACKNOWLEDGED_PENDING_FINALIZATION
                : State.PENDING_ACKNOWLEDGMENT;
        return new LifecycleStatus(
            state,
            handoff,
            acknowledgment,
            outcome);
    }

    static void print(LifecycleStatus status) {
        Objects.requireNonNull(status, "status must not be null");
        var handoff = status.handoff();

        System.out.println("Trade counter manual message lifecycle status");
        System.out.println("Trusted grant ID: " + handoff.grantId());
        System.out.println("Execution claim ID: " + handoff.claimId());
        System.out.println("Execution attempt ID: " + handoff.attemptId());
        System.out.println("Handoff presentation ID: " + handoff.handoffId());
        System.out.println("Manager destination ID: " + handoff.destination().id());
        System.out.println("Payload SHA-256: " + handoff.payloadSha256());
        System.out.println("Lifecycle state: " + status.state());

        if (status.acknowledgment() == null) {
            System.out.println("Acknowledgment evidence: NOT_RECORDED");
            System.out.println("Finalization evidence: NOT_APPLIED");
            System.out.println("Next safe action: send the reviewed message manually outside Butler, then record exact acknowledgment evidence.");
        } else {
            var acknowledgment = status.acknowledgment();
            System.out.println("Acknowledgment evidence: RECORDED");
            System.out.println("Acknowledgment ID: " + acknowledgment.acknowledgmentId());
            System.out.println("Acknowledged at: " + acknowledgment.acknowledgedAt());
            System.out.println("Human-send confirmation: " + acknowledgment.confirmation()
                + " (Butler did not send it).");

            if (status.outcome() == null) {
                System.out.println("Finalization evidence: NOT_APPLIED");
                System.out.println("Next safe action: run trade counter-message-finalize for this trusted grant if local finalization is intended.");
            } else {
                var outcome = status.outcome();
                System.out.println("Finalization evidence: APPLIED");
                System.out.println("Terminal outcome ID: " + outcome.outcomeId());
                System.out.println("Execution terminal state: " + outcome.terminalState());
                System.out.println("Authorization disposition: " + outcome.grantDisposition());
                System.out.println("Applied at: " + outcome.appliedAt());
                System.out.println("Lifecycle is complete in local Butler state.");
            }
        }

        System.out.println("Inspection only; this command does not send or modify a Sleeper message.");
        System.out.println("This command does not acknowledge, finalize, change execution state, or consume authorization.");
        System.out.println("This command performs no Sleeper write or private API call.");
    }

    static void printUnavailable(String grantId) {
        grantId = requireText(grantId, "grantId");
        System.out.println("Trade counter manual message lifecycle status unavailable");
        System.out.println("Trusted grant ID: " + grantId);
        System.out.println("Reason: no matching durable manual message handoff was found.");
        System.out.println("Inspection only; no local lifecycle state changed and no Sleeper action occurred.");
    }

    static void printUsage() {
        System.out.println("  butler trade counter-message-status <trusted-grant-id>");
        System.out.println("  Reads trusted persisted handoff, acknowledgment, and terminal-outcome evidence for one manual message lifecycle.");
        System.out.println("  Reports PENDING_ACKNOWLEDGMENT, ACKNOWLEDGED_PENDING_FINALIZATION, or FINALIZED.");
        System.out.println("  This inspection does not acknowledge or finalize anything and performs no Sleeper write/private API call.");
    }

    private static boolean isManualMessageHandoff(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff) {
        return handoff != null
            && handoff.action() == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
            && handoff.destination().type() == TradeCounterAuthorizationPolicy.DestinationType.MANAGER
            && handoff.reconciliationMode()
                == SleeperManualCounterHandoffService.ReconciliationMode.NO_OFFICIAL_READBACK;
    }

    private static void requireAcknowledgmentMatches(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperManualMessageAcknowledgmentRepository.StoredAcknowledgment acknowledgment) {
        if (!handoff.claimId().equals(acknowledgment.claimId())
            || !handoff.attemptId().equals(acknowledgment.attemptId())
            || !handoff.grantId().equals(acknowledgment.grantId())
            || !handoff.handoffId().equals(acknowledgment.handoffId())
            || !handoff.payloadSha256().equals(acknowledgment.payloadSha256())
            || !handoff.destination().id().equals(acknowledgment.destinationId())) {
            throw new IllegalStateException(
                "durable manual-message acknowledgment does not match trusted handoff coordinates");
        }
    }

    private static void requireOutcomeMatches(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperManualMessageAcknowledgmentRepository.StoredAcknowledgment acknowledgment,
        SleeperManualMessageOutcomeCoordinator.StoredOutcome outcome) {
        if (!acknowledgment.acknowledgmentId().equals(outcome.acknowledgmentId())
            || !handoff.claimId().equals(outcome.claimId())
            || !handoff.attemptId().equals(outcome.attemptId())
            || !handoff.grantId().equals(outcome.grantId())
            || !handoff.handoffId().equals(outcome.handoffId())
            || !handoff.payloadSha256().equals(outcome.payloadSha256())
            || !handoff.destination().id().equals(outcome.destinationId())
            || !acknowledgment.confirmation().equals(outcome.confirmation())
            || !acknowledgment.acknowledgedAt().equals(outcome.acknowledgedAt())
            || outcome.terminalState() != TradeCounterExecutionAttemptRepository.State.SUCCEEDED
            || !"CONSUME".equals(outcome.grantDisposition())) {
            throw new IllegalStateException(
                "terminal manual-message outcome does not match trusted acknowledgment lifecycle coordinates");
        }
    }

    enum State {
        PENDING_ACKNOWLEDGMENT,
        ACKNOWLEDGED_PENDING_FINALIZATION,
        FINALIZED
    }

    record LifecycleStatus(
        State state,
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperManualMessageAcknowledgmentRepository.StoredAcknowledgment acknowledgment,
        SleeperManualMessageOutcomeCoordinator.StoredOutcome outcome) {
        LifecycleStatus {
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(handoff, "handoff must not be null");
            if ((state == State.PENDING_ACKNOWLEDGMENT) != (acknowledgment == null)) {
                throw new IllegalArgumentException(
                    "pending acknowledgment is the only lifecycle state without acknowledgment evidence");
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
