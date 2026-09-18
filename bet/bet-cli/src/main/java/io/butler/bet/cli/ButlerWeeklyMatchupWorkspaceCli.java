package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.WeeklyMatchupWorkspaceAnalyzer;

import java.nio.file.Path;

/** Read-only CLI exposing one exact persisted weekly matchup for manager presentation. */
public final class ButlerWeeklyMatchupWorkspaceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String SOURCE = "sleeper";

    private ButlerWeeklyMatchupWorkspaceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new WeeklyMatchupWorkspaceAnalyzer(database).analyze(
                options.sleeperLeagueId(),
                options.teamId(),
                options.season(),
                options.week(),
                SOURCE));
        } catch (Exception e) {
            System.err.println("Error: " + safeMessage(e));
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 4) {
            throw new IllegalArgumentException(
                "weeklyMatchupWorkspace requires <sleeper-league-id> <butler-team-id> <season> <week>");
        }
        String sleeperLeagueId = requireText(args[0], "sleeper-league-id");
        String teamId = requireText(args[1], "butler-team-id");
        int season = parsePositive(args[2], "season");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        int week = parsePositive(args[3], "week");
        return new Options(sleeperLeagueId, teamId, season, week);
    }

    static void print(WeeklyMatchupWorkspaceAnalyzer.MatchupReport report) {
        System.out.println("Weekly matchup workspace");
        System.out.println("State: READY");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("League name: " + report.leagueName());
        System.out.println("Season: " + report.season());
        System.out.println("Week: " + report.week());
        System.out.println("Matchup ID: " + report.providerMatchupId());
        System.out.println("User team ID: " + report.userTeamId());
        System.out.println("User team name: " + report.userTeamName());
        System.out.println("Opponent team ID: " + report.opponentTeamId());
        System.out.println("Opponent team name: " + report.opponentTeamName());
        System.out.println("Source: " + report.source());
        System.out.println("As-of: " + report.asOfDate());
        System.out.println(
            "Boundary: exact persisted Sleeper matchup pairing only; no winner prediction or transaction write.");
    }

    private static int parsePositive(String value, String field) {
        try {
            int parsed = Integer.parseInt(requireText(value, field));
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    record Options(String sleeperLeagueId, String teamId, int season, int week) {}
}
