package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.TradeCounterAuthorizationGrantRepository;
import io.butler.bet.data.TradeCounterAuthorizationReplayContextRepository;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import io.butler.bet.intelligence.TradeCounterCandidateSelectionPolicy;
import io.butler.bet.intelligence.TradeCounterMaterializedPackagePolicy;
import io.butler.bet.intelligence.TradeCounterOpportunityPolicy;
import io.butler.bet.intelligence.TradeCounterProposalEnvelopePolicy;
import io.butler.bet.intelligence.TradeCounterProposalIdentityPolicy;
import io.butler.bet.intelligence.TradeCounterProposalPolicy;
import io.butler.bet.intelligence.TradeCounterStrategicCandidateVettingAnalyzer;
import io.butler.bet.intelligence.TradeCounterStrategicEligibilityPolicy;
import io.butler.bet.intelligence.TradeFlexibleRecommendationContextAnalyzer;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;

/** Explicit authorization surface only. No message or trade is sent or submitted. */
public final class ButlerTradeCounterAuthorizationCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeCounterAuthorizationCli() {}

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
            Database database = initializedDatabase();
            var identity = buildIdentity(database, options.trade());
            if (identity.state() != TradeCounterProposalIdentityPolicy.State.IDENTIFIED) {
                printUnavailable(identity);
                return;
            }

            var request = TradeCounterAuthorizationPolicy.request(
                identity, options.action(), options.destination());
            if (options.confirmation() == null) {
                printRequest(request);
                return;
            }

            var decision = TradeCounterAuthorizationPolicy.authorize(
                request, options.confirmation());
            var persistence = persistAuthorizationWithReplay(
                database,
                decision,
                options.trade().sideA(),
                options.trade().sideB());
            printDecision(request, decision, persistence);
        } catch (SQLException e) {
            System.err.println("Database error while building counter authorization evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args)) {
            throw new IllegalArgumentException("trade counter-authorize command is required");
        }
        int separator = separatorIndex(args);
        if (separator < 7) {
            throw new IllegalArgumentException(
                "trade counter-authorize requires proposal coordinates before --");
        }

        String[] proposalArgs = Arrays.copyOfRange(args, 0, separator);
        proposalArgs[1] = "counter-proposal";
        var trade = ButlerTradeCounterProposalCli.parse(proposalArgs);

        int trailing = args.length - separator - 1;
        if (trailing != 2 && trailing != 4) {
            throw new IllegalArgumentException(
                "authorization arguments require action, destination, and optional --confirm value");
        }

        var action = parseAction(args[separator + 1]);
        String destinationId = requireText(args[separator + 2], "destination-id");
        var destinationType = action == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
            ? TradeCounterAuthorizationPolicy.DestinationType.MANAGER
            : TradeCounterAuthorizationPolicy.DestinationType.LEAGUE;
        var destination = new TradeCounterAuthorizationPolicy.Destination(
            destinationType, destinationId);

        String confirmation = null;
        if (trailing == 4) {
            if (!"--confirm".equalsIgnoreCase(args[separator + 3])) {
                throw new IllegalArgumentException("optional authorization value must use --confirm");
            }
            confirmation = requireExactConfirmation(args[separator + 4]);
        }
        return new Options(trade, action, destination, confirmation);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "trade".equalsIgnoreCase(args[0])
            && "counter-authorize".equalsIgnoreCase(args[1]);
    }

    static void printRequest(TradeCounterAuthorizationPolicy.AuthorizationRequest request) {
        if (request == null) throw new IllegalArgumentException("authorization request must not be null");
        System.out.println("Trade counter authorization request (no external action)");
        System.out.println("Authorization policy: " + request.policyId());
        System.out.println("League ID: " + request.leagueId());
        System.out.println("Season: " + request.season());
        System.out.println("Perspective: " + request.perspective());
        System.out.println("Proposal fingerprint: " + request.proposalFingerprint());
        System.out.println("Authorized action requested: " + request.action());
        System.out.println("Destination: " + request.destination().type() + ":" + request.destination().id());
        System.out.println("Maximum uses: " + request.maxUses());
        System.out.println("Exact confirmation required:");
        System.out.println(request.requiredConfirmation());
        System.out.println("No authorization grant was created. No message or trade was sent or submitted.");
    }

    /** Retained BF-385 renderer for compatibility. */
    static void printDecision(
        TradeCounterAuthorizationPolicy.AuthorizationRequest request,
        TradeCounterAuthorizationPolicy.AuthorizationDecision decision) {
        if (request == null || decision == null) {
            throw new IllegalArgumentException("authorization output inputs must not be null");
        }
        System.out.println("Trade counter authorization decision (no external action)");
        System.out.println("Authorization policy: " + decision.policyId());
        System.out.println("Proposal fingerprint: " + request.proposalFingerprint());
        System.out.println("Requested action: " + request.action());
        System.out.println("Destination: " + request.destination().type() + ":" + request.destination().id());
        System.out.println("Authorization state: " + decision.state());
        System.out.println("Authorization reason: " + decision.reason());
        if (decision.state() == TradeCounterAuthorizationPolicy.DecisionState.AUTHORIZED) {
            var grant = decision.grant();
            System.out.println("Authorization grant ID: " + grant.grantId());
            System.out.println("Authorization granted at: " + grant.grantedAt());
            System.out.println("Authorization maximum uses: " + grant.maxUses());
            System.out.println("Grant is not persisted or consumed by this renderer.");
        } else {
            System.out.println("No authorization grant was created.");
        }
        System.out.println("This command never sends a message or submits a trade.");
    }

    /** Retained BF-387 renderer for compatibility. */
    static void printDecision(
        TradeCounterAuthorizationPolicy.AuthorizationRequest request,
        TradeCounterAuthorizationPolicy.AuthorizationDecision decision,
        PersistenceResult persistence) {
        if (request == null || decision == null || persistence == null) {
            throw new IllegalArgumentException("authorization output inputs must not be null");
        }
        requirePersistenceMatchesDecision(decision, persistence);

        System.out.println("Trade counter authorization decision (no external action)");
        System.out.println("Authorization policy: " + decision.policyId());
        System.out.println("Proposal fingerprint: " + request.proposalFingerprint());
        System.out.println("Requested action: " + request.action());
        System.out.println("Destination: " + request.destination().type() + ":" + request.destination().id());
        System.out.println("Authorization state: " + decision.state());
        System.out.println("Authorization reason: " + decision.reason());
        System.out.println("Authorization persistence: " + persistence.state());

        if (decision.state() == TradeCounterAuthorizationPolicy.DecisionState.AUTHORIZED) {
            if (persistence.state() == PersistenceState.PERSISTED) {
                System.out.println("Trusted authorization grant ID: " + persistence.trustedGrantId());
                System.out.println("The exact-confirmation grant is persisted and remains unconsumed.");
            } else {
                System.out.println("Existing trusted active grant ID: " + persistence.trustedGrantId());
                System.out.println("An equivalent active authorization already exists; no duplicate grant was persisted.");
            }
        } else {
            System.out.println("No authorization grant was persisted.");
        }
        System.out.println("No grant is consumed by this command.");
        System.out.println("This command never sends a message or submits a trade.");
    }

    static void printDecision(
        TradeCounterAuthorizationPolicy.AuthorizationRequest request,
        TradeCounterAuthorizationPolicy.AuthorizationDecision decision,
        AuthorizationPersistenceResult persistence) {
        if (persistence == null) {
            throw new IllegalArgumentException("authorization persistence result must not be null");
        }
        printDecision(request, decision, persistence.grantPersistence());
        System.out.println("Authorization replay context: "
            + (persistence.replayAttachment() == null
                ? "NOT_APPLICABLE"
                : persistence.replayAttachment()));
        if (persistence.replayAttachment() != null) {
            System.out.println("Original Side A/Side B asset identities are bound to the trusted grant for fresh replay.");
        } else {
            System.out.println("No replay context was persisted.");
        }
        System.out.println("Replay persistence does not consume the grant or authorize an external side effect.");
    }

    static void printUnavailable(TradeCounterProposalIdentityPolicy.Identity identity) {
        if (identity == null) throw new IllegalArgumentException("proposal identity must not be null");
        System.out.println("Trade counter authorization unavailable");
        System.out.println("Proposal identity state: " + identity.state());
        System.out.println("Proposal identity reason: " + identity.reasonCode());
        System.out.println("No authorization request or grant can be created without an IDENTIFIED proposal.");
        System.out.println("No message or trade was sent or submitted.");
    }

    static void printUsage() {
        System.out.println("  butler trade counter-authorize <league-id> <season> <side-a-assets> <side-b-assets> <side-a|side-b> [source] [--minimum-as-of YYYY-MM-DD] -- <message|submit> <destination-id> [--confirm \"<exact-confirmation>\"]");
        System.out.println("  message requires a stable manager destination ID; submit requires the exact proposal league ID.");
        System.out.println("  Omit --confirm to display the exact AUTHORIZE_ONCE phrase without creating a grant.");
        System.out.println("  Supplying the exact quoted phrase can persist one trusted, unconsumed authorization grant plus its original trade replay context.");
        System.out.println("  --confirm is case-sensitive and is not trimmed or normalized.");
        System.out.println("  This command never consumes grants, sends messages, or submits trades.");
    }

    static PersistenceResult persistAuthorization(
        Database database,
        TradeCounterAuthorizationPolicy.AuthorizationDecision decision) throws SQLException {
        if (database == null || decision == null) {
            throw new IllegalArgumentException("authorization persistence inputs must not be null");
        }
        if (decision.state() != TradeCounterAuthorizationPolicy.DecisionState.AUTHORIZED) {
            return new PersistenceResult(PersistenceState.NOT_APPLICABLE, null);
        }

        var grant = decision.grant();
        var repository = new TradeCounterAuthorizationGrantRepository(database);
        repository.initialize();
        try {
            repository.save(grant);
            return new PersistenceResult(PersistenceState.PERSISTED, grant.grantId());
        } catch (SQLException e) {
            var active = repository.findActive(
                grant.proposalFingerprint(), grant.action(), grant.destination());
            if (active.isPresent()) {
                return new PersistenceResult(
                    PersistenceState.ACTIVE_GRANT_EXISTS,
                    active.get().grant().grantId());
            }
            throw e;
        }
    }

    static AuthorizationPersistenceResult persistAuthorizationWithReplay(
        Database database,
        TradeCounterAuthorizationPolicy.AuthorizationDecision decision,
        TradeAssetAnalyzer.TradePackage originalSideA,
        TradeAssetAnalyzer.TradePackage originalSideB) throws SQLException {
        if (database == null || decision == null) {
            throw new IllegalArgumentException("authorization persistence inputs must not be null");
        }
        var grantPersistence = persistAuthorization(database, decision);
        if (grantPersistence.state() == PersistenceState.NOT_APPLICABLE) {
            return new AuthorizationPersistenceResult(grantPersistence, null);
        }

        var replay = new TradeCounterAuthorizationReplayContextRepository(database);
        var attachment = replay.attach(
            grantPersistence.trustedGrantId(), originalSideA, originalSideB);
        return new AuthorizationPersistenceResult(grantPersistence, attachment);
    }

    private static void requirePersistenceMatchesDecision(
        TradeCounterAuthorizationPolicy.AuthorizationDecision decision,
        PersistenceResult persistence) {
        if (decision.state() == TradeCounterAuthorizationPolicy.DecisionState.AUTHORIZED
            && persistence.state() == PersistenceState.NOT_APPLICABLE) {
            throw new IllegalStateException("authorized decision requires trusted persistence outcome");
        }
        if (decision.state() == TradeCounterAuthorizationPolicy.DecisionState.REJECTED
            && persistence.state() != PersistenceState.NOT_APPLICABLE) {
            throw new IllegalStateException("rejected decision cannot have trusted persistence outcome");
        }
    }

    static TradeCounterProposalIdentityPolicy.Identity buildIdentity(
        Database database,
        ButlerTradeCounterDecisionCli.Options options) throws SQLException {
        var recommendationContext = analyzeRecommendation(
            new TradeFlexibleRecommendationContextAnalyzer(database), options);
        var v5 = ButlerTradeRecommendationV5Cli.recommend(
            recommendationContext, options.perspective());

        boolean eligibilityEvaluated = v5.evidenceStatus().complete()
            && v5.action() == TradeTeamPerspectiveRecommendationPolicy.Action.REJECT;
        TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility;
        if (eligibilityEvaluated) {
            var strategic = analyzeStrategic(
                new TradeCounterStrategicCandidateVettingAnalyzer(database), options);
            eligibility = TradeCounterStrategicEligibilityPolicy.classify(strategic);
        } else {
            eligibility = notEvaluatedEligibility(recommendationContext, options.season());
        }

        var opportunity = TradeCounterOpportunityPolicy.classify(
            v5.packageRecommendation(),
            v5.action(),
            options.perspective(),
            v5.evidenceStatus().complete(),
            eligibility);
        var selection = TradeCounterCandidateSelectionPolicy.classify(opportunity, eligibility);
        var proposal = TradeCounterProposalPolicy.classify(opportunity, selection);
        var envelope = TradeCounterProposalEnvelopePolicy.bind(
            proposal, options.perspective(), options.sideA(), options.sideB());
        var materialized = TradeCounterMaterializedPackagePolicy.materialize(envelope);
        return TradeCounterProposalIdentityPolicy.identify(envelope, materialized);
    }

    private static TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport analyzeRecommendation(
        TradeFlexibleRecommendationContextAnalyzer analyzer,
        ButlerTradeCounterDecisionCli.Options options) throws SQLException {
        if (options.minimumAsOf() != null) {
            return options.source() == null
                ? analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.minimumAsOf())
                : analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.source(), options.minimumAsOf());
        }
        return options.source() == null
            ? analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB())
            : analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.source());
    }

    private static TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidateReport analyzeStrategic(
        TradeCounterStrategicCandidateVettingAnalyzer analyzer,
        ButlerTradeCounterDecisionCli.Options options) throws SQLException {
        if (options.minimumAsOf() != null) {
            return options.source() == null
                ? analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.minimumAsOf())
                : analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.source(), options.minimumAsOf());
        }
        return options.source() == null
            ? analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB())
            : analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.source());
    }

    private static TradeCounterStrategicEligibilityPolicy.EligibilityReport notEvaluatedEligibility(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        int season) {
        var trade = context.trade().strategic().trade();
        return new TradeCounterStrategicEligibilityPolicy.EligibilityReport(
            TradeCounterStrategicEligibilityPolicy.POLICY_ID,
            TradeCounterStrategicCandidateVettingAnalyzer.POLICY_ID,
            trade.leagueId(), season, trade.source(), trade.minimumAsOfDate(), false,
            "Strategic eligibility was not evaluated because the v5 action did not require a counter gate.",
            java.util.List.of(), java.util.List.of());
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static int separatorIndex(String[] args) {
        for (int i = 2; i < args.length; i++) {
            if ("--".equals(args[i])) return i;
        }
        return -1;
    }

    private static TradeCounterAuthorizationPolicy.Action parseAction(String value) {
        value = requireText(value, "action");
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "message" -> TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE;
            case "submit" -> TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE;
            default -> throw new IllegalArgumentException("action must be message or submit");
        };
    }

    private static String requireExactConfirmation(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("confirmation must not be blank");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    enum PersistenceState {
        PERSISTED,
        ACTIVE_GRANT_EXISTS,
        NOT_APPLICABLE
    }

    record PersistenceResult(PersistenceState state, String trustedGrantId) {
        PersistenceResult {
            if (state == null) throw new IllegalArgumentException("persistence state must not be null");
            if (state == PersistenceState.NOT_APPLICABLE) {
                if (trustedGrantId != null) {
                    throw new IllegalArgumentException("NOT_APPLICABLE cannot carry trustedGrantId");
                }
            } else if (trustedGrantId == null || trustedGrantId.isBlank()) {
                throw new IllegalArgumentException("trusted persistence result requires grant ID");
            }
        }
    }

    record AuthorizationPersistenceResult(
        PersistenceResult grantPersistence,
        TradeCounterAuthorizationReplayContextRepository.AttachmentResult replayAttachment) {
        AuthorizationPersistenceResult {
            if (grantPersistence == null) {
                throw new IllegalArgumentException("grantPersistence must not be null");
            }
            if (grantPersistence.state() == PersistenceState.NOT_APPLICABLE) {
                if (replayAttachment != null) {
                    throw new IllegalArgumentException("non-authorized persistence cannot attach replay context");
                }
            } else if (replayAttachment == null) {
                throw new IllegalArgumentException("authorized persistence requires replay context attachment");
            }
        }
    }

    record Options(
        ButlerTradeCounterDecisionCli.Options trade,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        String confirmation) {
        Options {
            if (trade == null) throw new IllegalArgumentException("trade options must not be null");
            if (action == null) throw new IllegalArgumentException("action must not be null");
            if (destination == null) throw new IllegalArgumentException("destination must not be null");
        }
    }
}
