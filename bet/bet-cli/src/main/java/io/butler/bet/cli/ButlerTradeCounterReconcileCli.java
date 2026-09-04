package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.integration.sleeper.SleeperCounterTradeReconciliationOutcomePolicy;
import io.butler.bet.integration.sleeper.SleeperCounterTradeSnapshotReconciliationService;
import io.butler.bet.integration.sleeper.SleeperReadOnlyClient;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** Read-only Sleeper trade reconciliation surface over trusted frozen counter-handoff evidence. */
public final class ButlerTradeCounterReconcileCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeCounterReconcileCli() {}

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
            print(report);
        } catch (SQLException e) {
            System.err.println("Database error while reconciling counter trade: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Sleeper read error while reconciling counter trade: " + e.getMessage());
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Sleeper read interrupted while reconciling counter trade.");
            System.exit(3);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(4);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 4) {
            throw new IllegalArgumentException(
                "trade counter-reconcile requires exactly one trusted grant ID and explicit Sleeper week");
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
            && "counter-reconcile".equalsIgnoreCase(args[1]);
    }

    static void print(SleeperCounterTradeSnapshotReconciliationService.Report report) {
        if (report == null) throw new IllegalArgumentException("reconciliation report must not be null");
        var outcome = SleeperCounterTradeReconciliationOutcomePolicy.classify(report);

        System.out.println("Trade counter Sleeper reconciliation");
        System.out.println("Reconciliation service: " + report.serviceId());
        System.out.println("Trusted grant ID: " + report.grantId());
        System.out.println("Sleeper week: " + report.week());
        System.out.println("Service state: " + report.state());
        System.out.println("Service reason: " + report.reason());

        if (report.state() == SleeperCounterTradeSnapshotReconciliationService.State.RECONCILED) {
            var reconciliation = report.reconciliation();
            System.out.println("Execution claim ID: " + report.claimId());
            System.out.println("Handoff presentation ID: " + report.handoffId());
            System.out.println("Frozen movement SHA-256: " + report.movementSha256());
            System.out.println("Not-before epoch millis: " + report.notBeforeEpochMillis());
            System.out.println("Observed Sleeper transactions: " + report.observedTransactions().size());
            System.out.println("Reconciliation policy: " + reconciliation.policyId());
            System.out.println("Reconciliation state: " + reconciliation.state());
            System.out.println("Reconciliation reason: " + reconciliation.reason());
            System.out.println("Matching transaction IDs: " + reconciliation.matchingTransactionIds());
            System.out.println("Reconciliation evidence incomplete: "
                + reconciliation.reconciliationEvidenceIncomplete());
        } else {
            System.out.println("No Sleeper transaction evidence was evaluated.");
        }

        System.out.println("Outcome eligibility policy: " + outcome.policyId());
        System.out.println("Outcome eligibility state: " + outcome.state());
        System.out.println("Outcome eligibility reason code: " + outcome.reasonCode());
        System.out.println("Terminal outcome eligibility: " + outcome.terminalOutcomeEligibility());
        System.out.println("Outcome eligibility transaction IDs: " + outcome.transactionIds());
        System.out.println("Outcome eligibility reason: " + outcome.reason());
        if (outcome.terminalOutcomeEligibility()
            == SleeperCounterTradeReconciliationOutcomePolicy.TerminalOutcomeEligibility.CONFIRMED_SUCCESS) {
            System.out.println("Exact completed readback is eligible for a separate governed success-finalization step.");
        } else {
            System.out.println("No terminal execution finalization is eligible from this reconciliation evidence.");
        }

        System.out.println("Read-only reconciliation and eligibility evaluation only; no trade is sent or changed.");
        System.out.println("This command does not mark execution SUCCEEDED, FAILED, or UNKNOWN.");
        System.out.println("This command does not consume the authorization grant.");
    }

    static void printUsage() {
        System.out.println("  butler trade counter-reconcile <trusted-grant-id> <sleeper-week>");
        System.out.println("  Sleeper week is explicit (1-30); Butler does not infer it.");
        System.out.println("  Butler loads only trusted persisted handoff/snapshot evidence and uses the official Sleeper transactions GET endpoint.");
        System.out.println("  BF-409 outcome eligibility is displayed, but this command remains read-only and does not finalize execution state.");
    }

    record Options(String grantId, int week) {
        Options {
            grantId = requireText(grantId, "grantId");
            if (week < 1 || week > 30) throw new IllegalArgumentException("week must be from 1 through 30");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
