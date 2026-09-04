package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.integration.sleeper.SleeperManualMessageOutcomeCoordinator;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;

/** Explicit local-only finalization surface for a durably acknowledged manual Sleeper message. */
public final class ButlerTradeCounterMessageFinalizeCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeCounterMessageFinalizeCli() {}

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
            var resolved = ButlerTradeCounterMessageAcknowledgeCli.resolve(database, grantId);
            if (resolved == null) {
                printUnavailable(grantId);
                return;
            }

            var result = new SleeperManualMessageOutcomeCoordinator(database)
                .apply(resolved.claimId(), Instant.now());
            print(grantId, resolved.claimId(), result);
        } catch (SQLException e) {
            System.err.println("Database error while finalizing manual message: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static String parseGrantId(String[] args) {
        if (!isCommand(args) || args.length != 3) {
            throw new IllegalArgumentException(
                "trade counter-message-finalize requires exactly one trusted grant ID");
        }
        return requireText(args[2], "trusted-grant-id");
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "trade".equalsIgnoreCase(args[0])
            && "counter-message-finalize".equalsIgnoreCase(args[1]);
    }

    static void print(
        String grantId,
        String claimId,
        SleeperManualMessageOutcomeCoordinator.ApplyResult result) {
        grantId = requireText(grantId, "grantId");
        claimId = requireText(claimId, "claimId");
        if (result == null) throw new IllegalArgumentException("result must not be null");
        if (result.outcome() != null
            && (!grantId.equals(result.outcome().grantId())
                || !claimId.equals(result.outcome().claimId()))) {
            throw new IllegalStateException(
                "manual-message finalization outcome does not match trusted grant/claim");
        }

        System.out.println("Trade counter manual message finalization");
        System.out.println("Trusted grant ID: " + grantId);
        System.out.println("Execution claim ID: " + claimId);
        System.out.println("Finalization state: " + result.state());
        System.out.println("Finalization reason: " + result.reason());

        if (result.outcome() != null) {
            var outcome = result.outcome();
            System.out.println("Terminal outcome ID: " + outcome.outcomeId());
            System.out.println("Acknowledgment ID: " + outcome.acknowledgmentId());
            System.out.println("Handoff presentation ID: " + outcome.handoffId());
            System.out.println("Payload SHA-256: " + outcome.payloadSha256());
            System.out.println("Manager destination ID: " + outcome.destinationId());
            System.out.println("Acknowledged at: " + outcome.acknowledgedAt());
            System.out.println("Execution terminal state: " + outcome.terminalState());
            System.out.println("Authorization disposition: " + outcome.grantDisposition());
            System.out.println("Applied at: " + outcome.appliedAt());
        } else {
            System.out.println("No terminal execution state or authorization change was applied.");
        }

        System.out.println("Local-only finalization; Butler did not send or modify the Sleeper message.");
        System.out.println("This command performs no Sleeper write or private API call.");
    }

    static void printUnavailable(String grantId) {
        grantId = requireText(grantId, "grantId");
        System.out.println("Trade counter manual message finalization unavailable");
        System.out.println("Trusted grant ID: " + grantId);
        System.out.println("Reason: no matching durable execution claim and manual handoff were found.");
        System.out.println("No terminal execution state or authorization change was applied.");
        System.out.println("No Sleeper action occurred.");
    }

    static void printUsage() {
        System.out.println("  butler trade counter-message-finalize <trusted-grant-id>");
        System.out.println("  Loads only trusted persisted message handoff/acknowledgment state for the grant.");
        System.out.println("  Requires durable BF-414 SENT_EXACT_MESSAGE acknowledgment evidence before local success can be finalized.");
        System.out.println("  Eligible finalization atomically marks the local attempt SUCCEEDED and consumes its one-shot authorization.");
        System.out.println("  This command never sends or modifies a Sleeper message and performs no Sleeper write/private API call.");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
