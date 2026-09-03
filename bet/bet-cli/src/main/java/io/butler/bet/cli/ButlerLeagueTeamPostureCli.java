package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueTeamPostureAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** Read-only CLI for governed league team posture. */
public final class ButlerLeagueTeamPostureCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeagueTeamPostureCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            var analyzer = new LeagueTeamPostureAnalyzer(initializedDatabase());
            var report = options.rosterValueSource() == null
                ? analyzer.analyze(options.leagueId(), options.season())
                : analyzer.analyze(options.leagueId(), options.season(), options.rosterValueSource());
            print(report);
        } catch (SQLException e) {
            System.err.println("Database error while building team posture: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || (args.length != 4 && args.length != 5)) {
            throw new IllegalArgumentException("league team-posture requires league id and season, with optional roster value source");
        }
        return new Options(requireText(args[2], "league-id"), parseSeason(args[3]),
            args.length == 5 ? requireText(args[4], "roster-value-source") : null);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && "team-posture".equalsIgnoreCase(args[1]);
    }

    static void print(LeagueTeamPostureAnalyzer.PostureReport report) {
        System.out.println("League team posture");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Season: " + report.season());
        System.out.println("Performance source: " + report.performanceSource());
        System.out.println("Roster value source: " + report.rosterValueSource());
        System.out.println("Posture policy: " + report.posturePolicyId());
        System.out.println("Competitive policy: " + report.competitivePolicyId());
        System.out.println("Roster-strength policy: " + report.rosterPolicyId());
        System.out.println("Available: " + report.available());
        System.out.println("Posture is descriptive strategic context only; it is not an accept/reject trade recommendation.");
        for (var team : report.teams()) {
            System.out.printf("%s [%s]: competitive=%s  roster=%s  posture=%s%n",
                team.teamName(), team.teamId(), team.competitiveTier(), team.rosterTier(), team.posture());
        }
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    record Options(String leagueId, int season, String rosterValueSource) {}
}
