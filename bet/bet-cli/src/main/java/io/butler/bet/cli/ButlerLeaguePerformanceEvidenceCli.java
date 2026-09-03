package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeaguePerformanceEvidenceAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** Read-only CLI for observed team-season competitive performance. */
public final class ButlerLeaguePerformanceEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeaguePerformanceEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            var analyzer = new LeaguePerformanceEvidenceAnalyzer(initializedDatabase());
            var report = options.source() == null
                ? analyzer.analyze(options.leagueId(), options.season())
                : analyzer.analyze(options.leagueId(), options.season(), options.source());
            print(report);
        } catch (SQLException e) {
            System.err.println("Database error while building league performance evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || (args.length != 4 && args.length != 5)) {
            throw new IllegalArgumentException("league performance-evidence requires league id and season, with optional source");
        }
        return new Options(requireText(args[2], "league-id"), parseSeason(args[3]),
            args.length == 5 ? requireText(args[4], "source") : null);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && "performance-evidence".equalsIgnoreCase(args[1]);
    }

    static void print(LeaguePerformanceEvidenceAnalyzer.PerformanceReport report) {
        System.out.println("League performance evidence");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Season: " + report.season());
        System.out.println("Source: " + report.source());
        System.out.printf("Coverage: %d/%d (%.1f%%) complete=%s%n",
            report.coveredTeams(), report.teams().size(), report.coveragePercent(), report.complete());
        System.out.println("Observed performance is descriptive only; no contender/rebuilder posture or recommendation is inferred.");
        for (var team : report.teams()) {
            if (!team.available()) {
                System.out.println(team.teamName() + " [" + team.teamId() + "]: unavailable");
                continue;
            }
            var p = team.performance();
            System.out.printf("%s [%s]: %d-%d-%d games=%d win%%=%.3f PF=%.2f PA=%.2f diff=%.2f as-of=%s%n",
                team.teamName(), team.teamId(), p.wins(), p.losses(), p.ties(), p.gamesPlayed(),
                p.winPercentage(), p.pointsFor(), p.pointsAgainst(), p.pointDifferential(), p.asOfDate());
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

    record Options(String leagueId, int season, String source) {}
}
