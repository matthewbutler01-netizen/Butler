package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueRosterStrengthTierAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Read-only CLI for governed league-relative current-roster strength tiers. */
public final class ButlerLeagueRosterStrengthCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeagueRosterStrengthCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            LeagueRosterStrengthTierAnalyzer analyzer = new LeagueRosterStrengthTierAnalyzer(initializedDatabase());
            var report = options.minimumAsOf() == null
                ? (options.source() == null ? analyzer.analyze(options.leagueId()) : analyzer.analyze(options.leagueId(), options.source()))
                : (options.source() == null ? analyzer.analyze(options.leagueId(), options.minimumAsOf())
                    : analyzer.analyze(options.leagueId(), options.source(), options.minimumAsOf()));
            print(report);
        } catch (SQLException e) {
            System.err.println("Database error while building roster strength tiers: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length < 3 || args.length > 6) {
            throw new IllegalArgumentException("league roster-strength requires league id with optional source/minimum-as-of");
        }
        if (args.length == 3) return new Options(requireText(args[2], "league-id"), null, null);
        if (args.length == 4) return new Options(requireText(args[2], "league-id"), requireText(args[3], "source"), null);
        if (args.length == 5 && "--minimum-as-of".equalsIgnoreCase(args[3])) {
            return new Options(requireText(args[2], "league-id"), null, parseDate(args[4]));
        }
        if (args.length == 6 && "--minimum-as-of".equalsIgnoreCase(args[4])) {
            return new Options(requireText(args[2], "league-id"), requireText(args[3], "source"), parseDate(args[5]));
        }
        throw new IllegalArgumentException("invalid roster-strength arguments");
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0]) && "roster-strength".equalsIgnoreCase(args[1]);
    }

    static void print(LeagueRosterStrengthTierAnalyzer.RosterStrengthReport report) {
        System.out.println("League roster strength tiers");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Source: " + report.source());
        if (report.minimumAsOfDate() != null) System.out.println("Minimum as-of: " + report.minimumAsOfDate());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Available: " + report.available());
        if (!report.available()) System.out.println("Reason: " + report.insufficiencyReason());
        System.out.println("Ranking: starter market value, then total usable player market value. Draft capital is excluded; positional depth is descriptive only.");
        System.out.println("Roster-strength tier is not contender/rebuilder posture and creates no recommendation.");
        for (var team : report.teams()) {
            System.out.printf("%s [%s]: tier=%s starter-value=%.2f total-player-value=%.2f coverage=%d/%d (%.1f%%) stale=%d missing=%d%n",
                team.teamName(), team.teamId(), team.tier(), team.starterValue(), team.totalPlayerValue(),
                team.valuedPlayers(), team.totalPlayers(), team.coveragePercent(), team.stalePlayers(), team.missingPlayers());
        }
    }

    private static LocalDate parseDate(String value) {
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException("minimum-as-of must use YYYY-MM-DD: " + value); }
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

    record Options(String leagueId, String source, LocalDate minimumAsOf) {}
}
