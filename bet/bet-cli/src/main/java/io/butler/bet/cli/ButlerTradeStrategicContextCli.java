package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeAssetStrategicContextAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Read-only mixed player/draft-pick strategic trade context. */
public final class ButlerTradeStrategicContextCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeStrategicContextCli() {}

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
            var analyzer = new TradeAssetStrategicContextAnalyzer(initializedDatabase());
            var report = analyze(analyzer, options);
            print(report, options.season());
        } catch (SQLException e) {
            System.err.println("Database error while building trade strategic context: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length < 6 || args.length > 9) {
            throw new IllegalArgumentException("trade strategic-context requires league, season, and two asset packages");
        }
        String leagueId = requireText(args[2], "league-id");
        int season = parseSeason(args[3]);
        TradeAssetAnalyzer.TradePackage sideA = ButlerApp.parseTradePackage(args[4], "side-a-assets");
        TradeAssetAnalyzer.TradePackage sideB = ButlerApp.parseTradePackage(args[5], "side-b-assets");
        String source = null;
        LocalDate minimumAsOf = null;
        if (args.length == 7) {
            if ("--minimum-as-of".equalsIgnoreCase(args[6])) {
                throw new IllegalArgumentException("--minimum-as-of requires a YYYY-MM-DD value");
            }
            source = requireText(args[6], "source");
        } else if (args.length == 8 && "--minimum-as-of".equalsIgnoreCase(args[6])) {
            minimumAsOf = parseDate(args[7]);
        } else if (args.length == 9 && "--minimum-as-of".equalsIgnoreCase(args[7])) {
            source = requireText(args[6], "source");
            minimumAsOf = parseDate(args[8]);
        } else if (args.length > 6) {
            throw new IllegalArgumentException("invalid strategic-context optional arguments");
        }
        return new Options(leagueId, season, sideA, sideB, source, minimumAsOf);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "trade".equalsIgnoreCase(args[0])
            && "strategic-context".equalsIgnoreCase(args[1]);
    }

    private static TradeAssetStrategicContextAnalyzer.StrategicTradeReport analyze(
        TradeAssetStrategicContextAnalyzer analyzer, Options options) throws SQLException {
        if (options.minimumAsOf() != null) {
            return options.source() == null
                ? analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.minimumAsOf())
                : analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.source(), options.minimumAsOf());
        }
        return options.source() == null
            ? analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB())
            : analyzer.analyze(options.leagueId(), options.season(), options.sideA(), options.sideB(), options.source());
    }

    static void print(TradeAssetStrategicContextAnalyzer.StrategicTradeReport report, int season) {
        var trade = report.trade();
        System.out.println("Trade strategic context (players + draft picks)");
        System.out.println("League ID: " + trade.leagueId());
        System.out.println("Season: " + season);
        System.out.println("Value source: " + trade.source());
        if (trade.minimumAsOfDate() != null) System.out.println("Minimum as-of: " + trade.minimumAsOfDate());
        System.out.printf("Market coverage: %d/%d (%.1f%%) stale=%d comparable=%s%n",
            trade.valuedAssets(), trade.totalAssets(), trade.coveragePercent(), trade.staleAssets(), trade.comparable());
        System.out.println("Fairness measurement policy: " + report.fairnessMeasurementPolicyId());
        System.out.println("Fairness policy: " + report.fairnessPolicyId());
        System.out.println("Symmetric market gap: " + (report.fairnessGapPercent() == null ? "unavailable" : String.format("%.3f%%", report.fairnessGapPercent())));
        System.out.println("Market fairness: " + report.fairnessClassification());
        System.out.println("Market-edge policy: " + report.marketEdgePolicyId());
        System.out.println("Market edge: " + report.marketEdge());
        System.out.println("Posture policy: " + report.posturePolicyId() + " available=" + report.postureAvailable());
        System.out.println("Future-capital policy: " + report.futureCapitalPolicyId() + " available=" + report.futureCapitalAvailable());
        System.out.println("These are independent descriptive dimensions; no winner or accept/reject/counter recommendation is produced.");
        printSide("A", trade.sideA(), report.sideA());
        printSide("B", trade.sideB(), report.sideB());
    }

    private static void printSide(String label, TradeAssetAnalyzer.TradeSide side,
                                  TradeAssetStrategicContextAnalyzer.TeamStrategicContext context) {
        System.out.printf("Side %s: %s [%s] value=%.2f coverage=%d/%d (%.1f%%) stale=%d%n",
            label, context.identity().teamName(), context.identity().teamId(), side.totalValue(),
            side.valuedAssets(), side.totalAssets(), side.coveragePercent(), side.staleAssets());
        System.out.printf("  posture=%s competitive-tier=%s roster-tier=%s%n",
            context.posture().posture(), context.posture().competitiveTier(), context.posture().rosterTier());
        System.out.printf("  future-capital=%s value=%.2f coverage=%d/%d (%.1f%%)%n",
            context.futureCapital().tier(), context.futureCapital().value(), context.futureCapital().valuedPicks(),
            context.futureCapital().totalPicks(), context.futureCapital().coveragePercent());
        for (var player : side.players()) {
            System.out.printf("  PLAYER %s %s [%s] value=%s as-of=%s%s%n",
                player.position(), player.playerName(), player.playerId(),
                player.valued() ? player.value() : "unavailable",
                player.asOfDate() == null ? "-" : player.asOfDate(), player.stale() ? " STALE" : "");
        }
        for (var pick : side.draftPicks()) {
            System.out.printf("  PICK %s [%s] value=%s as-of=%s%s%n",
                pick.label(), pick.draftPickId(), pick.valued() ? pick.value() : "unavailable",
                pick.asOfDate() == null ? "-" : pick.asOfDate(), pick.stale() ? " STALE" : "");
        }
    }

    static void printUsage() {
        System.out.println("  butler trade strategic-context <league-id> <season> <side-a-assets> <side-b-assets> [source] [--minimum-as-of YYYY-MM-DD]");
        System.out.println("  Assets are comma-separated. Bare IDs are players; use player:<id> or pick:<draft-pick-id>.");
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

    record Options(String leagueId, int season, TradeAssetAnalyzer.TradePackage sideA,
                   TradeAssetAnalyzer.TradePackage sideB, String source, LocalDate minimumAsOf) {}
}
