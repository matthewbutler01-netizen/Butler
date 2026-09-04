package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.TradeCounterCandidateSelectionPolicy;
import io.butler.bet.intelligence.TradeCounterOpportunityPolicy;
import io.butler.bet.intelligence.TradeCounterProposalEnvelopePolicy;
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
            print(recommendationContext, options, v5, opportunity, selection, proposal, envelope);
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
        System.out.println("  Butler does not submit, send, or mutate the trade.");
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
