package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterAuthorizationGrantRepository;
import io.butler.bet.data.TradeCounterAuthorizationReplayContextRepository;
import io.butler.bet.execution.TradeCounterManualHandoffCoordinator;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffService;
import io.butler.bet.intelligence.TradeCounterExecutionReadinessPolicy;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;

/** Trusted-grant-only CLI for governed manual Sleeper counter handoff. */
public final class ButlerTradeCounterHandoffCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeCounterHandoffCli() {}

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
            Database database = initializedDatabase();
            var grants = new TradeCounterAuthorizationGrantRepository(database);
            grants.initialize();
            var stored = grants.findById(grantId);
            if (stored.isEmpty()) {
                printGrantUnavailable(grantId);
                return;
            }

            var trusted = stored.get();
            if (trusted.consumed()) {
                printBlocked(ButlerTradeCounterReadinessCli.assessReadiness(trusted, null, null));
                return;
            }

            var replayRepository = new TradeCounterAuthorizationReplayContextRepository(database);
            var replay = replayRepository.findByGrantId(grantId);
            if (replay.isEmpty()) {
                printBlocked(ButlerTradeCounterReadinessCli.assessReadiness(trusted, null, null));
                return;
            }

            var options = ButlerTradeCounterReadinessCli.replayOptions(trusted, replay.get());
            var artifacts = ButlerTradeCounterFreshArtifacts.build(database, options);
            var readiness = ButlerTradeCounterReadinessCli.assessReadiness(
                trusted, replay.get(), artifacts.identity());
            if (readiness.state() != TradeCounterExecutionReadinessPolicy.State.READY) {
                printBlocked(readiness);
                return;
            }

            var coordinated = new TradeCounterManualHandoffCoordinator(database).coordinate(
                trusted.grant(),
                readiness,
                artifacts.identity(),
                artifacts.materialized(),
                artifacts.message(),
                Instant.now());

            if (coordinated.state() != TradeCounterManualHandoffCoordinator.State.HANDOFF_PRESENTED
                && coordinated.state()
                    != TradeCounterManualHandoffCoordinator.State.HANDOFF_ALREADY_PRESENTED) {
                printCoordinatorBlocked(grantId, readiness, coordinated);
                return;
            }

            var handoffResult = new SleeperManualCounterHandoffService(database)
                .prepare(coordinated.claimId());
            if (handoffResult.state() != SleeperManualCounterHandoffService.State.HANDOFF_READY) {
                throw new IllegalStateException(
                    "durably presented handoff could not be reconstructed from its trusted claim");
            }
            var presentation = new SleeperManualCounterHandoffRepository(database)
                .findByClaimId(coordinated.claimId())
                .orElseThrow(() -> new IllegalStateException(
                    "durably presented handoff is missing its presentation record"));
            print(readiness, coordinated, handoffResult.handoff(), presentation);
        } catch (SQLException e) {
            System.err.println("Database error while preparing manual counter handoff: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static String parseGrantId(String[] args) {
        if (!isCommand(args) || args.length != 3) {
            throw new IllegalArgumentException(
                "trade counter-handoff requires exactly one trusted grant ID");
        }
        if (args[2] == null || args[2].isBlank()) {
            throw new IllegalArgumentException("trusted-grant-id must not be blank");
        }
        return args[2].trim();
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "trade".equalsIgnoreCase(args[0])
            && "counter-handoff".equalsIgnoreCase(args[1]);
    }

    static void print(
        TradeCounterExecutionReadinessPolicy.Result readiness,
        TradeCounterManualHandoffCoordinator.Result coordinated,
        SleeperManualCounterHandoffService.Handoff handoff,
        SleeperManualCounterHandoffRepository.PresentedHandoff presentation) {
        if (readiness == null || coordinated == null || handoff == null || presentation == null) {
            throw new IllegalArgumentException("manual handoff output inputs must not be null");
        }
        requireOutputMatches(readiness, coordinated, handoff, presentation);

        System.out.println("Trade counter Sleeper manual handoff");
        System.out.println("Trusted grant ID: " + handoff.grantId());
        System.out.println("Execution readiness: " + readiness.state());
        System.out.println("Handoff coordinator: " + coordinated.coordinatorId());
        System.out.println("Handoff coordinator state: " + coordinated.state());
        System.out.println("Handoff coordinator reason: " + coordinated.reason());
        System.out.println("Execution attempt ID: " + handoff.attemptId());
        System.out.println("Execution claim ID: " + handoff.claimId());
        System.out.println("Handoff presentation ID: " + presentation.handoffId());
        System.out.println("First presented at: " + presentation.presentedAt());
        System.out.println("Authorized action: " + handoff.action());
        System.out.println("Authorized destination: "
            + handoff.destination().type() + ":" + handoff.destination().id());
        System.out.println("Payload kind: " + handoff.payloadKind());
        System.out.println("Payload SHA-256: " + handoff.payloadSha256());
        System.out.println("Reconciliation mode: " + handoff.reconciliationMode());
        System.out.println("Exact governed handoff payload:");
        System.out.println(handoff.payloadText());
        System.out.println("Handoff warning: " + handoff.warning());
        System.out.println("Sleeper's supported public API does not provide write operations; complete this action manually in Sleeper.");
        System.out.println("Displaying this handoff does not prove that the action was completed in Sleeper.");
        if (handoff.reconciliationMode()
            == SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK) {
            System.out.println("Official Sleeper transaction readback is available for this trade handoff.");
            System.out.println("The persisted first-presentation timestamp is the safe not-before boundary for later transaction reconciliation.");
        } else {
            System.out.println("Sleeper provides no supported official message readback for this handoff.");
        }
        System.out.println("The authorization grant remains unconsumed and the execution attempt remains IN_FLIGHT.");
    }

    static void printBlocked(TradeCounterExecutionReadinessPolicy.Result readiness) {
        if (readiness == null) throw new IllegalArgumentException("readiness must not be null");
        System.out.println("Trade counter Sleeper manual handoff unavailable");
        System.out.println("Trusted grant ID: " + readiness.grantId());
        System.out.println("Execution readiness: " + readiness.state());
        System.out.println("Readiness reason: " + readiness.reason());
        System.out.println("No execution attempt is created, no payload is presented, and no external action occurs.");
    }

    static void printCoordinatorBlocked(
        String grantId,
        TradeCounterExecutionReadinessPolicy.Result readiness,
        TradeCounterManualHandoffCoordinator.Result coordinated) {
        if (grantId == null || grantId.isBlank() || readiness == null || coordinated == null) {
            throw new IllegalArgumentException("blocked coordinator output inputs must not be null or blank");
        }
        System.out.println("Trade counter Sleeper manual handoff unavailable");
        System.out.println("Trusted grant ID: " + grantId);
        System.out.println("Execution readiness: " + readiness.state());
        System.out.println("Handoff coordinator state: " + coordinated.state());
        System.out.println("Handoff coordinator reason: " + coordinated.reason());
        System.out.println("No external action occurs.");
    }

    static void printGrantUnavailable(String grantId) {
        if (grantId == null || grantId.isBlank()) {
            throw new IllegalArgumentException("grantId must not be blank");
        }
        System.out.println("Trade counter Sleeper manual handoff unavailable");
        System.out.println("Trusted grant ID: " + grantId);
        System.out.println("Handoff reason: trusted authorization grant was not found.");
        System.out.println("No execution attempt is created and no external action occurs.");
    }

    static void printUsage() {
        System.out.println("  butler trade counter-handoff <trusted-grant-id>");
        System.out.println("  Loads only trusted persisted authorization/replay state, reruns the governed counter from current evidence, and requires fresh READY status.");
        System.out.println("  When READY, Butler derives the exact governed payload, durably prepares/claims the attempt, and presents a manual Sleeper handoff.");
        System.out.println("  No trade, action, destination, or payload may be supplied or overridden on this command.");
        System.out.println("  Sleeper writes remain manual; presentation does not prove completion and does not consume the authorization grant.");
    }

    private static void requireOutputMatches(
        TradeCounterExecutionReadinessPolicy.Result readiness,
        TradeCounterManualHandoffCoordinator.Result coordinated,
        SleeperManualCounterHandoffService.Handoff handoff,
        SleeperManualCounterHandoffRepository.PresentedHandoff presentation) {
        boolean matches = readiness.state() == TradeCounterExecutionReadinessPolicy.State.READY
            && coordinated.state() != null
            && coordinated.attemptId().equals(handoff.attemptId())
            && coordinated.claimId().equals(handoff.claimId())
            && coordinated.handoffId().equals(presentation.handoffId())
            && coordinated.payloadKind() == handoff.payloadKind()
            && coordinated.payloadSha256().equals(handoff.payloadSha256())
            && readiness.grantId().equals(handoff.grantId())
            && handoff.claimId().equals(presentation.claimId())
            && handoff.attemptId().equals(presentation.attemptId())
            && handoff.grantId().equals(presentation.grantId())
            && handoff.proposalFingerprint().equals(presentation.proposalFingerprint())
            && handoff.action() == presentation.action()
            && handoff.destination().equals(presentation.destination())
            && handoff.payloadKind().name().equals(presentation.payloadKind())
            && handoff.payloadSha256().equals(presentation.payloadSha256())
            && handoff.reconciliationMode() == presentation.reconciliationMode();
        if (!matches) {
            throw new IllegalStateException("manual handoff output artifacts do not match");
        }
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }
}
