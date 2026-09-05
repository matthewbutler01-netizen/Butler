package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeaguePlayerSeasonScoringAnalyzer;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;

/** Read-only inspection of one persisted player-season scored under exact league rules. */
public final class ButlerLeaguePlayerScoreCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeaguePlayerScoreCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            var report = new LeaguePlayerSeasonScoringAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.playerId(), options.season(), options.source());
            print(report);
        } catch (SQLException e) {
            System.err.println("Database error while scoring player-season production: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 6) {
            throw new IllegalArgumentException(
                "Usage: butler league player-score <league-id> <player-id> <season> <source>");
        }
        return new Options(
            requireText(args[2], "league-id"),
            requireText(args[3], "player-id"),
            parseSeason(args[4]),
            requireText(args[5], "source"));
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && "player-score".equalsIgnoreCase(args[1]);
    }

    static void print(LeaguePlayerSeasonScoringAnalyzer.ScoringReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("League player-season score");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Coverage policy: " + report.coveragePolicyId());
        System.out.println("Scoring policy: " + report.scoringPolicyId());
        System.out.println("League: " + report.leagueName() + " (" + report.leagueId() + ")");
        System.out.println("Player ID: " + report.playerId());
        System.out.println("Season: " + report.season());
        System.out.println("Source: " + report.source());
        System.out.println("Production snapshot: " + report.productionId() + " as-of=" + report.productionAsOf());
        System.out.println("Total fantasy points: " + format(report.score().totalPoints()));
        System.out.println("Stat | Raw value | Points per unit | Contribution");
        for (var component : report.score().components()) {
            System.out.println(component.statKey() + " | " + component.rawValue() + " | "
                + format(component.pointsPerUnit()) + " | " + format(component.contribution()));
        }
        System.out.println("Exact persisted evidence only; this command does not rank players or make recommendations.");
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

    private static String format(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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

    record Options(String leagueId, String playerId, int season, String source) {}
}
