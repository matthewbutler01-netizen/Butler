package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterAuthorizationGrantRepository;
import io.butler.bet.data.TradeCounterAuthorizationReplayContextRepository;
import io.butler.bet.intelligence.TradeCounterExecutionReadinessPolicy;
import io.butler.bet.intelligence.TradeCounterProposalIdentityPolicy;

import java.nio.file.Path;
import java.sql.SQLException;

/** Read-only fresh-evidence readiness surface for one trusted counter authorization grant. */
public final class ButlerTradeCounterReadinessCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeCounterReadinessCli() {}

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
                printNotFound(grantId);
                return;
            }

            var trusted = stored.get();
            if (trusted.consumed()) {
                print(assessReadiness(trusted, null, null));
                return;
            }

            var replayRepository = new TradeCounterAuthorizationReplayContextRepository(database);
            var replay = replayRepository.findByGrantId(grantId);
            if (replay.isEmpty()) {
                print(assessReadiness(trusted, null, null));
                return;
            }

            var options = replayOptions(trusted, replay.get());
            var freshIdentity = ButlerTradeCounterAuthorizationCli.buildIdentity(database, options);
            print(assessReadiness(trusted, replay.get(), freshIdentity));
        } catch (SQLException e) {
            System.err.println("Database error while evaluating counter readiness: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static String parseGrantId(String[] args) {
        if (!isCommand(args) || args.length != 3) {
            throw new IllegalArgumentException("trade counter-readiness requires exactly one trusted grant ID");
        }
        if (args[2] == null || args[2].isBlank()) {
            throw new IllegalArgumentException("grant-id must not be blank");
        }
        return args[2].trim();
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "trade".equalsIgnoreCase(args[0])
            && "counter-readiness".equalsIgnoreCase(args[1]);
    }

    static ButlerTradeCounterDecisionCli.Options replayOptions(
        TradeCounterAuthorizationGrantRepository.StoredGrant stored,
        TradeCounterAuthorizationReplayContextRepository.ReplayContext replay) {
        if (stored == null || replay == null) {
            throw new IllegalArgumentException("trusted grant and replay context must not be null");
        }
        var grant = stored.grant();
        if (!grant.grantId().equals(replay.grantId())) {
            throw new IllegalStateException("trusted grant and replay context grant IDs differ");
        }
        return new ButlerTradeCounterDecisionCli.Options(
            grant.leagueId(),
            grant.season(),
            replay.originalSideA(),
            replay.originalSideB(),
            grant.perspective(),
            grant.source(),
            grant.minimumAsOfDate());
    }

    static TradeCounterExecutionReadinessPolicy.Result assessReadiness(
        TradeCounterAuthorizationGrantRepository.StoredGrant stored,
        TradeCounterAuthorizationReplayContextRepository.ReplayContext replay,
        TradeCounterProposalIdentityPolicy.Identity freshIdentity) {
        if (stored == null) {
            throw new IllegalArgumentException("trusted grant must not be null");
        }
        if (stored.consumed()) {
            if (replay != null || freshIdentity != null) {
                throw new IllegalArgumentException(
                    "consumed grant readiness must not use replay or fresh identity");
            }
            return TradeCounterExecutionReadinessPolicy.assess(
                stored.grant(), true, false, null);
        }
        if (replay == null) {
            if (freshIdentity != null) {
                throw new IllegalArgumentException(
                    "missing replay context readiness must not use fresh identity");
            }
            return TradeCounterExecutionReadinessPolicy.assess(
                stored.grant(), false, false, null);
        }
        if (!stored.grant().grantId().equals(replay.grantId())) {
            throw new IllegalStateException("trusted grant and replay context grant IDs differ");
        }
        return TradeCounterExecutionReadinessPolicy.assess(
            stored.grant(), false, true, freshIdentity);
    }

    static void print(TradeCounterExecutionReadinessPolicy.Result result) {
        if (result == null) throw new IllegalArgumentException("readiness result must not be null");
        System.out.println("Trade counter execution readiness (read-only; no consume)");
        System.out.println("Readiness policy: " + result.policyId());
        System.out.println("Trusted grant ID: " + result.grantId());
        System.out.println("Authorized action: " + result.action());
        System.out.println("Authorized destination: "
            + result.destination().type() + ":" + result.destination().id());
        System.out.println("Authorized proposal fingerprint: " + result.authorizedFingerprint());
        if (result.freshFingerprint() != null) {
            System.out.println("Fresh proposal fingerprint: " + result.freshFingerprint());
        } else {
            System.out.println("Fresh proposal fingerprint: unavailable");
        }
        System.out.println("Fresh revalidation: "
            + (result.revalidationState() == null ? "NOT_EVALUATED" : result.revalidationState()));
        System.out.println("Execution readiness: " + result.state());
        System.out.println("Readiness reason: " + result.reason());
        System.out.println("Readiness never consumes the authorization grant.");
        System.out.println("READY is evidence status only; this command never sends a message or submits a trade.");
    }

    static void printNotFound(String grantId) {
        if (grantId == null || grantId.isBlank()) {
            throw new IllegalArgumentException("grantId must not be blank");
        }
        System.out.println("Trade counter execution readiness unavailable");
        System.out.println("Trusted grant ID: " + grantId);
        System.out.println("Readiness reason: trusted authorization grant was not found.");
        System.out.println("No grant is consumed and no message or trade is sent or submitted.");
    }

    static void printUsage() {
        System.out.println("  butler trade counter-readiness <trusted-grant-id>");
        System.out.println("  Loads the trusted grant and immutable original trade replay context, then reruns the governed counter proposal from current evidence.");
        System.out.println("  Reports READY, DRIFTED, INCONCLUSIVE, or a terminal blocked state.");
        System.out.println("  This command never consumes the grant, sends a message, or submits a trade.");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }
}
