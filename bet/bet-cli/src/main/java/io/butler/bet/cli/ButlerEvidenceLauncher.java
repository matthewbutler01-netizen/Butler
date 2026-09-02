package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueEvidenceOverviewAnalyzer;
import io.butler.bet.intelligence.LeagueProductionContextAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Adds focused league evidence/production commands while delegating established commands unchanged. */
public final class ButlerEvidenceLauncher {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerEvidenceLauncher() {}

    public static void main(String[] args) {
        if (isProductionContextCommand(args)) {
            if (!isSupportedProductionContext(args)) {
                printProductionContextUsage();
                return;
            }
            try {
                printProductionContext(analyzeProductionContext(args));
            } catch (SQLException e) {
                System.err.println("Database error while building league production context: " + e.getMessage());
                System.exit(1);
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(2);
            }
            return;
        }

        if (!isEvidenceOverviewCommand(args)) {
            ButlerLauncher.main(args);
            return;
        }

        EvidenceOverviewOptions options;
        try {
            options = parseEvidenceOverview(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printEvidenceOverviewUsage();
            return;
        }

        try {
            ButlerLauncher.printEvidenceOverview(analyze(options));
        } catch (SQLException e) {
            System.err.println("Database error while building league evidence overview: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static boolean isProductionContextCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("league")
            && args[1].equalsIgnoreCase("production-context");
    }

    static boolean isSupportedProductionContext(String[] args) {
        return isProductionContextCommand(args) && (args.length == 3 || args.length == 4);
    }

    private static LeagueProductionContextAnalyzer.ProductionContextReport analyzeProductionContext(String[] args)
        throws SQLException {
        Database database = initializedDatabase();
        LeagueProductionContextAnalyzer analyzer = new LeagueProductionContextAnalyzer(database);
        return args.length == 3 ? analyzer.analyze(args[2]) : analyzer.analyze(args[2], parseSeason(args[3]));
    }

    static void printProductionContext(LeagueProductionContextAnalyzer.ProductionContextReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("League production context");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Season: " + report.season());
        System.out.println("Source: " + report.source());
        System.out.printf("Coverage: %d/%d (%.1f%%)%n",
            report.coveredPlayers(), report.totalPlayers(), report.coveragePercent());
        for (var team : report.teams()) {
            System.out.printf("%s  coverage=%d/%d (%.1f%%)  as-of=%s..%s  [%s]%n",
                team.teamName(), team.coveredPlayers(), team.totalPlayers(), team.coveragePercent(),
                team.earliestAsOf() == null ? "-" : team.earliestAsOf(),
                team.latestAsOf() == null ? "-" : team.latestAsOf(), team.teamId());
            for (var position : team.positions().values()) {
                System.out.printf("  %s  coverage=%d/%d (%.1f%%)  player-games=%d  pass=%d/%d INT=%d  rush=%d/%d  rec=%d-%d/%d  FL=%d%n",
                    position.position(), position.coveredPlayers(), position.totalPlayers(), position.coveragePercent(),
                    position.playerGames(), position.passingYards(), position.passingTouchdowns(), position.interceptions(),
                    position.rushingYards(), position.rushingTouchdowns(), position.receptions(),
                    position.receivingYards(), position.receivingTouchdowns(), position.fumblesLost());
            }
            if (!team.missingPlayers().isEmpty()) {
                System.out.println("  Missing production:");
                team.missingPlayers().forEach(player -> System.out.printf(
                    "    %s  %s  [%s]%n", player.playerName(), player.position(), player.playerId()));
            }
        }
    }

    static void printProductionContextUsage() {
        System.out.println("  butler league production-context <league-id> [season]");
    }

    static boolean isEvidenceOverviewCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("league")
            && args[1].equalsIgnoreCase("evidence-overview");
    }

    static EvidenceOverviewOptions parseEvidenceOverview(String[] args) {
        if (!isEvidenceOverviewCommand(args) || args.length < 3) {
            throw new IllegalArgumentException("league evidence-overview requires a league id");
        }

        String leagueId = requireText(args[2], "league-id");
        Integer season = null;
        LocalDate minimumValueAsOf = null;
        LocalDate minimumProfileAsOf = null;
        int index = 3;

        if (index < args.length && !args[index].startsWith("--")) {
            season = parseSeason(args[index]);
            index++;
        }

        while (index < args.length) {
            String flag = args[index++];
            if (index >= args.length) throw new IllegalArgumentException("missing date after " + flag);
            LocalDate date = parseDate(args[index++], flag);
            if (flag.equalsIgnoreCase("--minimum-value-as-of")) {
                if (minimumValueAsOf != null) throw new IllegalArgumentException("duplicate --minimum-value-as-of");
                minimumValueAsOf = date;
            } else if (flag.equalsIgnoreCase("--minimum-profile-as-of")) {
                if (minimumProfileAsOf != null) throw new IllegalArgumentException("duplicate --minimum-profile-as-of");
                minimumProfileAsOf = date;
            } else {
                throw new IllegalArgumentException("unsupported evidence-overview option: " + flag);
            }
        }

        return new EvidenceOverviewOptions(leagueId, season, minimumValueAsOf, minimumProfileAsOf);
    }

    private static LeagueEvidenceOverviewAnalyzer.EvidenceOverviewReport analyze(EvidenceOverviewOptions options)
        throws SQLException {
        Database database = initializedDatabase();
        LeagueEvidenceOverviewAnalyzer analyzer = new LeagueEvidenceOverviewAnalyzer(database);
        if (options.season() == null) {
            if (options.minimumValueAsOf() == null && options.minimumProfileAsOf() == null) {
                return analyzer.analyze(options.leagueId());
            }
            return analyzer.analyze(options.leagueId(), options.minimumValueAsOf(), options.minimumProfileAsOf());
        }
        if (options.minimumValueAsOf() == null && options.minimumProfileAsOf() == null) {
            return analyzer.analyze(options.leagueId(), options.season());
        }
        return analyzer.analyze(options.leagueId(), options.season(),
            options.minimumValueAsOf(), options.minimumProfileAsOf());
    }

    static void printEvidenceOverviewUsage() {
        System.out.println("  butler league evidence-overview <league-id> [season] [--minimum-value-as-of YYYY-MM-DD] [--minimum-profile-as-of YYYY-MM-DD]");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
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

    private static LocalDate parseDate(String value, String flag) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(flag + " must use YYYY-MM-DD: " + value);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    record EvidenceOverviewOptions(String leagueId, Integer season,
                                   LocalDate minimumValueAsOf, LocalDate minimumProfileAsOf) {}
}
