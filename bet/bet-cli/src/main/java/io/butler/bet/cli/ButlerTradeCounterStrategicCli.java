package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterStrategicCandidateVettingAnalyzer;
import io.butler.bet.intelligence.TradeCounterStrategicEligibilityPolicy;
import io.butler.bet.intelligence.TradeRecommendationVetoPolicy;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Read-only season-aware strategic vetting for governed single-asset counter candidates. */
public final class ButlerTradeCounterStrategicCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerTradeCounterStrategicCli() {}

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
            var analyzer = new TradeCounterStrategicCandidateVettingAnalyzer(initializedDatabase());
            var report = analyze(analyzer, options);
            print(report, TradeCounterStrategicEligibilityPolicy.classify(report));
        } catch (SQLException e) {
            System.err.println("Database error while building strategic counter candidate evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length < 6 || args.length > 9) {
            throw new IllegalArgumentException(
                "trade counter-strategic requires league id, season, and two asset packages");
        }
        String leagueId = requireText(args[2], "league-id");
        int season = parseSeason(args[3]);
        var sideA = ButlerApp.parseTradePackage(args[4], "side-a-assets");
        var sideB = ButlerApp.parseTradePackage(args[5], "side-b-assets");
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
            throw new IllegalArgumentException("invalid trade counter-strategic optional arguments");
        }
        return new Options(leagueId, season, sideA, sideB, source, minimumAsOf);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "trade".equalsIgnoreCase(args[0])
            && "counter-strategic".equalsIgnoreCase(args[1]);
    }

    /** Retained BF-372 renderer for compatibility. */
    static void print(TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidateReport report) {
        printStrategicVetting(report);
    }

    static void print(
        TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidateReport report,
        TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility) {
        printStrategicVetting(report);
        printEligibility(report, eligibility);
    }

    private static void printStrategicVetting(
        TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidateReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("Trade counter strategic vetting (season-aware bilateral v5 veto)");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Season: " + report.season());
        System.out.println("Value source: " + report.source());
        if (report.minimumAsOfDate() != null) {
            System.out.println("Minimum as-of: " + report.minimumAsOfDate());
        }
        System.out.println("Strategic counter policy: " + report.policyId());
        System.out.println("Market candidate policy: " + report.marketCandidatePolicyId());
        System.out.println("Strategic veto policy: " + report.strategicVetoPolicyId());
        System.out.println("Strategic candidate evidence available: " + report.available());
        if (!report.available()) {
            System.out.println("Strategic candidate reason: " + report.insufficiencyReason());
            System.out.println("No candidate is strategically labeled and no COUNTER action is emitted.");
            return;
        }

        System.out.println("Strategically vetted market-fair candidates: " + report.candidates().size());
        for (var vetted : report.candidates()) {
            var candidate = vetted.candidate();
            System.out.printf(Locale.ROOT,
                "#%d %s %s %s %s [%s] value=%.2f market-gap=%.3f%% strategic=%s%n",
                vetted.marketRank(), candidate.adjustmentType(), candidate.side(), candidate.assetType(),
                candidate.displayName(), candidate.assetId(), candidate.assetValue(),
                candidate.resultingGapPercent(), vetted.state());
            printSide(vetted.sideA());
            printSide(vetted.sideB());
        }
        System.out.println("Market rank is preserved as evidence ordering; strategic vetting only annotates candidates.");
        System.out.println("No candidate is selected and no COUNTER action or recommendation is emitted.");
    }

    private static void printEligibility(
        TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidateReport report,
        TradeCounterStrategicEligibilityPolicy.EligibilityReport eligibility) {
        if (eligibility == null) throw new IllegalArgumentException("eligibility must not be null");
        if (!report.leagueId().equals(eligibility.leagueId())
            || report.season() != eligibility.season()
            || !report.source().equals(eligibility.source())
            || !java.util.Objects.equals(report.minimumAsOfDate(), eligibility.minimumAsOfDate())
            || !report.policyId().equals(eligibility.strategicVettingPolicyId())) {
            throw new IllegalStateException("strategic vetting and eligibility coordinates differ");
        }
        System.out.println("Strategic eligibility policy: " + eligibility.policyId());
        System.out.println("Strategic eligibility available: " + eligibility.available());
        if (!eligibility.available()) {
            System.out.println("Strategic eligibility reason: " + eligibility.insufficiencyReason());
            return;
        }
        System.out.println("Strategically eligible candidates: " + eligibility.eligibleCandidates().size());
        for (var eligible : eligibility.eligibleCandidates()) {
            var candidate = eligible.candidate();
            System.out.printf(Locale.ROOT,
                "ELIGIBLE #%d %s %s [%s] value=%.2f%n",
                eligible.marketRank(), candidate.adjustmentType(), candidate.displayName(),
                candidate.assetId(), candidate.assetValue());
        }
        System.out.println("Strategically blocked candidates excluded: " + eligibility.blockedCandidates().size());
        System.out.println("Eligibility preserves market rank and does not select or re-rank a candidate.");
    }

    private static void printSide(TradeCounterStrategicCandidateVettingAnalyzer.SideVetting side) {
        System.out.println("  " + side.side() + " " + side.teamName() + " [" + side.teamId() + "] veto=" + side.vetoState());
        if (side.vetoState() == TradeRecommendationVetoPolicy.VetoState.BLOCKED) {
            for (var reason : side.reasons()) {
                System.out.println("    Veto reason: " + ButlerTradeRecommendationV5Cli.formatVetoReason(reason));
            }
        }
    }

    static void printUsage() {
        System.out.println("  butler trade counter-strategic <league-id> <season> <side-a-assets> <side-b-assets> [source] [--minimum-as-of YYYY-MM-DD]");
        System.out.println("  Assets are comma-separated. Bare IDs are players; use player:<id> or pick:<draft-pick-id>.");
        System.out.println("  This command season-vets and filters ranked market-fair candidates; it does not select or emit COUNTER.");
    }

    private static TradeCounterStrategicCandidateVettingAnalyzer.StrategicCandidateReport analyze(
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
        String source,
        LocalDate minimumAsOf) {}
}
