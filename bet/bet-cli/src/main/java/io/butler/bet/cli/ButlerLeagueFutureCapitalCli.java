package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueFutureCapitalTierAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Read-only CLI for governed league-relative future draft-capital tiers. */
public final class ButlerLeagueFutureCapitalCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeagueFutureCapitalCli() {}

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
            LeagueFutureCapitalTierAnalyzer analyzer = new LeagueFutureCapitalTierAnalyzer(initializedDatabase());
            var report = options.minimumAsOfDate() == null
                ? (options.source() == null ? analyzer.analyze(options.leagueId()) : analyzer.analyze(options.leagueId(), options.source()))
                : (options.source() == null ? analyzer.analyze(options.leagueId(), options.minimumAsOfDate())
                    : analyzer.analyze(options.leagueId(), options.source(), options.minimumAsOfDate()));
            print(report);
        } catch (SQLException e) {
            System.err.println("Database error while building future-capital tiers: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args)) throw new IllegalArgumentException("expected league future-capital command");
        if (args.length == 3) return new Options(requireText(args[2], "league-id"), null, null);
        if (args.length == 4) return new Options(requireText(args[2], "league-id"), requireText(args[3], "source"), null);
        if (args.length == 5 && "--minimum-as-of".equalsIgnoreCase(args[3])) {
            return new Options(requireText(args[2], "league-id"), null, parseDate(args[4]));
        }
        if (args.length == 6 && "--minimum-as-of".equalsIgnoreCase(args[4])) {
            return new Options(requireText(args[2], "league-id"), requireText(args[3], "source"), parseDate(args[5]));
        }
        throw new IllegalArgumentException("invalid future-capital arguments");
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && "future-capital".equalsIgnoreCase(args[1]);
    }

    static void print(LeagueFutureCapitalTierAnalyzer.FutureCapitalReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("League future draft-capital tiers");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Value source: " + report.source());
        if (report.minimumAsOfDate() != null) System.out.println("Minimum as-of: " + report.minimumAsOfDate());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Available: " + report.available());
        if (!report.available()) System.out.println("Reason: " + report.insufficiencyReason());
        System.out.println("Future-capital tier is a separate future-flexibility dimension; it does not change current roster strength, team posture, or create a trade recommendation.");

        for (var team : report.teams()) {
            System.out.printf("%s  tier=%s  value=%.2f  coverage=%d/%d (%.1f%%)  stale=%d missing=%d  [%s]%n",
                team.teamName(), team.tier(), team.value(), team.valuedPicks(), team.totalPicks(),
                team.coveragePercent(), team.stalePicks(), team.missingPicks(), team.teamId());
            for (var season : team.seasons()) {
                System.out.printf("  %d: value=%.2f coverage=%d/%d (%.1f%%) stale=%d missing=%d rounds=%s%n",
                    season.season(), season.value(), season.valuedPicks(), season.totalPicks(), season.coveragePercent(),
                    season.stalePicks(), season.missingPicks(), season.roundCounts());
            }
        }
    }

    static void printUsage() {
        System.out.println("  butler league future-capital <league-id> [source] [--minimum-as-of YYYY-MM-DD]");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("minimum-as-of must use YYYY-MM-DD: " + value);
        }
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    record Options(String leagueId, String source, LocalDate minimumAsOfDate) {}
}
