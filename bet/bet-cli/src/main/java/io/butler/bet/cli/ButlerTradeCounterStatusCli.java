package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.execution.TradeCounterExecutionOutcomePolicy;
import io.butler.bet.integration.sleeper.SleeperCounterTradeExpectationSnapshotRepository;
import io.butler.bet.integration.sleeper.SleeperCounterTradeNoActionResolutionRepository;
import io.butler.bet.integration.sleeper.SleeperCounterTradeOutcomeCoordinator;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffService;
import io.butler.bet.integration.sleeper.SleeperManualCounterNoActionAcknowledgmentPolicy;
import io.butler.bet.integration.sleeper.SleeperManualCounterNoActionAcknowledgmentRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterNoActionOutcomeCoordinator;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;

import java.nio.file.Path;
import java.sql.SQLException;
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
            var handoff = new SleeperManualCounterHandoffRepository(database)
                .findByGrantId(grantId).orElse(null);
            if (handoff == null
                || handoff.action() != TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE
                || handoff.reconciliationMode()
                    != SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK) {
                printUnavailable(grantId);
                return;
            }

            var snapshot = new SleeperCounterTradeExpectationSnapshotRepository(database)
                .findByClaimId(handoff.claimId()).orElse(null);
            var successOutcome = new SleeperCounterTradeOutcomeCoordinator(database)
                .findByClaimId(handoff.claimId()).orElse(null);
            var noActionAcknowledgment = new SleeperManualCounterNoActionAcknowledgmentRepository(database)
                .findByClaimId(handoff.claimId()).orElse(null);
            var noActionOutcome = new SleeperManualCounterNoActionOutcomeCoordinator(database)
                .findByClaimId(handoff.claimId()).orElse(null);
            var noActionResolution = new SleeperCounterTradeNoActionResolutionRepository(database)
                .findByClaimId(handoff.claimId()).orElse(null);
            print(inspect(
                handoff,
                snapshot,
                successOutcome,
                noActionAcknowledgment,
                noActionOutcome,
                noActionResolution));
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
        SleeperCounterTradeOutcomeCoordinator.StoredOutcome successOutcome) {
        return inspect(handoff, snapshot, successOutcome, null, null, null);
    }

    static LifecycleStatus inspect(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperCounterTradeExpectationSnapshotRepository.Snapshot snapshot,
        SleeperCounterTradeOutcomeCoordinator.StoredOutcome successOutcome,
        SleeperManualCounterNoActionAcknowledgmentRepository.StoredAcknowledgment noActionAcknowledgment,
        SleeperManualCounterNoActionOutcomeCoordinator.StoredOutcome noActionOutcome) {
        return inspect(
            handoff,
            snapshot,
            successOutcome,
            noActionAcknowledgment,
            noActionOutcome,
            null);
    }

    static LifecycleStatus inspect(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperCounterTradeExpectationSnapshotRepository.Snapshot snapshot,
        SleeperCounterTradeOutcomeCoordinator.StoredOutcome successOutcome,
        SleeperManualCounterNoActionAcknowledgmentRepository.StoredAcknowledgment noActionAcknowledgment,
        SleeperManualCounterNoActionOutcomeCoordinator.StoredOutcome noActionOutcome,
        SleeperCounterTradeNoActionResolutionRepository.StoredResolution noActionResolution) {
        Objects.requireNonNull(handoff, "handoff must not be null");
        if (handoff.action() != TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE
            || handoff.destination().type() != TradeCounterAuthorizationPolicy.DestinationType.LEAGUE
            || handoff.reconciliationMode()
                != SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK) {
            throw new IllegalStateException("trusted handoff is not a manual Sleeper trade handoff");
        }

        if (snapshot != null) requireSnapshotMatches(handoff, snapshot);
        if (successOutcome != null) {
            if (snapshot == null) {
                throw new IllegalStateException(
                    "terminal manual-trade success outcome exists without provider expectation snapshot evidence");
            }
            requireSuccessOutcomeMatches(handoff, snapshot, successOutcome);
        }
        if (noActionAcknowledgment != null) {
            requireNoActionAcknowledgmentMatches(handoff, noActionAcknowledgment);
        }
        if (noActionOutcome != null) {
            if (noActionAcknowledgment == null) {
                throw new IllegalStateException(
                    "terminal manual no-action outcome exists without durable no-action acknowledgment evidence");
            }
            requireNoActionOutcomeMatches(handoff, noActionAcknowledgment, noActionOutcome);
        }
        if (noActionResolution != null) {
            if (snapshot == null || noActionAcknowledgment == null) {
                throw new IllegalStateException(
                    "trade no-action resolution requires provider snapshot and durable no-action acknowledgment evidence");
            }
            requireNoActionResolutionMatches(
                handoff,
                snapshot,
                successOutcome,
                noActionAcknowledgment,
                noActionOutcome,
                noActionResolution);
        }

        if (successOutcome != null && noActionOutcome != null) {
            throw new IllegalStateException(
                "terminal trade success cannot coexist with a finalized no-action terminal outcome");
        }
        if (successOutcome != null && noActionAcknowledgment != null
            && (noActionResolution == null
                || noActionResolution.resolutionType()
                    != SleeperCounterTradeNoActionResolutionRepository.ResolutionType.SUPERSEDED_BY_CONFIRMED_TRADE)) {
            throw new IllegalStateException(
                "trade success and no-action acknowledgment coexist without governed supersession evidence");
        }
        if (noActionResolution != null
            && noActionResolution.resolutionType()
                == SleeperCounterTradeNoActionResolutionRepository.ResolutionType.SUPERSEDED_BY_CONFIRMED_TRADE
            && successOutcome == null) {
            throw new IllegalStateException(
                "superseded trade no-action acknowledgment exists without its atomic success outcome");
        }
        if (noActionResolution != null
            && noActionResolution.resolutionType()
                == SleeperCounterTradeNoActionResolutionRepository.ResolutionType.POST_CLOSURE_EXTERNAL_ACTION
            && noActionOutcome == null) {
            throw new IllegalStateException(
                "post-closure trade discrepancy exists without the finalized no-action terminal outcome");
        }

        State state;
        if (noActionResolution != null
            && noActionResolution.resolutionType()
                == SleeperCounterTradeNoActionResolutionRepository.ResolutionType.POST_CLOSURE_EXTERNAL_ACTION) {
            state = State.POST_CLOSURE_EXTERNAL_ACTION_DISCREPANCY;
        } else if (successOutcome != null && noActionResolution != null) {
            state = State.FINALIZED_AFTER_NO_ACTION_SUPERSESSION;
        } else if (noActionOutcome != null) {
            state = State.NO_ACTION_FINALIZED;
        } else if (noActionAcknowledgment != null) {
            state = State.NO_ACTION_ACKNOWLEDGED_PENDING_FINALIZATION;
        } else if (successOutcome != null) {
            state = State.FINALIZED;
        } else if (snapshot != null) {
            state = State.LOCAL_UNFINALIZED;
        } else {
            state = State.SNAPSHOT_MISSING;
        }
        return new LifecycleStatus(
            state,
            handoff,
            snapshot,
            successOutcome,
            noActionAcknowledgment,
            noActionOutcome,
            noActionResolution);
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
        } else {
            var snapshot = status.snapshot();
            System.out.println("Provider expectation snapshot: RECORDED");
            System.out.println("Sleeper league ID snapshot: " + snapshot.sleeperLeagueId());
            System.out.println("Frozen movement SHA-256: " + snapshot.movementSha256());
            System.out.println("Snapshot captured at: " + snapshot.snapshottedAt());
        }

        if (status.state() == State.FINALIZED_AFTER_NO_ACTION_SUPERSESSION) {
            var acknowledgment = status.noActionAcknowledgment();
            var resolution = status.noActionResolution();
            var outcome = status.successOutcome();
            System.out.println("No-action acknowledgment evidence: RECORDED_HISTORICAL");
            System.out.println("No-action acknowledgment ID: " + acknowledgment.acknowledgmentId());
            System.out.println("No-action confirmation: " + acknowledgment.confirmation());
            System.out.println("No-action resolution: " + resolution.resolutionType());
            System.out.println("Resolution ID: " + resolution.resolutionId());
            System.out.println("Resolution transaction ID: " + resolution.sleeperTransactionId());
            System.out.println("Resolution Sleeper week: " + resolution.sleeperWeek());
            System.out.println("Local terminal outcome: RECORDED_SUCCESS");
            printSuccessOutcome(outcome);
            System.out.println("The historical no-action acknowledgment remains immutable but was superseded before local closure by exact completed Sleeper readback.");
        } else if (status.state() == State.POST_CLOSURE_EXTERNAL_ACTION_DISCREPANCY) {
            var acknowledgment = status.noActionAcknowledgment();
            var outcome = status.noActionOutcome();
            var resolution = status.noActionResolution();
            System.out.println("No-action acknowledgment evidence: RECORDED");
            System.out.println("No-action acknowledgment ID: " + acknowledgment.acknowledgmentId());
            System.out.println("No-action confirmation: " + acknowledgment.confirmation());
            System.out.println("Local terminal outcome: RECORDED_NO_ACTION");
            printNoActionOutcome(outcome);
            System.out.println("No-action resolution: " + resolution.resolutionType());
            System.out.println("Discrepancy resolution ID: " + resolution.resolutionId());
            System.out.println("Observed completed Sleeper transaction ID: " + resolution.sleeperTransactionId());
            System.out.println("Observed Sleeper week: " + resolution.sleeperWeek());
            System.out.println("INVESTIGATION REQUIRED: exact completed readback appeared after the local no-action lifecycle was already closed.");
            System.out.println("The historical FAILED terminal state and consumed authorization were not rewritten.");
        } else if (status.noActionAcknowledgment() != null) {
            var acknowledgment = status.noActionAcknowledgment();
            System.out.println("No-action acknowledgment evidence: RECORDED");
            System.out.println("No-action acknowledgment ID: " + acknowledgment.acknowledgmentId());
            System.out.println("No-action confirmation: " + acknowledgment.confirmation());
            System.out.println("No-action acknowledged at: " + acknowledgment.acknowledgedAt());
            System.out.println("External Sleeper completion: NOT_INFERRED");
            if (status.noActionOutcome() == null) {
                System.out.println("Local terminal outcome: NOT_RECORDED");
                System.out.println("Next safe action: run trade counter-no-action-finalize for this trusted grant only if the no-action acknowledgment remains correct.");
            } else {
                System.out.println("Local terminal outcome: RECORDED_NO_ACTION");
                printNoActionOutcome(status.noActionOutcome());
                System.out.println("Local Butler lifecycle is closed from explicit durable no-action evidence; any retry requires fresh explicit authorization.");
            }
        } else if (status.successOutcome() != null) {
            System.out.println("No-action acknowledgment evidence: NOT_RECORDED");
            System.out.println("Local terminal outcome: RECORDED_SUCCESS");
            printSuccessOutcome(status.successOutcome());
            System.out.println("Local Butler lifecycle is complete from previously verified exact completed readback.");
        } else {
            System.out.println("No-action acknowledgment evidence: NOT_RECORDED");
            System.out.println("Local terminal outcome: NOT_RECORDED");
            System.out.println("External Sleeper completion: NOT_INFERRED");
            if (status.snapshot() == null) {
                System.out.println("Next safe action: reconstruct a governed handoff that includes a provider expectation snapshot before reconciliation, or record exact no-action evidence if this handoff was never acted on.");
            } else {
                System.out.println("Next safe action: run trade counter-reconcile with an explicit Sleeper week for live GET-only evidence, or record exact no-action evidence if this handoff was never acted on.");
            }
        }

        System.out.println("Local inspection only; this command performs no Sleeper request.");
        System.out.println("This command does not submit, accept, reject, alter, reconcile, acknowledge, or finalize a trade.");
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
        System.out.println("  Reads only local persisted trade handoff, provider snapshot, success outcome, no-action evidence, and governed no-action resolution evidence.");
        System.out.println("  Reports ordinary pending/success states, no-action pending/finalized states, superseded success, or post-closure external-action discrepancy without inferring current Sleeper state.");
        System.out.println("  This command performs no Sleeper request and does not acknowledge, reconcile, resolve, or finalize anything.");
    }

    private static void printSuccessOutcome(SleeperCounterTradeOutcomeCoordinator.StoredOutcome outcome) {
        System.out.println("Completed Sleeper transaction ID: " + outcome.sleeperTransactionId());
        System.out.println("Sleeper week used for finalization: " + outcome.sleeperWeek());
        System.out.println("Terminal execution state: " + outcome.terminalState());
        System.out.println("Authorization disposition: " + outcome.grantDisposition());
        System.out.println("Finalization applied at: " + outcome.appliedAt());
    }

    private static void printNoActionOutcome(SleeperManualCounterNoActionOutcomeCoordinator.StoredOutcome outcome) {
        System.out.println("Terminal outcome ID: " + outcome.outcomeId());
        System.out.println("Terminal execution state: " + outcome.terminalState());
        System.out.println("Authorization disposition: " + outcome.grantDisposition());
        System.out.println("Finalization applied at: " + outcome.appliedAt());
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

    private static void requireSuccessOutcomeMatches(
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
                "terminal manual-trade success outcome does not match trusted local lifecycle coordinates");
        }
    }

    private static void requireNoActionAcknowledgmentMatches(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperManualCounterNoActionAcknowledgmentRepository.StoredAcknowledgment acknowledgment) {
        if (!handoff.claimId().equals(acknowledgment.claimId())
            || !handoff.attemptId().equals(acknowledgment.attemptId())
            || !handoff.grantId().equals(acknowledgment.grantId())
            || !handoff.handoffId().equals(acknowledgment.handoffId())
            || !handoff.payloadSha256().equals(acknowledgment.payloadSha256())
            || handoff.action() != acknowledgment.action()
            || !handoff.destination().equals(acknowledgment.destination())
            || !SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION
                .equals(acknowledgment.confirmation())
            || acknowledgment.localTerminalEligibility()
                != SleeperManualCounterNoActionAcknowledgmentPolicy.LocalTerminalEligibility.CONFIRMED_NO_ACTION_FAILURE
            || acknowledgment.attemptTerminalState() != TradeCounterExecutionAttemptRepository.State.FAILED
            || acknowledgment.grantDisposition() != TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME) {
            throw new IllegalStateException(
                "durable manual-trade no-action acknowledgment does not match trusted handoff coordinates");
        }
    }

    private static void requireNoActionOutcomeMatches(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperManualCounterNoActionAcknowledgmentRepository.StoredAcknowledgment acknowledgment,
        SleeperManualCounterNoActionOutcomeCoordinator.StoredOutcome outcome) {
        if (!acknowledgment.acknowledgmentId().equals(outcome.acknowledgmentId())
            || !handoff.claimId().equals(outcome.claimId())
            || !handoff.attemptId().equals(outcome.attemptId())
            || !handoff.grantId().equals(outcome.grantId())
            || !handoff.handoffId().equals(outcome.handoffId())
            || !handoff.payloadSha256().equals(outcome.payloadSha256())
            || handoff.action() != outcome.action()
            || !handoff.destination().equals(outcome.destination())
            || !acknowledgment.confirmation().equals(outcome.confirmation())
            || !acknowledgment.acknowledgedAt().equals(outcome.acknowledgedAt())
            || outcome.terminalState() != TradeCounterExecutionAttemptRepository.State.FAILED
            || outcome.grantDisposition() != TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME) {
            throw new IllegalStateException(
                "terminal manual-trade no-action outcome does not match trusted acknowledgment lifecycle coordinates");
        }
    }

    private static void requireNoActionResolutionMatches(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperCounterTradeExpectationSnapshotRepository.Snapshot snapshot,
        SleeperCounterTradeOutcomeCoordinator.StoredOutcome successOutcome,
        SleeperManualCounterNoActionAcknowledgmentRepository.StoredAcknowledgment acknowledgment,
        SleeperManualCounterNoActionOutcomeCoordinator.StoredOutcome noActionOutcome,
        SleeperCounterTradeNoActionResolutionRepository.StoredResolution resolution) {
        boolean baseMatches = acknowledgment.acknowledgmentId().equals(resolution.acknowledgmentId())
            && handoff.claimId().equals(resolution.claimId())
            && handoff.attemptId().equals(resolution.attemptId())
            && handoff.grantId().equals(resolution.grantId())
            && handoff.handoffId().equals(resolution.handoffId())
            && handoff.payloadSha256().equals(resolution.payloadSha256())
            && snapshot.movementSha256().equals(resolution.movementSha256());
        if (!baseMatches) {
            throw new IllegalStateException(
                "trade no-action resolution does not match trusted handoff and provider snapshot coordinates");
        }

        if (resolution.resolutionType()
            == SleeperCounterTradeNoActionResolutionRepository.ResolutionType.SUPERSEDED_BY_CONFIRMED_TRADE) {
            if (successOutcome == null
                || noActionOutcome != null
                || resolution.noActionTerminalOutcomeId() != null
                || successOutcome.sleeperWeek() != resolution.sleeperWeek()
                || !successOutcome.sleeperTransactionId().equals(resolution.sleeperTransactionId())
                || !successOutcome.movementSha256().equals(resolution.movementSha256())) {
                throw new IllegalStateException(
                    "trade no-action supersession does not match the atomic success outcome");
            }
            return;
        }

        if (successOutcome != null
            || noActionOutcome == null
            || !noActionOutcome.outcomeId().equals(resolution.noActionTerminalOutcomeId())) {
            throw new IllegalStateException(
                "post-closure trade discrepancy does not match the finalized no-action outcome");
        }
    }

    enum State {
        SNAPSHOT_MISSING,
        LOCAL_UNFINALIZED,
        FINALIZED,
        FINALIZED_AFTER_NO_ACTION_SUPERSESSION,
        NO_ACTION_ACKNOWLEDGED_PENDING_FINALIZATION,
        NO_ACTION_FINALIZED,
        POST_CLOSURE_EXTERNAL_ACTION_DISCREPANCY
    }

    record LifecycleStatus(
        State state,
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperCounterTradeExpectationSnapshotRepository.Snapshot snapshot,
        SleeperCounterTradeOutcomeCoordinator.StoredOutcome successOutcome,
        SleeperManualCounterNoActionAcknowledgmentRepository.StoredAcknowledgment noActionAcknowledgment,
        SleeperManualCounterNoActionOutcomeCoordinator.StoredOutcome noActionOutcome,
        SleeperCounterTradeNoActionResolutionRepository.StoredResolution noActionResolution) {
        LifecycleStatus {
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(handoff, "handoff must not be null");
            boolean successState = state == State.FINALIZED
                || state == State.FINALIZED_AFTER_NO_ACTION_SUPERSESSION;
            boolean noActionAcknowledgmentState = state == State.NO_ACTION_ACKNOWLEDGED_PENDING_FINALIZATION
                || state == State.NO_ACTION_FINALIZED
                || state == State.FINALIZED_AFTER_NO_ACTION_SUPERSESSION
                || state == State.POST_CLOSURE_EXTERNAL_ACTION_DISCREPANCY;
            boolean noActionOutcomeState = state == State.NO_ACTION_FINALIZED
                || state == State.POST_CLOSURE_EXTERNAL_ACTION_DISCREPANCY;
            boolean resolutionState = state == State.FINALIZED_AFTER_NO_ACTION_SUPERSESSION
                || state == State.POST_CLOSURE_EXTERNAL_ACTION_DISCREPANCY;

            if (state == State.SNAPSHOT_MISSING && snapshot != null) {
                throw new IllegalArgumentException("snapshot-missing lifecycle state cannot carry a provider snapshot");
            }
            if ((state == State.LOCAL_UNFINALIZED || successState || resolutionState) && snapshot == null) {
                throw new IllegalArgumentException("trade success/resolution lifecycle states require provider snapshot evidence");
            }
            if (successState != (successOutcome != null)) {
                throw new IllegalArgumentException("only success lifecycle states may carry a trade success outcome");
            }
            if (noActionAcknowledgmentState != (noActionAcknowledgment != null)) {
                throw new IllegalArgumentException("no-action lifecycle states require durable no-action acknowledgment evidence");
            }
            if (noActionOutcomeState != (noActionOutcome != null)) {
                throw new IllegalArgumentException("only finalized no-action states may carry a no-action terminal outcome");
            }
            if (resolutionState != (noActionResolution != null)) {
                throw new IllegalArgumentException("only governed resolution states may carry trade no-action resolution evidence");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
