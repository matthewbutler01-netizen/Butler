package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeFlexibleCoverageMaterialLossAnalyzer;
import io.butler.bet.intelligence.TradeFlexiblePostTradeDepthAnalyzer;
import io.butler.bet.intelligence.TradeFlexiblePressureTransitionAnalyzer;
import io.butler.bet.intelligence.TradeFlexibleRecommendationContextAnalyzer;
import io.butler.bet.intelligence.TradeMarketEdgePolicy;
import io.butler.bet.intelligence.TradeProtectedValueFlowAnalyzer;
import io.butler.bet.intelligence.TradeProtectedValueMaterialityPolicy;
import io.butler.bet.intelligence.TradeRecommendationAdvisoryPosturePolicy;
import io.butler.bet.intelligence.TradeRecommendationPolicy;
import io.butler.bet.intelligence.TradeRecommendationVetoPolicy;
import io.butler.bet.intelligence.TradeStrategicFlexibleTransitionMaterialLossVetoDetector;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Live v6 trade recommendation CLI.
 *
 * Team posture remains visible strategic context, but it is advisory because the posture model
 * deliberately cannot classify competitive tier until enough games exist. The governed decision
 * gate remains exact market direction plus future-capital, positional-pressure, and
 * FLEX/SUPERFLEX-pressure evidence.
 */
public final class ButlerTradeRecommendationV6Cli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeRecommendationV6Cli() {}

    public static void main(String[] args) {
        ButlerTradeRecommendationCli.Options options;
        try {
            options = ButlerTradeRecommendationCli.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            ButlerTradeRecommendationCli.printUsage();
            return;
        }

        try {
            var analyzer = new TradeFlexibleRecommendationContextAnalyzer(initializedDatabase());
            var report = analyze(analyzer, options);
            print(report, options);
        } catch (SQLException e) {
            System.err.println("Database error while building trade recommendation: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static V6RecommendationResult recommend(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        TradeTeamPerspectiveRecommendationPolicy.Perspective perspective) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(perspective, "perspective must not be null");

        var report = context.trade();
        boolean positionalAvailable = report.positionAvailability().values().stream().allMatch(
            io.butler.bet.intelligence.TradeAssetPositionalContextAnalyzer.PositionAvailability::available);
        boolean marketDirectionAvailable =
            report.strategic().marketEdge() != TradeMarketEdgePolicy.Direction.UNAVAILABLE;

        var evidence = new TradeRecommendationAdvisoryPosturePolicy.EvidenceGate(
            report.strategic().futureCapitalAvailable(),
            positionalAvailable,
            context.flexible().flexiblePressureAvailable());
        var status = new V6EvidenceStatus(
            marketDirectionAvailable,
            report.strategic().postureAvailable(),
            evidence.futureCapitalAvailable(),
            evidence.positionalPressureAvailable(),
            evidence.flexiblePressureAvailable());

        V6VetoEvaluation veto;
        TradeFlexibleCoverageMaterialLossAnalyzer.Assessment flexibleLoss = null;
        TradeFlexiblePressureTransitionAnalyzer.Assessment transition = null;

        if (status.complete()) {
            boolean sideA = perspective == TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM;
            var team = sideA ? report.strategic().sideA() : report.strategic().sideB();
            var positional = sideA ? report.sideA() : report.sideB();
            var flexibleTeam = sideA ? context.flexible().sideA() : context.flexible().sideB();
            var outgoing = sideA ? report.strategic().trade().sideA() : report.strategic().trade().sideB();
            var incoming = sideA ? report.strategic().trade().sideB() : report.strategic().trade().sideA();

            flexibleLoss = TradeFlexibleCoverageMaterialLossAnalyzer.assess(
                context.flexible(), flexibleTeam, context.lineup(), context.depth(), outgoing, incoming);
            transition = TradeFlexiblePressureTransitionAnalyzer.assess(
                context, flexibleTeam, outgoing, incoming);
            veto = evaluated(TradeStrategicFlexibleTransitionMaterialLossVetoDetector.assess(
                team, positional, flexibleLoss, transition, outgoing, incoming));
        } else {
            veto = new V6VetoEvaluation(false, TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of());
        }

        var packageRecommendation = TradeRecommendationAdvisoryPosturePolicy.classify(
            report.strategic().marketEdge(), evidence, veto.state());
        var action = TradeTeamPerspectiveRecommendationPolicy.classify(packageRecommendation, perspective);

        return new V6RecommendationResult(
            packageRecommendation, action, status, veto, flexibleLoss, transition);
    }

    static void print(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        ButlerTradeRecommendationCli.Options options) {
        var result = recommend(context, options.perspective());
        var report = context.trade();
        var trade = report.strategic().trade();
        boolean sideA = options.perspective() == TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM;
        var perspectiveTeam = sideA ? report.strategic().sideA().identity() : report.strategic().sideB().identity();
        var flexibleTeam = sideA ? context.flexible().sideA() : context.flexible().sideB();

        System.out.println("Trade recommendation (market-first; team posture advisory)");
        System.out.println("League ID: " + trade.leagueId());
        System.out.println("Season: " + options.season());
        System.out.println("Perspective: " + perspectiveTeam.teamName() + " [" + perspectiveTeam.teamId() + "]");
        System.out.println("Recommendation policy: " + TradeRecommendationAdvisoryPosturePolicy.POLICY_ID);
        System.out.println("Strategic veto policy: " + TradeStrategicFlexibleTransitionMaterialLossVetoDetector.POLICY_ID);
        System.out.println("Flexible pressure policy: " + context.flexible().flexiblePressurePolicyId());
        System.out.println("Flexible coverage policy: " + context.flexible().flexibleCoveragePolicyId());
        System.out.println("Flexible coverage loss policy: " + TradeFlexibleCoverageMaterialLossAnalyzer.POLICY_ID);
        System.out.println("Flexible transition policy: " + TradeFlexiblePressureTransitionAnalyzer.POLICY_ID);
        System.out.println("Post-trade depth policy: " + TradeFlexiblePostTradeDepthAnalyzer.POLICY_ID);
        System.out.println("Protected value flow policy: " + TradeProtectedValueFlowAnalyzer.POLICY_ID);
        System.out.println("Protected value materiality policy: " + TradeProtectedValueMaterialityPolicy.POLICY_ID);
        System.out.println("Perspective policy: " + TradeTeamPerspectiveRecommendationPolicy.POLICY_ID);
        System.out.println("Evidence complete: " + result.evidenceStatus().complete());
        System.out.println(formatEvidenceGates(result.evidenceStatus()));
        System.out.println("Team posture advisory: "
            + (result.evidenceStatus().postureAvailable() ? "AVAILABLE" : "UNAVAILABLE")
            + " (does not gate the recommendation)");
        System.out.println("Flexible pressure: " + flexibleTeam.pressure().tier());

        if (!context.flexible().flexiblePressureAvailable()) {
            System.out.println("Flexible pressure reason: " + context.flexible().flexiblePressureInsufficiencyReason());
        }
        if (result.flexibleLossAssessment() != null
            && result.flexibleLossAssessment().protectedPressureArea()) {
            System.out.println(String.format(Locale.ROOT,
                "Flexible protected coverage: %.2f -> %.2f (%s loss)",
                result.flexibleLossAssessment().preTradeCoverageValue(),
                result.flexibleLossAssessment().postTradeCoverageValue(),
                ButlerTradeRecommendationCli.formatLossPercent(result.flexibleLossAssessment().lossFraction())));
        }
        if (result.transitionAssessment() != null) {
            System.out.println("Flexible pressure transition: "
                + result.transitionAssessment().preTradeTier() + " -> "
                + result.transitionAssessment().postTradeTier());
            System.out.println("Flexible transition state: " + result.transitionAssessment().state());
            if (result.transitionAssessment().preTradeCoverageValue() != null) {
                System.out.println(String.format(Locale.ROOT,
                    "Flexible transition coverage: %.2f -> %.2f (%s loss)",
                    result.transitionAssessment().preTradeCoverageValue(),
                    result.transitionAssessment().postTradeCoverageValue(),
                    ButlerTradeRecommendationCli.formatLossPercent(result.transitionAssessment().lossFraction())));
            }
        }

        System.out.println("Strategic veto: " + (result.vetoAssessment().evaluated()
            ? result.vetoAssessment().state()
            : "NOT_EVALUATED"));
        for (var reason : result.vetoAssessment().reasons()) {
            System.out.println("Veto reason: " + ButlerTradeRecommendationV5Cli.formatVetoReason(reason));
        }

        System.out.println("Package recommendation: " + result.packageRecommendation());
        System.out.println("Action: " + result.action());

        if (result.action() == TradeTeamPerspectiveRecommendationPolicy.Action.INCONCLUSIVE) {
            System.out.println("Reason: " + formatInconclusiveReason(result.evidenceStatus()) + ".");
        } else if (result.action() == TradeTeamPerspectiveRecommendationPolicy.Action.HOLD) {
            if (result.vetoAssessment().evaluated()
                && result.vetoAssessment().state() == TradeRecommendationVetoPolicy.VetoState.BLOCKED
                && report.strategic().marketEdge() != TradeMarketEdgePolicy.Direction.MARKET_FAIR) {
                System.out.println(
                    "Reason: a governed strategic material-loss veto blocked the directional market recommendation.");
            } else {
                System.out.println("Reason: the governed market comparison is inside the fairness band.");
            }
        }

        System.out.println(
            "Team posture is advisory only; no hidden weighting, side flipping, or strategic score blending is applied.");
    }

    static String formatEvidenceGates(V6EvidenceStatus status) {
        return "Evidence gates: market-direction=" + status.marketDirectionAvailable()
            + " future-capital=" + status.futureCapitalAvailable()
            + " positional-pressure=" + status.positionalPressureAvailable()
            + " flexible-pressure=" + status.flexiblePressureAvailable()
            + " advisory-posture=" + status.postureAvailable();
    }

    static String formatInconclusiveReason(V6EvidenceStatus status) {
        List<String> missing = new ArrayList<>();
        if (!status.marketDirectionAvailable()) missing.add("market direction");
        if (!status.futureCapitalAvailable()) missing.add("future capital");
        if (!status.positionalPressureAvailable()) missing.add("positional pressure");
        if (!status.flexiblePressureAvailable()) missing.add("flexible pressure");
        return missing.isEmpty()
            ? "required governed decision evidence is incomplete"
            : "unavailable governed evidence: " + String.join(", ", missing);
    }

    private static TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport analyze(
        TradeFlexibleRecommendationContextAnalyzer analyzer,
        ButlerTradeRecommendationCli.Options options) throws SQLException {
        if (options.minimumAsOf() != null) {
            return options.source() == null
                ? analyzer.analyze(
                    options.leagueId(), options.season(), options.sideA(), options.sideB(), options.minimumAsOf())
                : analyzer.analyze(
                    options.leagueId(), options.season(), options.sideA(), options.sideB(),
                    options.source(), options.minimumAsOf());
        }
        return options.source() == null
            ? analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB())
            : analyzer.analyze(
                options.leagueId(), options.season(), options.sideA(), options.sideB(), options.source());
    }

    private static V6VetoEvaluation evaluated(
        TradeStrategicFlexibleTransitionMaterialLossVetoDetector.VetoAssessment assessment) {
        return new V6VetoEvaluation(true, assessment.state(), assessment.reasons());
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    record V6EvidenceStatus(
        boolean marketDirectionAvailable,
        boolean postureAvailable,
        boolean futureCapitalAvailable,
        boolean positionalPressureAvailable,
        boolean flexiblePressureAvailable) {
        boolean complete() {
            return marketDirectionAvailable
                && futureCapitalAvailable
                && positionalPressureAvailable
                && flexiblePressureAvailable;
        }
    }

    record V6VetoEvaluation(
        boolean evaluated,
        TradeRecommendationVetoPolicy.VetoState state,
        List<TradeStrategicFlexibleTransitionMaterialLossVetoDetector.VetoReason> reasons) {
        V6VetoEvaluation {
            Objects.requireNonNull(state, "state must not be null");
            reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons must not be null"));
            if (!evaluated && state != TradeRecommendationVetoPolicy.VetoState.CLEAR) {
                throw new IllegalArgumentException("not-evaluated veto state must be CLEAR for policy input");
            }
            if (!evaluated && !reasons.isEmpty()) {
                throw new IllegalArgumentException("not-evaluated veto cannot contain reasons");
            }
            if (evaluated && state == TradeRecommendationVetoPolicy.VetoState.BLOCKED && reasons.isEmpty()) {
                throw new IllegalArgumentException("blocked veto evaluation requires reasons");
            }
            if (evaluated && state == TradeRecommendationVetoPolicy.VetoState.CLEAR && !reasons.isEmpty()) {
                throw new IllegalArgumentException("clear veto evaluation cannot contain reasons");
            }
        }
    }

    record V6RecommendationResult(
        TradeRecommendationPolicy.Recommendation packageRecommendation,
        TradeTeamPerspectiveRecommendationPolicy.Action action,
        V6EvidenceStatus evidenceStatus,
        V6VetoEvaluation vetoAssessment,
        TradeFlexibleCoverageMaterialLossAnalyzer.Assessment flexibleLossAssessment,
        TradeFlexiblePressureTransitionAnalyzer.Assessment transitionAssessment) {}
}
