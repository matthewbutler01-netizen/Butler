package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueTeamWeekMatchupEvidenceAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** Read-only CLI for exact provider-paired weekly opponent evidence. */
public final class ButlerLeagueTeamWeekMatchupEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "team-week-matchup-evidence";

    private ButlerLeagueTeamWeekMatchupEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            var analyzer = new LeagueTeamWeekMatchupEvidenceAnalyzer(initializedDatabase());
            var report = options.source() == null
                ? analyzer.analyze(options.leagueId(), options.teamId(), options.season(), options.week())
                : analyzer.analyze(options.leagueId(), options.teamId(), options.season(), options.week(), options.source());
            print(report);
        } catch (SQLException e) {
            System.err.println("Database error while building team-week matchup evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || (args.length != 6 && args.length != 7)) {
            throw usage();
        }
        String leagueId = requireText(args[2], "league-id");
        String teamId = requireText(args[3], "team-id");
        int season = parseInt(args[4], "season");
        int week = parseInt(args[5], "week");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        if (week <= 0) throw new IllegalArgumentException("week must be positive");
        String source = args.length == 7 ? requireText(args[6], "source") : null;
        return new Options(leagueId, teamId, season, week, source);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0]) && COMMAND.equalsIgnoreCase(args[1]);
    }

    static void print(LeagueTeamWeekMatchupEvidenceAnalyzer.MatchupReport report) {
        System.out.println("Team-week matchup evidence");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Season/week: " + report.season() + "/" + report.week());
        System.out.println("Source: " + report.source());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Provider matchup id: " + report.providerMatchupId());
        System.out.println("Your team: " + report.teamName() + " [" + report.teamId()
            + "] roster=" + report.teamExternalId());
        System.out.println("Opponent: " + report.opponentTeamName() + " [" + report.opponentTeamId()
            + "] roster=" + report.opponentExternalId());
        System.out.println("Pairing as-of: " + report.asOfDate());
        System.out.println("State: EXACT_PAIR_VERIFIED");
        System.out.println("Boundary: exact provider matchup identity only; no score projection, win probability, winner prediction, ranking, betting guidance, or transaction write is produced.");
    }

    private static int parseInt(String value, String field) {
        try { return Integer.parseInt(requireText(value, field)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(field + " must be an integer: " + value); }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
            "Usage: butler league team-week-matchup-evidence <league-id> <team-id> <season> <week> [source]");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    record Options(String leagueId, String teamId, int season, int week, String source) {}
}
