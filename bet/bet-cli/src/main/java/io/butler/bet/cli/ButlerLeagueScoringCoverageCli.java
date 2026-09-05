package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueScoringCoverageAnalyzer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;

/** Read-only explanation of whether league scoring is exactly representable by stored production. */
public final class ButlerLeagueScoringCoverageCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeagueScoringCoverageCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueScoringCoverageAnalyzer(initializedDatabase()).analyze(options.leagueId()));
        } catch (SQLException e) {
            System.err.println("Database error while analyzing league scoring coverage: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 3) {
            throw new IllegalArgumentException("Usage: butler league scoring-coverage <league-id>");
        }
        return new Options(requireText(args[2], "league-id"));
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && "scoring-coverage".equalsIgnoreCase(args[1]);
    }

    static void print(LeagueScoringCoverageAnalyzer.CoverageReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("League scoring coverage");
        System.out.println("Policy: " + report.policyId());
        System.out.println("League: " + report.leagueName() + " (" + report.leagueId() + ")");
        System.out.println("Coverage: " + report.state());
        System.out.println("Exact scoring eligible: " + report.exactScoringEligible());
        System.out.printf("Rules: supported-nonzero=%d ignored-zero=%d unsupported-nonzero=%d%n",
            report.supportedNonzeroRules(), report.ignoredZeroRules(), report.unsupportedNonzeroRules());
        System.out.println("Reason: " + report.reason());
        if (!report.rules().isEmpty()) {
            System.out.println("Stat | Points per unit | Coverage | Production field");
            for (var rule : report.rules()) {
                System.out.println(rule.statKey() + " | " + format(rule.pointsPerUnit()) + " | "
                    + rule.state() + " | " + (rule.productionField() == null ? "-" : rule.productionField()));
            }
        }
        System.out.println("Coverage only; this command does not calculate fantasy points or make player recommendations.");
    }

    private static String format(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    record Options(String leagueId) {}
}
