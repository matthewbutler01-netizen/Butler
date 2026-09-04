package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterValueContextAnalyzer;
import io.butler.bet.intelligence.TradeCounterValueTargetAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Read-only CLI for governed, asset-neutral trade counter-value targets. */
public final class ButlerTradeCounterValueCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeCounterValueCli() {}

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
            var analyzer = new TradeAssetAnalyzer(initializedDatabase());
            var trade = analyze(analyzer, options);
            var context = TradeCounterValueContextAnalyzer.compose(trade);
            print(trade, context);
        } catch (SQLException e) {
            System.err.println("Database error while building trade counter-value evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length < 5 || args.length > 8) {
            throw new IllegalArgumentException(
                "trade counter-value requires league id and two asset packages");
        }
        String leagueId = requireText(args[2], "league-id");
        var sideA = ButlerApp.parseTradePackage(args[3], "side-a-assets");
        var sideB = ButlerApp.parseTradePackage(args[4], "side-b-assets");
        String source = null;
        LocalDate minimumAsOf = null;

        if (args.length == 6) {
            if ("--minimum-as-of".equalsIgnoreCase(args[5])) {
                throw new IllegalArgumentException("--minimum-as-of requires a YYYY-MM-DD value");
            }
            source = requireText(args[5], "source");
        } else if (args.length == 7 && "--minimum-as-of".equalsIgnoreCase(args[5])) {
            minimumAsOf = parseDate(args[6]);
        } else if (args.length == 8 && "--minimum-as-of".equalsIgnoreCase(args[6])) {
            source = requireText(args[5], "source");
            minimumAsOf = parseDate(args[7]);
        } else if (args.length > 5) {
            throw new IllegalArgumentException("invalid trade counter-value optional arguments");
        }
        return new Options(leagueId, sideA, sideB, source, minimumAsOf);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "trade".equalsIgnoreCase(args[0])
            && "counter-value".equalsIgnoreCase(args[1]);
    }

    static void print(
        TradeAssetAnalyzer.TradeReport trade,
        TradeCounterValueContextAnalyzer.CounterValueContextReport context) {
        if (trade == null) throw new IllegalArgumentException("trade must not be null");
        if (context == null) throw new IllegalArgumentException("context must not be null");
        if (!trade.leagueId().equals(context.leagueId()) || !trade.source().equals(context.source())
            || !java.util.Objects.equals(trade.minimumAsOfDate(), context.minimumAsOfDate())) {
            throw new IllegalStateException("trade and counter-value context coordinates differ");
        }

        System.out.println("Trade counter-value evidence (asset-neutral market target)");
        System.out.println("League ID: " + context.leagueId());
        System.out.println("Value source: " + context.source());
        if (context.minimumAsOfDate() != null) {
            System.out.println("Minimum as-of: " + context.minimumAsOfDate());
        }
        System.out.println("Counter context policy: " + context.policyId());
        System.out.println("Counter target policy: " + context.targetPolicyId());
        System.out.printf(Locale.ROOT,
            "Market-value coverage: %d/%d (%.1f%%) stale=%d%n",
            trade.valuedAssets(), trade.totalAssets(), trade.coveragePercent(), trade.staleAssets());
        System.out.printf(Locale.ROOT, "Side A package value: %.2f%n", trade.sideA().totalValue());
        System.out.printf(Locale.ROOT, "Side B package value: %.2f%n", trade.sideB().totalValue());
        System.out.println("Counter-value evidence available: " + context.available());

        if (!context.available()) {
            System.out.println("Counter-value reason: " + context.insufficiencyReason());
            System.out.println("No partial or stale package total is used to construct a target.");
            return;
        }

        var target = context.target();
        System.out.println("Fairness measurement policy: " + target.fairnessMeasurementPolicyId());
        System.out.println("Fairness policy: " + target.fairnessPolicyId());
        System.out.printf(Locale.ROOT, "Current symmetric market-value gap: %.3f%%%n", target.currentGapPercent());
        System.out.println("Current market fairness: " + target.currentFairness());

        if (target.options().isEmpty()) {
            System.out.println("Required market-value adjustment: none; the trade is already inside the governed fairness band.");
        } else {
            for (var option : target.options()) {
                String direction = option.type() == TradeCounterValueTargetAnalyzer.AdjustmentType.ADD_TO_LOWER_VALUE_PACKAGE
                    ? "add"
                    : "remove";
                System.out.printf(Locale.ROOT,
                    "%s %s: current=%.2f target=%.2f required-%s=%.2f%n",
                    option.type(), option.side(), option.currentValue(), option.targetValue(),
                    direction, option.requiredValueChange());
            }
        }
        System.out.println("This is market-value evidence only. No asset is selected and no COUNTER action is emitted.");
    }

    static void printUsage() {
        System.out.println("  butler trade counter-value <league-id> <side-a-assets> <side-b-assets> [source] [--minimum-as-of YYYY-MM-DD]");
        System.out.println("  Assets are comma-separated. Bare IDs are players; use player:<id> or pick:<draft-pick-id>.");
        System.out.println("  This command reports asset-neutral value targets only; it does not select counter assets or emit a recommendation.");
    }

    private static TradeAssetAnalyzer.TradeReport analyze(
        TradeAssetAnalyzer analyzer,
        Options options) throws SQLException {
        if (options.minimumAsOf() != null) {
            return options.source() == null
                ? analyzer.analyze(options.leagueId(), options.sideA(), options.sideB(), options.minimumAsOf())
                : analyzer.analyze(options.leagueId(), options.sideA(), options.sideB(), options.source(), options.minimumAsOf());
        }
        return options.source() == null
            ? analyzer.analyze(options.leagueId(), options.sideA(), options.sideB())
            : analyzer.analyze(options.leagueId(), options.sideA(), options.sideB(), options.source());
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
        TradeAssetAnalyzer.TradePackage sideA,
        TradeAssetAnalyzer.TradePackage sideB,
        String source,
        LocalDate minimumAsOf) {}
}
