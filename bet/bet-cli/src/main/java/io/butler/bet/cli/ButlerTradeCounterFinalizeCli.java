package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.integration.sleeper.SleeperCounterTradeOutcomeCoordinator;
import io.butler.bet.integration.sleeper.SleeperCounterTradeReconciliationOutcomePolicy;
import io.butler.bet.integration.sleeper.SleeperCounterTradeSnapshotReconciliationService;
import io.butler.bet.integration.sleeper.SleeperReadOnlyClient;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Explicit local finalization surface for one manually completed Sleeper counter trade.
 * Sleeper access is read-only; only Butler's local execution/grant state may be finalized.
 */
public final class ButlerTradeCounterFinalizeCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeCounterFinalizeCli() {}

    public static void main(String[] args) {
        Options options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
            return;
        }

        try {
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            var report = new SleeperCounterTradeSnapshotReconciliationService(
                database, SleeperReadOnlyClient.official())
                .reconcile(options.grantId(), options.week());
            var decision = SleeperCounterTradeReconciliationOutcomePolicy.classify(report);
            var application = new SleeperCounterTradeOutcomeCoordinator(database)
                .apply(decision, Instant.now());
            print(report, decision, application);
        } catch (SQLException e) {
            System.err.println("Database error while finalizing counter trade: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Sleeper read error while finalizing counter trade: " + e.getMessage());
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Sleeper read interrupted while finalizing counter trade.");
            System.exit(3);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(4);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 4) {
            throw new IllegalArgumentException(
                "trade counter-finalize requires exactly one trusted grant ID and explicit Sleeper week");
        }
        String grantId = requireText(args[2], "trusted-grant-id");
        int week;
        try {
            week = Integer.parseInt(requireText(args[3], "sleeper-week"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("sleeper-week must be an integer from 1 through 30");
        }
        if (week < 1 || week > 30) {
            throw new IllegalArgumentException("sleeper-week must be from 1 through 30");
        }
        return new Options(grantId, week);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "trade".equalsIgnoreCase(args[0])
            && "counter-finalize".equalsIgnoreCase(args[1]);
    }

    static void print(
        SleeperCounterTradeSnapshotReconciliationService.Report report,
        SleeperCounterTradeReconciliationOutcomePolicy.Decision decision,
        SleeperCounterTradeOutcomeCoordinator.ApplyResult application) {
        if (report == null || decision == null || application == null) {
            throw new IllegalArgumentException("counter finalization output inputs must not be null");
        }
        requireMatching(report, decision);

        System.out.println("Trade counter Sleeper finalization");
        System.out.println("Trusted grant ID: " + report.grantId());
        System.out.println("Sleeper week: " + report.week());
        System.out.println("Reconciliation service: " + report.serviceId());
        System.out.println("Reconciliation service state: " + report.state());
        System.out.println("Reconciliation service reason: " + report.reason());
        if (report.state() == SleeperCounterTradeSnapshotReconciliationService.State.RECONCILED) {
            System.out.println("Reconciliation state: " + report.reconciliation().state());
            System.out.println("Matching transaction IDs: " + report.reconciliation().matchingTransactionIds());
        } else {
            System.out.println("No Sleeper transaction evidence was evaluated.");
        }

        System.out.println("Outcome eligibility policy: " + decision.policyId());
        System.out.println("Outcome eligibility state: " + decision.state());
        System.out.println("Outcome eligibility reason code: " + decision.reasonCode());
        System.out.println("Terminal outcome eligibility: " + decision.terminalOutcomeEligibility());
        System.out.println("Finalization coordinator: "
            + SleeperCounterTradeOutcomeCoordinator.COORDINATOR_POLICY_ID);
        System.out.println("Finalization state: " + application.state());
        System.out.println("Finalization reason: " + application.reason());

        if (application.outcome() != null) {
            var outcome = application.outcome();
            System.out.println("Finalized attempt ID: " + outcome.attemptId());
            System.out.println("Completed Sleeper transaction ID: " + outcome.sleeperTransactionId());
            System.out.println("Terminal execution state: " + outcome.terminalState());
            System.out.println("Authorization disposition: " + outcome.grantDisposition());
            System.out.println("Finalization applied at: " + outcome.appliedAt());
            System.out.println("Local Butler execution is SUCCEEDED and the one-shot authorization is consumed.");
        } else {
            System.out.println("No local execution finalization was applied by this command invocation.");
        }

        System.out.println("Sleeper access is GET-only; Butler does not submit, accept, reject, or alter the Sleeper trade.");
        System.out.println("NO_MATCH, PENDING, AMBIGUOUS, or INCONCLUSIVE evidence never finalizes failure or success.");
    }

    static void printUsage() {
        System.out.println("  butler trade counter-finalize <trusted-grant-id> <sleeper-week>");
        System.out.println("  Sleeper week is explicit (1-30); Butler does not infer it.");
        System.out.println("  Butler rereads official Sleeper transaction evidence and requires BF-409 CONFIRMED_SUCCESS_EVIDENCE.");
        System.out.println("  Only exact completed readback may atomically mark the local attempt SUCCEEDED and consume the one-shot authorization.");
        System.out.println("  Sleeper access remains GET-only; this command performs no Sleeper write action.");
    }

    record Options(String grantId, int week) {
        Options {
            grantId = requireText(grantId, "grantId");
            if (week < 1 || week > 30) throw new IllegalArgumentException("week must be from 1 through 30");
        }
    }

    private static void requireMatching(
        SleeperCounterTradeSnapshotReconciliationService.Report report,
        SleeperCounterTradeReconciliationOutcomePolicy.Decision decision) {
        if (!report.grantId().equals(decision.grantId())
            || report.week() != decision.week()
            || !report.serviceId().equals(decision.reconciliationServiceId())) {
            throw new IllegalArgumentException(
                "outcome eligibility decision does not match reconciliation report");
        }
        if (report.state() == SleeperCounterTradeSnapshotReconciliationService.State.RECONCILED) {
            if (!report.claimId().equals(decision.claimId())
                || !report.handoffId().equals(decision.handoffId())
                || !report.movementSha256().equals(decision.movementSha256())) {
                throw new IllegalArgumentException(
                    "outcome eligibility coordinates do not match reconciliation report");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
