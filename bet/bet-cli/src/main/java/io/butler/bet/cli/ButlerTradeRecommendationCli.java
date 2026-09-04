package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeAssetPositionalContextAnalyzer;
import io.butler.bet.intelligence.TradeMarketEdgePolicy;
import io.butler.bet.intelligence.TradeRecommendationPolicy;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Conservative evidence-first trade recommendation from one explicit team's perspective. */
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
            var analyzer = new TradeAssetPositionalContextAnalyzer(initializedDatabase());
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

    static RecommendationResult recommend(TradeAssetPositionalContextAnalyzer.TradePositionalContextReport report,
                                          TradeTeamPerspectiveRecommendationPolicy.Perspective perspective) {
        boolean positionalAvailable = report.positionAvailability().values().stream().allMatch(
            TradeAssetPositionalContextAnalyzer.PositionAvailability::available);
        var evidence = new TradeRecommendationPolicy.EvidenceGate(
            report.strategic().postureAvailable(), report.strategic().futureCapitalAvailable(), positionalAvailable);
        var packageRecommendation = TradeRecommendationPolicy.classify(report.strategic().marketEdge(), evidence);
        var action = TradeTeamPerspectiveRecommendationPolicy.classify(packageRecommendation, perspective);
        var status = new EvidenceStatus(
            report.strategic().marketEdge() != TradeMarketEdgePolicy.Direction.UNAVAILABLE,
            evidence.postureAvailable(), evidence.futureCapitalAvailable(), evidence.positionalPressureAvailable());
        return new RecommendationResult(packageRecommendation, action, status);
    }

    static String formatEvidenceGates(EvidenceStatus status) {
        return "Evidence gates: market-direction=" + status.marketDirectionAvailable()
            + " posture=" + status.postureAvailable()
            + " future-capital=" + status.futureCapitalAvailable()
            + " positional-pressure=" + status.positionalPressureAvailable();
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

    static void print(TradeAssetPositionalContextAnalyzer.TradePositionalContextReport report, Options options) {
        var result = recommend(report, options.perspective());
        var trade = report.strategic().trade();
        var perspectiveTeam = options.perspective() == TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM
            ? report.strategic().sideA().identity() : report.strategic().sideB().identity();
        System.out.println("Trade recommendation (conservative evidence-first)");
        System.out.println("League ID: " + trade.leagueId());
        System.out.println("Season: " + options.season());
        System.out.println("Perspective: " + perspectiveTeam.teamName() + " [" + perspectiveTeam.teamId() + "]");
        System.out.println("Recommendation policy: " + TradeRecommendationPolicy.POLICY_ID);
        System.out.println("Perspective policy: " + TradeTeamPerspectiveRecommendationPolicy.POLICY_ID);
        System.out.println("Evidence complete: " + result.evidenceStatus().complete());
        System.out.println(formatEvidenceGates(result.evidenceStatus()));
        System.out.println("Package recommendation: " + result.packageRecommendation());
        System.out.println("Action: " + result.action());
        if (result.action() == TradeTeamPerspectiveRecommendationPolicy.Action.INCONCLUSIVE) {
            System.out.println("Reason: " + formatInconclusiveReason(result.evidenceStatus()) + ".");
        } else if (result.action() == TradeTeamPerspectiveRecommendationPolicy.Action.HOLD) {
            System.out.println("Reason: the governed market comparison is inside the fairness band.");
        }
        System.out.println("No hidden weighting or strategic override is applied.");
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

    record EvidenceStatus(boolean marketDirectionAvailable, boolean postureAvailable,
                          boolean futureCapitalAvailable, boolean positionalPressureAvailable) {
        boolean complete() {
            return marketDirectionAvailable && postureAvailable && futureCapitalAvailable && positionalPressureAvailable;
        }
    }

    record RecommendationResult(TradeRecommendationPolicy.Recommendation packageRecommendation,
                                TradeTeamPerspectiveRecommendationPolicy.Action action,
                                EvidenceStatus evidenceStatus) {}

    record Options(String leagueId, int season, TradeAssetAnalyzer.TradePackage sideA,
                   TradeAssetAnalyzer.TradePackage sideB,
                   TradeTeamPerspectiveRecommendationPolicy.Perspective perspective,
                   String source, LocalDate minimumAsOf) {}
}
