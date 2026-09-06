package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueCompetitiveTierAnalyzer;
import io.butler.bet.intelligence.LeaguePerformanceEvidenceAnalyzer;
import io.butler.bet.sleeper.SleeperHistoricalTeamPerformanceImporter;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** Read-only evidence by default, with an explicit Sleeper historical-sync flag. */
public final class ButlerLeaguePerformanceEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String SYNC_SLEEPER = "--sync-sleeper";

    private ButlerLeaguePerformanceEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = initializedDatabase();
            if (options.syncSleeper()) {
                var sync = new SleeperHistoricalTeamPerformanceImporter(database)
                    .syncSeason(options.leagueId(), options.season());
                printSync(sync);
            }
            var analyzer = new LeaguePerformanceEvidenceAnalyzer(database);
            var report = options.source() == null
                ? analyzer.analyze(options.leagueId(), options.season())
                : analyzer.analyze(options.leagueId(), options.season(), options.source());
            print(report);
        } catch (SQLException e) {
            System.err.println("Database error while building league performance evidence: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Sleeper historical performance sync error: " + e.getMessage());
            System.exit(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Sleeper historical performance sync interrupted.");
            System.exit(4);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || (args.length != 4 && args.length != 5)) {
            throw new IllegalArgumentException(
                "league performance-evidence requires league id and season, with optional source or --sync-sleeper");
        }
        String source = null;
        boolean syncSleeper = false;
        if (args.length == 5) {
            if (SYNC_SLEEPER.equalsIgnoreCase(args[4])) syncSleeper = true;
            else source = requireText(args[4], "source");
        }
        return new Options(requireText(args[2], "league-id"), parseSeason(args[3]), source, syncSleeper);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && "performance-evidence".equalsIgnoreCase(args[1]);
    }

    private static void printSync(SleeperHistoricalTeamPerformanceImporter.ImportResult result) {
        System.out.println("Sleeper historical team performance synchronized.");
        System.out.println("Resolved Sleeper league: " + result.sleeperLeagueId());
        System.out.println("History hops: " + result.historyHops());
        System.out.println("Performance snapshots: " + result.teamsImported()
            + "  season=" + result.season() + "  source=" + result.source() + "  as-of=" + result.asOfDate());
        System.out.println();
    }

    static void print(LeaguePerformanceEvidenceAnalyzer.PerformanceReport report) {
        var tiers = new LeagueCompetitiveTierAnalyzer().analyze(report);
        System.out.println("League performance evidence");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Season: " + report.season());
        System.out.println("Source: " + report.source());
        System.out.printf("Coverage: %d/%d (%.1f%%) complete=%s%n",
            report.coveredTeams(), report.teams().size(), report.coveragePercent(), report.complete());
        System.out.println("Competitive-tier policy: " + tiers.policyId());
        System.out.println("Competitive tiers available: " + tiers.available());
        if (!tiers.available()) System.out.println("Competitive-tier reason: " + tiers.unavailableReason());
        System.out.println("Competitive tiers are league-relative descriptive context only; no contender/rebuilder posture or recommendation is inferred.");
        for (var team : report.teams()) {
            var tier = tiers.teams().stream().filter(item -> item.teamId().equals(team.teamId())).findFirst().orElseThrow();
            if (!team.available()) {
                System.out.println(team.teamName() + " [" + team.teamId() + "]: unavailable tier=" + tier.tier());
                continue;
            }
            var p = team.performance();
            System.out.printf("%s [%s]: %d-%d-%d games=%d win%%=%.3f PF=%.2f PA=%.2f diff=%.2f PF/game=%.2f diff/game=%.2f tier=%s as-of=%s%n",
                team.teamName(), team.teamId(), p.wins(), p.losses(), p.ties(), p.gamesPlayed(),
                p.winPercentage(), p.pointsFor(), p.pointsAgainst(), p.pointDifferential(),
                tier.pointsForPerGame(), tier.pointDifferentialPerGame(), tier.tier(), p.asOfDate());
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

    record Options(String leagueId, int season, String source, boolean syncSleeper) {}
}
