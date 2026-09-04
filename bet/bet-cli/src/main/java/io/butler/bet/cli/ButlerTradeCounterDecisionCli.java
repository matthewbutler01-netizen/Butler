package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterCandidateSelectionPolicy;
import io.butler.bet.intelligence.TradeCounterOpportunityPolicy;
import io.butler.bet.intelligence.TradeCounterStrategicCandidateVettingAnalyzer;
import io.butler.bet.intelligence.TradeCounterStrategicEligibilityPolicy;
import io.butler.bet.intelligence.TradeFlexibleRecommendationContextAnalyzer;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Perspective-aware counter decision path built on live v5, strategic eligibility, and governed selection. */
public final class ButlerTradeCounterDecisionCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeCounterDecisionCli() {}

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

            var decision = TradeCounterOpportunityPolicy.classify(
                v5.packageRecommendation(),
                v5.action(),
                options.perspective(),
                v5.evidenceStatus().complete(),
                eligibility);
            var selection = TradeCounterCandidateSelectionPolicy.classify(decision, eligibility);
            print(recommendationContext, options, v5, eligibility, eligibilityEvaluated, decision, selection);
        } catch (SQLException e) {
            System.err.println("Database error while building counter decision evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length < 7 || args.length > 10) {
            throw new IllegalArgumentException(
                "trade counter-decision requires league, season, two asset packages, and perspective");
        }
        String leagueId = requireText(args[2], "league-id");
        int season = parseSeason(args[3]);
        var sideA = ButlerApp.parseTradePackage(args[4], "side-a-assets");
        var sideB = ButlerApp.parseTradePackage(args[5], "side-b-assets");
        var perspective = parsePerspective(args[6]);
        String source = null;
        LocalDate minimumAsOf = null;

        if (args.length == 8) {
            if ("--minimum-as-of".equalsIgnoreCase(args[7])) {
                throw new IllegalArgumentException("--minimum-as-of requires a YYYY-MM-DD value");
            }
            source = requireText(args[7], "source");
        } else if (args.length == 9 && "--minimum-as-of".equalsIgnoreCase(args[7])) {
            minimumAsOf = parseDate(args[8]);
        } else if (args.length == 10 && "--minimum-as-of".equalsIgnoreCase(args[8])) {
            source = requireText(args[7], "source");
            minimumAsOf = parseDate(args[9]);
        } else if (args.length > 7) {
            throw new IllegalArgumentException("invalid trade counter-decision optional arguments");
        }
        return new Options(leagueId, season, sideA, sideB, perspective, source, minimumAsOf);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "trade".equalsIgnoreCase(args[0])
            && "counter-decision".equalsIgnoreCase(args[1]);
    }

    /** Retained BF-374 renderer for compatibility. */
    static void print(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        Options options,
        ButlerTradeRecommendationV5Cli.V5RecommendationResult v5,
        TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility,
        boolean eligibilityEvaluated,
        TradeCounterOpportunityPolicy.Decision decision) {
        printDecision(context, options, v5, eligibility, eligibilityEvaluated, decision);
        System.out.println("No candidate is selected and no COUNTER action is emitted.");
    }

    static void print(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        Options options,
        ButlerTradeRecommendationV5Cli.V5RecommendationResult v5,
        TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility,
        boolean eligibilityEvaluated,
        TradeCounterOpportunityPolicy.Decision decision,
        TradeCounterCandidateSelectionPolicy.Selection selection) {
        printDecision(context, options, v5, eligibility, eligibilityEvaluated, decision);
        printSelection(decision, selection);
    }

    private static void printDecision(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        Options options,
        ButlerTradeRecommendationV5Cli.V5RecommendationResult v5,
        TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility,
        boolean eligibilityEvaluated,
        TradeCounterOpportunityPolicy.Decision decision) {
        if (context == null || options == null || v5 == null || eligibility == null || decision == null) {
            throw new IllegalArgumentException("counter decision output inputs must not be null");
        }
        var report = context.trade();
        var trade = report.strategic().trade();
        boolean sideA = options.perspective() == TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM;
        var team = sideA ? report.strategic().sideA().identity() : report.strategic().sideB().identity();
        if (!trade.leagueId().equals(decision.leagueId())
            || options.season() != decision.season()
            || !trade.source().equals(decision.source())
            || !java.util.Objects.equals(trade.minimumAsOfDate(), decision.minimumAsOfDate())) {
            throw new IllegalStateException("recommendation and counter decision coordinates differ");
        }

        System.out.println("Trade counter decision gate (perspective-aware v5 reject plus strategic eligibility)");
        System.out.println("League ID: " + trade.leagueId());
        System.out.println("Season: " + options.season());
        System.out.println("Perspective: " + team.teamName() + " [" + team.teamId() + "]");
        System.out.println("Value source: " + trade.source());
        if (trade.minimumAsOfDate() != null) {
            System.out.println("Minimum as-of: " + trade.minimumAsOfDate());
        }
        System.out.println("Counter opportunity policy: " + decision.policyId());
        System.out.println("Recommendation policy: " + decision.recommendationPolicyId());
        System.out.println("Perspective policy: " + decision.perspectivePolicyId());
        System.out.println("Strategic eligibility policy: " + decision.strategicEligibilityPolicyId());
        System.out.println("V5 evidence complete: " + v5.evidenceStatus().complete());
        System.out.println(ButlerTradeRecommendationCli.formatEvidenceGates(v5.evidenceStatus()));
        System.out.println("V5 package recommendation: " + v5.packageRecommendation());
        System.out.println("V5 team action: " + v5.action());
        System.out.println("Strategic eligibility evaluated: " + eligibilityEvaluated);
        if (eligibilityEvaluated) {
            System.out.println("Strategic eligibility available: " + eligibility.available());
            if (eligibility.available()) {
                System.out.println("Strategically eligible candidates: " + eligibility.eligibleCandidates().size());
            } else {
                System.out.println("Strategic eligibility reason: " + eligibility.insufficiencyReason());
            }
        } else {
            System.out.println("Strategic eligibility reason: not required because v5 did not produce a complete REJECT.");
        }
        System.out.println("Counter opportunity: " + decision.state());
        System.out.println("Counter opportunity reason: " + decision.reasonCode());
        if (decision.state() == TradeCounterOpportunityPolicy.State.COUNTER_AVAILABLE) {
            System.out.println("Eligible market ranks: " + decision.eligibleMarketRanks());
        }
    }

    private static void printSelection(
        TradeCounterOpportunityPolicy.Decision decision,
        TradeCounterCandidateSelectionPolicy.Selection selection) {
        if (selection == null) throw new IllegalArgumentException("selection must not be null");
        if (!decision.leagueId().equals(selection.leagueId())
            || decision.season() != selection.season()
            || !decision.source().equals(selection.source())
            || !java.util.Objects.equals(decision.minimumAsOfDate(), selection.minimumAsOfDate())
            || !decision.policyId().equals(selection.opportunityPolicyId())) {
            throw new IllegalStateException("counter opportunity and candidate selection coordinates differ");
        }
        System.out.println("Counter candidate selection policy: " + selection.policyId());
        System.out.println("Counter candidate selection: " + selection.state());
        System.out.println("Counter candidate selection reason: " + selection.reasonCode());
        if (selection.state() == TradeCounterCandidateSelectionPolicy.State.SELECTED) {
            var selected = selection.selectedCandidate();
            var candidate = selected.candidate();
            System.out.println("Selected market rank: " + selected.marketRank());
            System.out.println("Selected adjustment: " + candidate.adjustmentType() + " " + candidate.side());
            System.out.println("Selected asset: " + candidate.displayName() + " [" + candidate.assetId() + "] " + candidate.assetType());
            System.out.printf(Locale.ROOT,
                "Selected market criteria: excess=%.2f asset-value=%.2f required-change=%.2f resulting-gap=%.3f%%%n",
                candidate.excessValue(), candidate.assetValue(), candidate.requiredValueChange(),
                candidate.resultingGapPercent());
            System.out.println("The asset is selected by governed market criteria after strategic eligibility; no COUNTER action is emitted.");
        } else if (selection.state() == TradeCounterCandidateSelectionPolicy.State.AMBIGUOUS) {
            System.out.println("Ambiguous top market ranks: " + selection.ambiguousMarketRanks());
            System.out.println("No asset is selected because the top eligible candidates tie on governed selection criteria.");
            System.out.println("No COUNTER action is emitted.");
        } else {
            System.out.println("No candidate is selected and no COUNTER action is emitted.");
        }
    }

    static void printUsage() {
        System.out.println("  butler trade counter-decision <league-id> <season> <side-a-assets> <side-b-assets> <side-a|side-b> [source] [--minimum-as-of YYYY-MM-DD]");
        System.out.println("  Perspective is the team giving that side's package and receiving the opposite package.");
        System.out.println("  A candidate is selected only when uniquely best on governed market criteria; ties remain ambiguous.");
        System.out.println("  Candidate selection does not emit a COUNTER action.");
    }

    private static TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport analyzeRecommendation(
        TradeFlexibleRecommendationContextAnalyzer analyzer,
        Options options) throws SQLException {
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
        Options options) throws SQLException {
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

    private static TradeTeamPerspectiveRecommendationPolicy.Perspective parsePerspective(String value) {
        return switch (requireText(value, "perspective").toLowerCase()) {
            case "side-a", "a" -> TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM;
            case "side-b", "b" -> TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_B_TEAM;
            default -> throw new IllegalArgumentException("perspective must be side-a or side-b");
        };
    }

    private static int parseSeason(String value) {
        try {
            int season = Integer.parseInt(value);
            if (season < 1999 || season > 2100) throw new NumberFormatException();
            return season;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("season must be a year between 1999 and 2100: " + value);
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(requireText(value, "minimum-as-of"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("minimum-as-of must use YYYY-MM-DD: " + value);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    record Options(
        String leagueId,
        int season,
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB,
        TradeTeamPerspectiveRecommendationPolicy.Perspective perspective,
        String source,
        LocalDate minimumAsOf) {}
}
