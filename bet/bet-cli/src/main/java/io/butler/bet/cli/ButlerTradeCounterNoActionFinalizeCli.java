package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.integration.sleeper.SleeperManualCounterNoActionOutcomeCoordinator;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;

/** Explicit local-only finalization surface for durable manual no-action evidence. */
public final class ButlerTradeCounterNoActionFinalizeCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeCounterNoActionFinalizeCli() {}

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
            var resolved = ButlerTradeCounterNoActionAcknowledgeCli.resolve(database, grantId);
            if (resolved == null) {
                printUnavailable(grantId);
                return;
            }

            var result = new SleeperManualCounterNoActionOutcomeCoordinator(database)
                .apply(resolved.claimId(), Instant.now());
            print(grantId, resolved.claimId(), result);
        } catch (SQLException e) {
            System.err.println("Database error while finalizing manual no-action evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static String parseGrantId(String[] args) {
        if (!isCommand(args) || args.length != 3) {
            throw new IllegalArgumentException(
                "trade counter-no-action-finalize requires exactly one trusted grant ID");
        }
        return requireText(args[2], "trusted-grant-id");
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "trade".equalsIgnoreCase(args[0])
            && "counter-no-action-finalize".equalsIgnoreCase(args[1]);
    }

    static void print(
        String grantId,
        String claimId,
        SleeperManualCounterNoActionOutcomeCoordinator.ApplyResult result) {
        grantId = requireText(grantId, "grantId");
        claimId = requireText(claimId, "claimId");
        if (result == null) throw new IllegalArgumentException("result must not be null");
        if (result.outcome() != null
            && (!grantId.equals(result.outcome().grantId())
                || !claimId.equals(result.outcome().claimId()))) {
            throw new IllegalStateException(
                "manual no-action finalization outcome does not match trusted grant/claim");
        }

        System.out.println("Trade counter manual no-action finalization");
        System.out.println("Trusted grant ID: " + grantId);
        System.out.println("Execution claim ID: " + claimId);
        System.out.println("Finalization state: " + result.state());
        System.out.println("Finalization reason: " + result.reason());

        if (result.outcome() != null) {
            var outcome = result.outcome();
            System.out.println("Terminal outcome ID: " + outcome.outcomeId());
            System.out.println("No-action acknowledgment ID: " + outcome.acknowledgmentId());
            System.out.println("Handoff presentation ID: " + outcome.handoffId());
            System.out.println("Payload SHA-256: " + outcome.payloadSha256());
            System.out.println("Authorized action: " + outcome.action());
            System.out.println("Authorized destination: "
                + outcome.destination().type() + ":" + outcome.destination().id());
            System.out.println("Acknowledged at: " + outcome.acknowledgedAt());
            System.out.println("Execution terminal state: " + outcome.terminalState());
            System.out.println("Authorization disposition: " + outcome.grantDisposition());
            System.out.println("Applied at: " + outcome.appliedAt());
            System.out.println("Any retry now requires a fresh explicit authorization.");
        } else {
            System.out.println("No terminal execution state or authorization change was applied.");
        }

        System.out.println("Local-only finalization; this command performs no Sleeper request or external action.");
        System.out.println("Butler did not send a message or submit a trade through this command.");
    }

    static void printUnavailable(String grantId) {
        grantId = requireText(grantId, "grantId");
        System.out.println("Trade counter manual no-action finalization unavailable");
        System.out.println("Trusted grant ID: " + grantId);
        System.out.println("Reason: no matching durable execution claim and manual handoff were found.");
        System.out.println("No terminal execution state or authorization change was applied.");
        System.out.println("No Sleeper action occurred.");
    }

    static void printUsage() {
        System.out.println("  butler trade counter-no-action-finalize <trusted-grant-id>");
        System.out.println("  Loads only trusted persisted handoff and BF-426 no-action acknowledgment state for the grant.");
        System.out.println("  Requires durable exact NO_EXTERNAL_ACTION_TAKEN evidence before local failure can be finalized.");
        System.out.println("  Eligible finalization atomically marks the local attempt FAILED and consumes/closes its one-shot authorization.");
        System.out.println("  Any retry requires fresh explicit authorization; this command performs no Sleeper request or external action.");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
