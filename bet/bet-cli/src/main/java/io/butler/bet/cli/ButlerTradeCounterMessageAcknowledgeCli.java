package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionClaimRepository;
import io.butler.bet.integration.sleeper.SleeperManualCounterHandoffRepository;
import io.butler.bet.integration.sleeper.SleeperManualMessageAcknowledgmentPolicy;
import io.butler.bet.integration.sleeper.SleeperManualMessageAcknowledgmentRepository;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;

/** Explicit local acknowledgment surface for one exact manual Sleeper negotiation message. */
public final class ButlerTradeCounterMessageAcknowledgeCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeCounterMessageAcknowledgeCli() {}

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
            var resolved = resolve(database, options.grantId());
            if (resolved == null) {
                printUnavailable(options.grantId());
                return;
            }

            if (options.confirmation() == null) {
                printRequired(resolved.handoff());
                return;
            }
            if (!exactConfirmation(options.confirmation())) {
                printRejected(resolved.handoff(), options.confirmation());
                return;
            }

            Instant now = Instant.now();
            var request = new SleeperManualMessageAcknowledgmentPolicy.AcknowledgmentRequest(
                resolved.handoff().grantId(),
                resolved.handoff().handoffId(),
                resolved.handoff().payloadSha256(),
                options.confirmation(),
                now);
            var decision = SleeperManualMessageAcknowledgmentPolicy.acknowledge(
                resolved.handoff(), request);
            var record = new SleeperManualMessageAcknowledgmentRepository(database)
                .record(decision, now);
            print(resolved.handoff(), decision, record);
        } catch (SQLException e) {
            System.err.println("Database error while acknowledging manual message: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || (args.length != 3 && args.length != 5)) {
            throw new IllegalArgumentException(
                "trade counter-message-ack requires one trusted grant ID and optional exact --confirm value");
        }
        String grantId = requireText(args[2], "trusted-grant-id");
        if (args.length == 3) return new Options(grantId, null);
        if (!"--confirm".equals(args[3])) {
            throw new IllegalArgumentException("optional acknowledgment argument must be --confirm");
        }
        if (args[4] == null || args[4].isBlank()) {
            throw new IllegalArgumentException("confirmation must not be blank");
        }
        return new Options(grantId, args[4]);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "trade".equalsIgnoreCase(args[0])
            && "counter-message-ack".equalsIgnoreCase(args[1]);
    }

    static boolean exactConfirmation(String confirmation) {
        return SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION.equals(confirmation);
    }

    static Resolved resolve(Database database, String grantId) throws SQLException {
        if (database == null) throw new IllegalArgumentException("database must not be null");
        grantId = requireText(grantId, "grantId");
        var attempt = new TradeCounterExecutionAttemptRepository(database)
            .findByGrantId(grantId).orElse(null);
        if (attempt == null) return null;
        var claim = new TradeCounterExecutionClaimRepository(database)
            .findByAttemptId(attempt.attemptId()).orElse(null);
        if (claim == null || !grantId.equals(claim.grantId())) return null;
        var handoff = new SleeperManualCounterHandoffRepository(database)
            .findByClaimId(claim.claimId()).orElse(null);
        if (handoff == null || !grantId.equals(handoff.grantId())
            || !attempt.attemptId().equals(handoff.attemptId())) {
            return null;
        }
        return new Resolved(attempt.attemptId(), claim.claimId(), handoff);
    }

    static void printRequired(SleeperManualCounterHandoffRepository.PresentedHandoff handoff) {
        requireHandoff(handoff);
        System.out.println("Trade counter manual message acknowledgment required");
        printCoordinates(handoff);
        System.out.println("Required exact confirmation: "
            + SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION);
        System.out.println("No acknowledgment was recorded.");
        System.out.println("This command does not send the message, change execution state, or consume authorization.");
    }

    static void printRejected(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        String suppliedConfirmation) {
        requireHandoff(handoff);
        if (suppliedConfirmation == null) throw new IllegalArgumentException("suppliedConfirmation must not be null");
        System.out.println("Trade counter manual message acknowledgment rejected");
        printCoordinates(handoff);
        System.out.println("Acknowledgment state: NOT_ACKNOWLEDGED");
        System.out.println("Required exact confirmation: "
            + SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION);
        System.out.println("The supplied confirmation was not an exact match; no acknowledgment was recorded.");
        System.out.println("Execution remains unchanged and authorization remains unconsumed.");
    }

    static void print(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        SleeperManualMessageAcknowledgmentPolicy.Decision decision,
        SleeperManualMessageAcknowledgmentRepository.RecordResult record) {
        requireHandoff(handoff);
        if (decision == null || record == null) {
            throw new IllegalArgumentException("acknowledgment output inputs must not be null");
        }
        if (!handoff.claimId().equals(decision.claimId())
            || !handoff.attemptId().equals(decision.attemptId())
            || !handoff.grantId().equals(decision.grantId())
            || !handoff.handoffId().equals(decision.handoffId())
            || !handoff.payloadSha256().equals(decision.payloadSha256())) {
            throw new IllegalStateException("acknowledgment decision does not match trusted handoff");
        }

        System.out.println("Trade counter manual message acknowledgment");
        printCoordinates(handoff);
        System.out.println("Acknowledgment policy: " + decision.policyId());
        System.out.println("Acknowledgment state: " + decision.state());
        System.out.println("Acknowledgment reason code: " + decision.reasonCode());
        System.out.println("Local completion eligibility: " + decision.localCompletionEligibility());
        System.out.println("Acknowledgment journal state: " + record.state());
        System.out.println("Acknowledgment journal reason: " + record.reason());
        if (record.acknowledgment() != null) {
            System.out.println("Acknowledgment ID: " + record.acknowledgment().acknowledgmentId());
            System.out.println("Acknowledged at: " + record.acknowledgment().acknowledgedAt());
            System.out.println("Recorded at: " + record.acknowledgment().recordedAt());
        }
        System.out.println("This is local evidence that the user says the exact message was sent; Butler did not send it.");
        System.out.println("This command does not mark execution SUCCEEDED and does not consume authorization.");
    }

    static void printUnavailable(String grantId) {
        grantId = requireText(grantId, "grantId");
        System.out.println("Trade counter manual message acknowledgment unavailable");
        System.out.println("Trusted grant ID: " + grantId);
        System.out.println("Reason: no matching durable execution claim and manual handoff were found.");
        System.out.println("No acknowledgment was recorded and no external action occurred.");
    }

    static void printUsage() {
        System.out.println("  butler trade counter-message-ack <trusted-grant-id> [--confirm SENT_EXACT_MESSAGE]");
        System.out.println("  Without --confirm, prints the exact trusted message coordinates and required confirmation only.");
        System.out.println("  --confirm is raw and case-sensitive; whitespace or case variants are rejected.");
        System.out.println("  Exact confirmation records BF-414 local acknowledgment evidence only; it does not send or finalize the message.");
    }

    private static void printCoordinates(SleeperManualCounterHandoffRepository.PresentedHandoff handoff) {
        System.out.println("Trusted grant ID: " + handoff.grantId());
        System.out.println("Execution claim ID: " + handoff.claimId());
        System.out.println("Handoff presentation ID: " + handoff.handoffId());
        System.out.println("Authorized action: " + handoff.action());
        System.out.println("Authorized destination: "
            + handoff.destination().type() + ":" + handoff.destination().id());
        System.out.println("Payload SHA-256: " + handoff.payloadSha256());
        System.out.println("First presented at: " + handoff.presentedAt());
    }

    private static void requireHandoff(SleeperManualCounterHandoffRepository.PresentedHandoff handoff) {
        if (handoff == null) throw new IllegalArgumentException("handoff must not be null");
    }

    record Options(String grantId, String confirmation) {
        Options {
            grantId = requireText(grantId, "grantId");
        }
    }

    record Resolved(
        String attemptId,
        String claimId,
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff) {
        Resolved {
            attemptId = requireText(attemptId, "attemptId");
            claimId = requireText(claimId, "claimId");
            requireHandoff(handoff);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
