package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.TradeCounterCandidateSelectionPolicy;
import io.butler.bet.intelligence.TradeCounterMaterializedPackagePolicy;
import io.butler.bet.intelligence.TradeCounterNegotiationMessagePolicy;
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
import java.util.Locale;

/** Read-only governed COUNTER proposal surface. No trade is submitted or sent. */
public final class ButlerTradeCounterProposalCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeCounterProposalCli() {}

    public static void main(String[] args) {
        ButlerTradeCounterDecisionCli.Options options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
            return;
        }

        try {
            Database database = initializedDatabase();
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
            var identity = TradeCounterProposalIdentityPolicy.identify(envelope, materialized);
            var message = TradeCounterNegotiationMessagePolicy.compose(envelope);
            print(recommendationContext, options, v5, opportunity, selection, proposal,
                envelope, materialized, identity, message);
        } catch (SQLException e) {
            System.err.println("Database error while building counter proposal: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static ButlerTradeCounterDecisionCli.Options parse(String[] args) {
        if (!isCommand(args)) {
            throw new IllegalArgumentException("trade counter-proposal command is required");
        }
        String[] normalized = args.clone();
        normalized[1] = "counter-decision";
        return ButlerTradeCounterDecisionCli.parse(normalized);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "trade".equalsIgnoreCase(args[0])
            && "counter-proposal".equalsIgnoreCase(args[1]);
    }

    /** Retained BF-376 renderer for compatibility. */
    static void print(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        ButlerTradeCounterDecisionCli.Options options,
        ButlerTradeRecommendationV5Cli.V5RecommendationResult v5,
        TradeCounterOpportunityPolicy.Decision opportunity,
        TradeCounterCandidateSelectionPolicy.Selection selection,
        TradeCounterProposalPolicy.Result proposal) {
        printProposal(context, options, v5, opportunity, selection, proposal);
    }

    /** Retained BF-377 renderer for compatibility. */
    static void print(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        ButlerTradeCounterDecisionCli.Options options,
        ButlerTradeRecommendationV5Cli.V5RecommendationResult v5,
        TradeCounterOpportunityPolicy.Decision opportunity,
        TradeCounterCandidateSelectionPolicy.Selection selection,
        TradeCounterProposalPolicy.Result proposal,
        TradeCounterProposalEnvelopePolicy.Envelope envelope) {
        printProposal(context, options, v5, opportunity, selection, proposal);
        printEnvelope(options, proposal, envelope);
    }

    /** Retained BF-379 renderer for compatibility. */
    static void print(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        ButlerTradeCounterDecisionCli.Options options,
        ButlerTradeRecommendationV5Cli.V5RecommendationResult v5,
        TradeCounterOpportunityPolicy.Decision opportunity,
        TradeCounterCandidateSelectionPolicy.Selection selection,
        TradeCounterProposalPolicy.Result proposal,
        TradeCounterProposalEnvelopePolicy.Envelope envelope,
        TradeCounterNegotiationMessagePolicy.MessageResult message) {
        printProposal(context, options, v5, opportunity, selection, proposal);
        printEnvelope(options, proposal, envelope);
        printMessage(envelope, message);
    }

    /** Retained BF-381 renderer for compatibility. */
    static void print(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        ButlerTradeCounterDecisionCli.Options options,
        ButlerTradeRecommendationV5Cli.V5RecommendationResult v5,
        TradeCounterOpportunityPolicy.Decision opportunity,
        TradeCounterCandidateSelectionPolicy.Selection selection,
        TradeCounterProposalPolicy.Result proposal,
        TradeCounterProposalEnvelopePolicy.Envelope envelope,
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized,
        TradeCounterNegotiationMessagePolicy.MessageResult message) {
        printProposal(context, options, v5, opportunity, selection, proposal);
        printEnvelope(options, proposal, envelope);
        printMaterialized(envelope, materialized);
        printMessage(envelope, message);
    }

    static void print(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        ButlerTradeCounterDecisionCli.Options options,
        ButlerTradeRecommendationV5Cli.V5RecommendationResult v5,
        TradeCounterOpportunityPolicy.Decision opportunity,
        TradeCounterCandidateSelectionPolicy.Selection selection,
        TradeCounterProposalPolicy.Result proposal,
        TradeCounterProposalEnvelopePolicy.Envelope envelope,
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized,
        TradeCounterProposalIdentityPolicy.Identity identity,
        TradeCounterNegotiationMessagePolicy.MessageResult message) {
        printProposal(context, options, v5, opportunity, selection, proposal);
        printEnvelope(options, proposal, envelope);
        printMaterialized(envelope, materialized);
        printIdentity(envelope, materialized, identity);
        printMessage(envelope, message);
    }

    private static void printProposal(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        ButlerTradeCounterDecisionCli.Options options,
        ButlerTradeRecommendationV5Cli.V5RecommendationResult v5,
        TradeCounterOpportunityPolicy.Decision opportunity,
        TradeCounterCandidateSelectionPolicy.Selection selection,
        TradeCounterProposalPolicy.Result proposal) {
        if (context == null || options == null || v5 == null || opportunity == null
            || selection == null || proposal == null) {
            throw new IllegalArgumentException("counter proposal output inputs must not be null");
        }
        var report = context.trade();
        var trade = report.strategic().trade();
        boolean sideA = options.perspective() == TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM;
        var team = sideA ? report.strategic().sideA().identity() : report.strategic().sideB().identity();
        if (!trade.leagueId().equals(proposal.leagueId())
            || options.season() != proposal.season()
            || !trade.source().equals(proposal.source())
            || !java.util.Objects.equals(trade.minimumAsOfDate(), proposal.minimumAsOfDate())
            || !opportunity.policyId().equals(proposal.opportunityPolicyId())
            || !selection.policyId().equals(proposal.selectionPolicyId())) {
            throw new IllegalStateException("counter proposal coordinates or provenance differ");
        }

        System.out.println("Trade counter proposal (read-only governed COUNTER)");
        System.out.println("League ID: " + trade.leagueId());
        System.out.println("Season: " + options.season());
        System.out.println("Perspective: " + team.teamName() + " [" + team.teamId() + "]");
        System.out.println("Value source: " + trade.source());
        if (trade.minimumAsOfDate() != null) {
            System.out.println("Minimum as-of: " + trade.minimumAsOfDate());
        }
        System.out.println("Counter proposal policy: " + proposal.policyId());
        System.out.println("Counter opportunity policy: " + opportunity.policyId());
        System.out.println("Counter selection policy: " + selection.policyId());
        System.out.println("V5 team action: " + v5.action());
        System.out.println("Counter opportunity: " + opportunity.state());
        System.out.println("Counter candidate selection: " + selection.state());
        System.out.println("Counter action: " + proposal.action());
        System.out.println("Counter action reason: " + proposal.reasonCode());

        if (proposal.action() == TradeCounterProposalPolicy.Action.COUNTER) {
            var counter = proposal.proposal();
            System.out.println("COUNTER: " + formatAdjustment(counter));
            System.out.printf(Locale.ROOT,
                "Counter evidence: market-rank=%d value=%.2f as-of=%s required-change=%.2f excess=%.2f%n",
                counter.marketRank(), counter.assetValue(), counter.asOfDate(),
                counter.requiredValueChange(), counter.excessValue());
            System.out.printf(Locale.ROOT,
                "Counter result: side-a=%.2f side-b=%.2f gap=%.3f%% fairness=%s%n",
                counter.resultingSideAValue(), counter.resultingSideBValue(),
                counter.resultingGapPercent(), counter.resultingFairness());
        } else if (selection.state() == TradeCounterCandidateSelectionPolicy.State.AMBIGUOUS) {
            System.out.println("Ambiguous top market ranks: " + selection.ambiguousMarketRanks());
            System.out.println("No COUNTER proposal is emitted because the governed selection is ambiguous.");
        } else {
            System.out.println("No COUNTER proposal is emitted.");
        }
        System.out.println("Read-only proposal only; Butler does not submit, send, or mutate the trade.");
    }

    private static void printEnvelope(
        ButlerTradeCounterDecisionCli.Options options,
        TradeCounterProposalPolicy.Result proposal,
        TradeCounterProposalEnvelopePolicy.Envelope envelope) {
        if (options == null || proposal == null || envelope == null) {
            throw new IllegalArgumentException("counter proposal envelope output inputs must not be null");
        }
        if (!proposal.policyId().equals(envelope.proposalPolicyId())
            || options.perspective() != envelope.perspective()
            || proposal.action() != envelope.action()
            || proposal.reasonCode() != envelope.reasonCode()) {
            throw new IllegalStateException("counter proposal and envelope differ");
        }
        System.out.println("Counter proposal envelope policy: " + envelope.policyId());
        System.out.println("Envelope perspective policy: " + envelope.perspectivePolicyId());
        System.out.println("Envelope perspective: " + envelope.perspective());
        System.out.println("Original Side A package: " + formatPackage(envelope.originalSideA()));
        System.out.println("Original Side B package: " + formatPackage(envelope.originalSideB()));
        System.out.println("Proposal binding verified against original trade packages.");
    }

    private static void printMaterialized(
        TradeCounterProposalEnvelopePolicy.Envelope envelope,
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized) {
        if (envelope == null || materialized == null) {
            throw new IllegalArgumentException("counter materialized package output inputs must not be null");
        }
        if (!envelope.policyId().equals(materialized.envelopePolicyId())
            || !envelope.leagueId().equals(materialized.leagueId())
            || envelope.season() != materialized.season()
            || !envelope.source().equals(materialized.source())
            || !java.util.Objects.equals(envelope.minimumAsOfDate(), materialized.minimumAsOfDate())
            || envelope.perspective() != materialized.perspective()
            || !envelope.originalSideA().equals(materialized.originalSideA())
            || !envelope.originalSideB().equals(materialized.originalSideB())) {
            throw new IllegalStateException("counter proposal envelope and materialized packages differ");
        }
        System.out.println("Counter materialized package policy: " + materialized.policyId());
        System.out.println("Counter materialized package state: " + materialized.state());
        System.out.println("Counter materialized package reason: " + materialized.reasonCode());
        if (materialized.state() == TradeCounterMaterializedPackagePolicy.State.MATERIALIZED) {
            System.out.println("Revised Side A package: " + formatPackage(materialized.revisedSideA()));
            System.out.println("Revised Side B package: " + formatPackage(materialized.revisedSideB()));
            System.out.println("Complete counter packages materialized from the bound single-asset proposal.");
        } else {
            System.out.println("No revised counter packages are available.");
        }
        System.out.println("Read-only package snapshot only; Butler does not submit or mutate the trade.");
    }

    private static void printIdentity(
        TradeCounterProposalEnvelopePolicy.Envelope envelope,
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized,
        TradeCounterProposalIdentityPolicy.Identity identity) {
        if (envelope == null || materialized == null || identity == null) {
            throw new IllegalArgumentException("counter proposal identity output inputs must not be null");
        }
        if (!envelope.policyId().equals(identity.envelopePolicyId())
            || !materialized.policyId().equals(identity.materializedPackagePolicyId())
            || !envelope.leagueId().equals(identity.leagueId())
            || envelope.season() != identity.season()
            || !envelope.source().equals(identity.source())
            || !java.util.Objects.equals(envelope.minimumAsOfDate(), identity.minimumAsOfDate())
            || envelope.perspective() != identity.perspective()) {
            throw new IllegalStateException("counter proposal artifacts and identity differ");
        }
        System.out.println("Counter proposal identity policy: " + identity.policyId());
        System.out.println("Counter proposal identity state: " + identity.state());
        System.out.println("Counter proposal identity reason: " + identity.reasonCode());
        System.out.println("Counter proposal identity algorithm: " + identity.algorithm()
            + " canonical-version=" + identity.canonicalVersion());
        if (identity.state() == TradeCounterProposalIdentityPolicy.State.IDENTIFIED) {
            System.out.println("Counter proposal fingerprint: " + identity.fingerprint());
        } else {
            System.out.println("No counter proposal fingerprint is available.");
        }
        System.out.println("Fingerprint is audit identity only; it is not authorization to send or execute the trade.");
    }

    private static void printMessage(
        TradeCounterProposalEnvelopePolicy.Envelope envelope,
        TradeCounterNegotiationMessagePolicy.MessageResult message) {
        if (envelope == null || message == null) {
            throw new IllegalArgumentException("counter negotiation message output inputs must not be null");
        }
        if (!envelope.policyId().equals(message.envelopePolicyId())
            || !envelope.leagueId().equals(message.leagueId())
            || envelope.season() != message.season()
            || !envelope.source().equals(message.source())
            || !java.util.Objects.equals(envelope.minimumAsOfDate(), message.minimumAsOfDate())
            || envelope.perspective() != message.perspective()) {
            throw new IllegalStateException("counter proposal envelope and negotiation message differ");
        }
        System.out.println("Counter negotiation message policy: " + message.policyId());
        System.out.println("Counter negotiation message state: " + message.state());
        System.out.println("Counter negotiation message reason: " + message.reasonCode());
        if (message.state() == TradeCounterNegotiationMessagePolicy.State.MESSAGE_AVAILABLE) {
            System.out.println("Counter negotiation actor: " + message.actor());
            System.out.println("Negotiation message: " + message.text());
        } else {
            System.out.println("No negotiation message is available.");
        }
        System.out.println("Read-only wording only; Butler does not send this message.");
    }

    private static String formatPackage(io.butler.bet.intelligence.TradeAssetAnalyzer.TradePackage tradePackage) {
        return "players=" + tradePackage.playerIds() + " picks=" + tradePackage.draftPickIds();
    }

    private static String formatAdjustment(TradeCounterProposalPolicy.Proposal proposal) {
        String asset = proposal.assetType() + " " + proposal.displayName() + " [" + proposal.assetId() + "]";
        return switch (proposal.adjustmentType()) {
            case ADD_ASSET_TO_LOWER_PACKAGE -> "ADD " + asset + " TO " + proposal.side();
            case REMOVE_ASSET_FROM_HIGHER_PACKAGE -> "REMOVE " + asset + " FROM " + proposal.side();
        };
    }

    static void printUsage() {
        System.out.println("  butler trade counter-proposal <league-id> <season> <side-a-assets> <side-b-assets> <side-a|side-b> [source] [--minimum-as-of YYYY-MM-DD]");
        System.out.println("  A read-only COUNTER proposal is emitted only for a complete v5 REJECT with a uniquely selected strategically eligible candidate.");
        System.out.println("  Proposal binding verifies the explicit perspective and original trade packages.");
        System.out.println("  When a COUNTER exists, Butler displays the complete revised packages, audit fingerprint, and governed neutral negotiation wording.");
        System.out.println("  The fingerprint is not authorization. Butler does not submit, send, or mutate the trade or message.");
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
}
