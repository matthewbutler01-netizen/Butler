package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeAssetPositionalContextAnalyzer;
import io.butler.bet.intelligence.TradeFlexibleCoverageMaterialLossAnalyzer;
import io.butler.bet.intelligence.TradeFlexibleRecommendationContextAnalyzer;
import io.butler.bet.intelligence.TradeMarketEdgePolicy;
import io.butler.bet.intelligence.TradeProtectedValueFlowAnalyzer;
import io.butler.bet.intelligence.TradeProtectedValueMaterialityPolicy;
import io.butler.bet.intelligence.TradeRecommendationFlexibleMaterialLossPolicy;
import io.butler.bet.intelligence.TradeRecommendationMaterialLossPolicy;
import io.butler.bet.intelligence.TradeRecommendationPolicy;
import io.butler.bet.intelligence.TradeRecommendationVetoPolicy;
import io.butler.bet.intelligence.TradeStrategicFlexibleMaterialLossVetoDetector;
import io.butler.bet.intelligence.TradeStrategicMaterialLossVetoDetector;
import io.butler.bet.intelligence.TradeStrategicVetoDetector;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Conservative market-first trade recommendation with explicit material-loss vetoes and team perspective. */
public final class ButlerTradeRecommendationCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeRecommendationCli() {}

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

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length < 7 || args.length > 10) {
            throw new IllegalArgumentException("trade recommendation requires league, season, two asset packages, and perspective");
        }
        String leagueId = requireText(args[2], "league-id");
        int season = parseSeason(args[3]);
        TradeAssetAnalyzer.TradePackage sideA = ButlerApp.parseTradePackage(args[4], "side-a-assets");
        TradeAssetAnalyzer.TradePackage sideB = ButlerApp.parseTradePackage(args[5], "side-b-assets");
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
            throw new IllegalArgumentException("invalid trade recommendation optional arguments");
        }
        return new Options(leagueId, season, sideA, sideB, perspective, source, minimumAsOf);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "trade".equalsIgnoreCase(args[0])
            && "recommendation".equalsIgnoreCase(args[1]);
    }

    /** Retained v3 compatibility surface. The executable path uses the v4 overload below. */
    static RecommendationResult recommend(TradeAssetPositionalContextAnalyzer.TradePositionalContextReport report,
                                          TradeTeamPerspectiveRecommendationPolicy.Perspective perspective) {
        boolean positionalAvailable = report.positionAvailability().values().stream().allMatch(
            TradeAssetPositionalContextAnalyzer.PositionAvailability::available);
        var evidence = new TradeRecommendationPolicy.EvidenceGate(
            report.strategic().postureAvailable(), report.strategic().futureCapitalAvailable(), positionalAvailable);
        var status = new EvidenceStatus(
            report.strategic().marketEdge() != TradeMarketEdgePolicy.Direction.UNAVAILABLE,
            evidence.postureAvailable(), evidence.futureCapitalAvailable(), evidence.positionalPressureAvailable());

        VetoEvaluation veto;
        if (status.complete()) {
            boolean sideA = perspective == TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM;
            var team = sideA ? report.strategic().sideA() : report.strategic().sideB();
            var positional = sideA ? report.sideA() : report.sideB();
            var outgoing = sideA ? report.strategic().trade().sideA() : report.strategic().trade().sideB();
            var incoming = sideA ? report.strategic().trade().sideB() : report.strategic().trade().sideA();
            veto = evaluated(TradeStrategicMaterialLossVetoDetector.assess(team, positional, outgoing, incoming));
        } else {
            veto = new VetoEvaluation(false, TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of());
        }

        var packageRecommendation = TradeRecommendationMaterialLossPolicy.classify(
            report.strategic().marketEdge(), evidence, veto.state());
        var action = TradeTeamPerspectiveRecommendationPolicy.classify(packageRecommendation, perspective);
        return new RecommendationResult(packageRecommendation, action, status, veto);
    }

    static FlexibleRecommendationResult recommend(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        TradeTeamPerspectiveRecommendationPolicy.Perspective perspective) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(perspective, "perspective must not be null");
        var report = context.trade();
        boolean positionalAvailable = report.positionAvailability().values().stream().allMatch(
            TradeAssetPositionalContextAnalyzer.PositionAvailability::available);
        var evidence = new TradeRecommendationFlexibleMaterialLossPolicy.EvidenceGate(
            report.strategic().postureAvailable(),
            report.strategic().futureCapitalAvailable(),
            positionalAvailable,
            context.flexible().flexiblePressureAvailable());
        var status = new FlexibleEvidenceStatus(
            report.strategic().marketEdge() != TradeMarketEdgePolicy.Direction.UNAVAILABLE,
            evidence.postureAvailable(),
            evidence.futureCapitalAvailable(),
            evidence.positionalPressureAvailable(),
            evidence.flexiblePressureAvailable());

        FlexibleVetoEvaluation veto;
        TradeFlexibleCoverageMaterialLossAnalyzer.Assessment flexibleLoss = null;
        if (status.complete()) {
            boolean sideA = perspective == TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM;
            var team = sideA ? report.strategic().sideA() : report.strategic().sideB();
            var positional = sideA ? report.sideA() : report.sideB();
            var flexibleTeam = sideA ? context.flexible().sideA() : context.flexible().sideB();
            var outgoing = sideA ? report.strategic().trade().sideA() : report.strategic().trade().sideB();
            var incoming = sideA ? report.strategic().trade().sideB() : report.strategic().trade().sideA();
            flexibleLoss = TradeFlexibleCoverageMaterialLossAnalyzer.assess(
                context.flexible(), flexibleTeam, context.lineup(), context.depth(), outgoing, incoming);
            veto = evaluated(TradeStrategicFlexibleMaterialLossVetoDetector.assess(
                team, positional, flexibleLoss, outgoing, incoming));
        } else {
            veto = new FlexibleVetoEvaluation(false, TradeRecommendationVetoPolicy.VetoState.CLEAR, List.of());
        }

        var packageRecommendation = TradeRecommendationFlexibleMaterialLossPolicy.classify(
            report.strategic().marketEdge(), evidence, veto.state());
        var action = TradeTeamPerspectiveRecommendationPolicy.classify(packageRecommendation, perspective);
        return new FlexibleRecommendationResult(packageRecommendation, action, status, veto, flexibleLoss);
    }

    private static VetoEvaluation evaluated(TradeStrategicMaterialLossVetoDetector.VetoAssessment assessment) {
        return new VetoEvaluation(true, assessment.state(), assessment.reasons());
    }

    private static FlexibleVetoEvaluation evaluated(
        TradeStrategicFlexibleMaterialLossVetoDetector.VetoAssessment assessment) {
        return new FlexibleVetoEvaluation(true, assessment.state(), assessment.reasons());
    }

    static String formatEvidenceGates(EvidenceStatus status) {
        return "Evidence gates: market-direction=" + status.marketDirectionAvailable()
            + " posture=" + status.postureAvailable()
            + " future-capital=" + status.futureCapitalAvailable()
            + " positional-pressure=" + status.positionalPressureAvailable();
    }

    static String formatEvidenceGates(FlexibleEvidenceStatus status) {
        return "Evidence gates: market-direction=" + status.marketDirectionAvailable()
            + " posture=" + status.postureAvailable()
            + " future-capital=" + status.futureCapitalAvailable()
            + " positional-pressure=" + status.positionalPressureAvailable()
            + " flexible-pressure=" + status.flexiblePressureAvailable();
    }

    static String formatInconclusiveReason(EvidenceStatus status) {
        List<String> missing = new ArrayList<>();
        if (!status.marketDirectionAvailable()) missing.add("market direction");
        if (!status.postureAvailable()) missing.add("team posture");
        if (!status.futureCapitalAvailable()) missing.add("future capital");
        if (!status.positionalPressureAvailable()) missing.add("positional pressure");
        return missing.isEmpty()
            ? "required governed evidence is incomplete"
            : "unavailable governed evidence: " + String.join(", ", missing);
    }

    static String formatInconclusiveReason(FlexibleEvidenceStatus status) {
        List<String> missing = new ArrayList<>();
        if (!status.marketDirectionAvailable()) missing.add("market direction");
        if (!status.postureAvailable()) missing.add("team posture");
        if (!status.futureCapitalAvailable()) missing.add("future capital");
        if (!status.positionalPressureAvailable()) missing.add("positional pressure");
        if (!status.flexiblePressureAvailable()) missing.add("flexible pressure");
        return missing.isEmpty()
            ? "required governed evidence is incomplete"
            : "unavailable governed evidence: " + String.join(", ", missing);
    }

    static String formatVetoReason(TradeStrategicMaterialLossVetoDetector.VetoReason reason) {
        String protectedArea = switch (reason.code()) {
            case LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS ->
                "low future capital: future-pick protected value";
            case POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS ->
                reason.position() + " pressure: " + reason.position() + " protected value";
        };
        return formatMaterialLossReason(
            protectedArea,
            reason.outgoingProtectedValue(),
            reason.incomingProtectedValue(),
            reason.lossFraction());
    }

    static String formatVetoReason(TradeStrategicFlexibleMaterialLossVetoDetector.VetoReason reason) {
        String protectedArea = switch (reason.code()) {
            case LOW_FUTURE_CAPITAL_MATERIAL_PICK_VALUE_LOSS ->
                "low future capital: future-pick protected value";
            case POSITION_PRESSURE_MATERIAL_SAME_POSITION_VALUE_LOSS ->
                reason.position() + " pressure: " + reason.position() + " protected value";
            case FLEXIBLE_PRESSURE_MATERIAL_POST_TRADE_COVERAGE_LOSS ->
                "FLEX/SUPERFLEX pressure: legal coverage value";
        };
        return formatMaterialLossReason(
            protectedArea,
            reason.outgoingProtectedValue(),
            reason.incomingProtectedValue(),
            reason.lossFraction());
    }

    private static String formatMaterialLossReason(
        String protectedArea, double before, double after, double lossFraction) {
        return String.format(Locale.ROOT,
            "%s %.2f -> %.2f (%s loss; material when loss > %.1f%%)",
            protectedArea,
            before,
            after,
            formatLossPercent(lossFraction),
            TradeProtectedValueMaterialityPolicy.MAX_ALLOWED_LOSS_FRACTION * 100.0);
    }

    static String formatLossPercent(double lossFraction) {
        double percent = lossFraction * 100.0;
        String rounded = String.format(Locale.ROOT, "%.1f%%", percent);
        String threshold = String.format(Locale.ROOT, "%.1f%%",
            TradeProtectedValueMaterialityPolicy.MAX_ALLOWED_LOSS_FRACTION * 100.0);
        if (Double.compare(lossFraction, TradeProtectedValueMaterialityPolicy.MAX_ALLOWED_LOSS_FRACTION) > 0
            && rounded.equals(threshold)) {
            return ">" + threshold;
        }
        return rounded;
    }

    /** Retained for the versioned v1 detector contract; the live recommendation path uses v3. */
    static String formatVetoReason(TradeStrategicVetoDetector.VetoReason reason) {
        return switch (reason.code()) {
            case LOW_FUTURE_CAPITAL_OUTGOING_PICKS_WITHOUT_PICK_RETURN ->
                "low future capital: sending future pick(s) without receiving a future pick";
            case POSITION_PRESSURE_OUTGOING_WITHOUT_SAME_POSITION_RETURN ->
                reason.position() + " pressure: sending " + reason.position() + " without receiving " + reason.position();
        };
    }

    private static TradeAssetPositionalContextAnalyzer.TradePositionalContextReport analyze(
        TradeAssetPositionalContextAnalyzer analyzer, Options options) throws SQLException {
        if (options.minimumAsOf() != null) {
            return options.source() == null
                ? analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.minimumAsOf())
                : analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.source(), options.minimumAsOf());
        }
        return options.source() == null
            ? analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB())
            : analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.source());
    }

    private static TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport analyze(
        TradeFlexibleRecommendationContextAnalyzer analyzer, Options options) throws SQLException {
        if (options.minimumAsOf() != null) {
            return options.source() == null
                ? analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.minimumAsOf())
                : analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.source(), options.minimumAsOf());
        }
        return options.source() == null
            ? analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB())
            : analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.source());
    }

    /** Retained v3 compatibility output. The executable path uses the v4 overload below. */
    static void print(TradeAssetPositionalContextAnalyzer.TradePositionalContextReport report, Options options) {
        var result = recommend(report, options.perspective());
        var trade = report.strategic().trade();
        var perspectiveTeam = options.perspective() == TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM
            ? report.strategic().sideA().identity() : report.strategic().sideB().identity();
        System.out.println("Trade recommendation (conservative market-first material-loss veto)");
        System.out.println("League ID: " + trade.leagueId());
        System.out.println("Season: " + options.season());
        System.out.println("Perspective: " + perspectiveTeam.teamName() + " [" + perspectiveTeam.teamId() + "]");
        System.out.println("Recommendation policy: " + TradeRecommendationMaterialLossPolicy.POLICY_ID);
        System.out.println("Strategic veto policy: " + TradeStrategicMaterialLossVetoDetector.POLICY_ID);
        System.out.println("Protected value flow policy: " + TradeProtectedValueFlowAnalyzer.POLICY_ID);
        System.out.println("Protected value materiality policy: " + TradeProtectedValueMaterialityPolicy.POLICY_ID);
        System.out.println("Perspective policy: " + TradeTeamPerspectiveRecommendationPolicy.POLICY_ID);
        System.out.println("Evidence complete: " + result.evidenceStatus().complete());
        System.out.println(formatEvidenceGates(result.evidenceStatus()));
        System.out.println("Strategic veto: " + (result.vetoAssessment().evaluated()
            ? result.vetoAssessment().state()
            : "NOT_EVALUATED"));
        for (var reason : result.vetoAssessment().reasons()) {
            System.out.println("Veto reason: " + formatVetoReason(reason));
        }
        System.out.println("Package recommendation: " + result.packageRecommendation());
        System.out.println("Action: " + result.action());
        if (result.action() == TradeTeamPerspectiveRecommendationPolicy.Action.INCONCLUSIVE) {
            System.out.println("Reason: " + formatInconclusiveReason(result.evidenceStatus()) + ".");
        } else if (result.action() == TradeTeamPerspectiveRecommendationPolicy.Action.HOLD) {
            if (result.vetoAssessment().evaluated()
                && result.vetoAssessment().state() == TradeRecommendationVetoPolicy.VetoState.BLOCKED
                && report.strategic().marketEdge() != TradeMarketEdgePolicy.Direction.MARKET_FAIR) {
                System.out.println("Reason: a governed strategic material-loss veto blocked the directional market recommendation.");
            } else {
                System.out.println("Reason: the governed market comparison is inside the fairness band.");
            }
        }
        System.out.println("No hidden weighting, side flipping, or strategic score blending is applied.");
    }

    static void print(
        TradeFlexibleRecommendationContextAnalyzer.TradeFlexibleRecommendationContextReport context,
        Options options) {
        var result = recommend(context, options.perspective());
        var report = context.trade();
        var trade = report.strategic().trade();
        boolean sideA = options.perspective() == TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM;
        var perspectiveTeam = sideA ? report.strategic().sideA().identity() : report.strategic().sideB().identity();
        var flexibleTeam = sideA ? context.flexible().sideA() : context.flexible().sideB();

        System.out.println("Trade recommendation (conservative market-first flexible material-loss veto)");
        System.out.println("League ID: " + trade.leagueId());
        System.out.println("Season: " + options.season());
        System.out.println("Perspective: " + perspectiveTeam.teamName() + " [" + perspectiveTeam.teamId() + "]");
        System.out.println("Recommendation policy: " + TradeRecommendationFlexibleMaterialLossPolicy.POLICY_ID);
        System.out.println("Strategic veto policy: " + TradeStrategicFlexibleMaterialLossVetoDetector.POLICY_ID);
        System.out.println("Flexible pressure policy: " + context.flexible().flexiblePressurePolicyId());
        System.out.println("Flexible coverage policy: " + context.flexible().flexibleCoveragePolicyId());
        System.out.println("Flexible coverage loss policy: " + TradeFlexibleCoverageMaterialLossAnalyzer.POLICY_ID);
        System.out.println("Protected value flow policy: " + TradeProtectedValueFlowAnalyzer.POLICY_ID);
        System.out.println("Protected value materiality policy: " + TradeProtectedValueMaterialityPolicy.POLICY_ID);
        System.out.println("Perspective policy: " + TradeTeamPerspectiveRecommendationPolicy.POLICY_ID);
        System.out.println("Evidence complete: " + result.evidenceStatus().complete());
        System.out.println(formatEvidenceGates(result.evidenceStatus()));
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
                formatLossPercent(result.flexibleLossAssessment().lossFraction())));
        }
        System.out.println("Strategic veto: " + (result.vetoAssessment().evaluated()
            ? result.vetoAssessment().state()
            : "NOT_EVALUATED"));
        for (var reason : result.vetoAssessment().reasons()) {
            System.out.println("Veto reason: " + formatVetoReason(reason));
        }
        System.out.println("Package recommendation: " + result.packageRecommendation());
        System.out.println("Action: " + result.action());
        if (result.action() == TradeTeamPerspectiveRecommendationPolicy.Action.INCONCLUSIVE) {
            System.out.println("Reason: " + formatInconclusiveReason(result.evidenceStatus()) + ".");
        } else if (result.action() == TradeTeamPerspectiveRecommendationPolicy.Action.HOLD) {
            if (result.vetoAssessment().evaluated()
                && result.vetoAssessment().state() == TradeRecommendationVetoPolicy.VetoState.BLOCKED
                && report.strategic().marketEdge() != TradeMarketEdgePolicy.Direction.MARKET_FAIR) {
                System.out.println("Reason: a governed strategic material-loss veto blocked the directional market recommendation.");
            } else {
                System.out.println("Reason: the governed market comparison is inside the fairness band.");
            }
        }
        System.out.println("No hidden weighting, side flipping, or strategic score blending is applied.");
    }

    static void printUsage() {
        System.out.println("  butler trade recommendation <league-id> <season> <side-a-assets> <side-b-assets> <side-a|side-b> [source] [--minimum-as-of YYYY-MM-DD]");
        System.out.println("  Perspective is the team giving that side's package and receiving the opposite package.");
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
            return LocalDate.parse(value);
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

    record VetoEvaluation(boolean evaluated,
                          TradeRecommendationVetoPolicy.VetoState state,
                          List<TradeStrategicMaterialLossVetoDetector.VetoReason> reasons) {
        VetoEvaluation {
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

    record FlexibleVetoEvaluation(boolean evaluated,
                                  TradeRecommendationVetoPolicy.VetoState state,
                                  List<TradeStrategicFlexibleMaterialLossVetoDetector.VetoReason> reasons) {
        FlexibleVetoEvaluation {
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

    record EvidenceStatus(boolean marketDirectionAvailable, boolean postureAvailable,
                          boolean futureCapitalAvailable, boolean positionalPressureAvailable) {
        boolean complete() {
            return marketDirectionAvailable && postureAvailable && futureCapitalAvailable && positionalPressureAvailable;
        }
    }

    record FlexibleEvidenceStatus(boolean marketDirectionAvailable, boolean postureAvailable,
                                  boolean futureCapitalAvailable, boolean positionalPressureAvailable,
                                  boolean flexiblePressureAvailable) {
        boolean complete() {
            return marketDirectionAvailable && postureAvailable && futureCapitalAvailable
                && positionalPressureAvailable && flexiblePressureAvailable;
        }
    }

    record RecommendationResult(TradeRecommendationPolicy.Recommendation packageRecommendation,
                                TradeTeamPerspectiveRecommendationPolicy.Action action,
                                EvidenceStatus evidenceStatus,
                                VetoEvaluation vetoAssessment) {}

    record FlexibleRecommendationResult(
        TradeRecommendationPolicy.Recommendation packageRecommendation,
        TradeTeamPerspectiveRecommendationPolicy.Action action,
        FlexibleEvidenceStatus evidenceStatus,
        FlexibleVetoEvaluation vetoAssessment,
        TradeFlexibleCoverageMaterialLossAnalyzer.Assessment flexibleLossAssessment) {}

    record Options(String leagueId, int season, TradeAssetAnalyzer.TradePackage sideA,
                   TradeAssetAnalyzer.TradePackage sideB,
                   TradeTeamPerspectiveRecommendationPolicy.Perspective perspective,
                   String source, LocalDate minimumAsOf) {}
}
