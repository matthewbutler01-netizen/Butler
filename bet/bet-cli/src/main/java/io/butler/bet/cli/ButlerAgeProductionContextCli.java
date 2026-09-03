package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueAgeProductionContextAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** CLI leaf for neutral age-production context. */
public final class ButlerAgeProductionContextCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgeProductionContextCli() {}

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
            print(analyze(options));
        } catch (SQLException e) {
            System.err.println("Database error while building age production context: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("league")
            && args[1].equalsIgnoreCase("age-production-context");
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length < 3) {
            throw new IllegalArgumentException("league age-production-context requires a league id");
        }
        String leagueId = requireText(args[2], "league-id");
        Integer season = null;
        LocalDate ageAsOf = null;
        LocalDate minimumProfileAsOf = null;
        int index = 3;
        if (index < args.length && !args[index].startsWith("--")) {
            season = parseSeason(args[index++]);
        }
        while (index < args.length) {
            String flag = args[index++];
            if (index >= args.length) throw new IllegalArgumentException("missing date after " + flag);
            LocalDate date = parseDate(args[index++], flag);
            if (flag.equalsIgnoreCase("--age-as-of")) {
                if (ageAsOf != null) throw new IllegalArgumentException("duplicate --age-as-of");
                ageAsOf = date;
            } else if (flag.equalsIgnoreCase("--minimum-profile-as-of")) {
                if (minimumProfileAsOf != null) throw new IllegalArgumentException("duplicate --minimum-profile-as-of");
                minimumProfileAsOf = date;
            } else {
                throw new IllegalArgumentException("unsupported age-production-context option: " + flag);
            }
        }
        return new Options(leagueId, season, ageAsOf, minimumProfileAsOf);
    }

    private static LeagueAgeProductionContextAnalyzer.AgeProductionReport analyze(Options options) throws SQLException {
        LeagueAgeProductionContextAnalyzer analyzer = new LeagueAgeProductionContextAnalyzer(initializedDatabase());
        if (options.ageAsOf() == null && options.minimumProfileAsOf() == null) {
            return options.season() == null
                ? analyzer.analyze(options.leagueId())
                : analyzer.analyze(options.leagueId(), options.season());
        }
        LocalDate ageAsOf = options.ageAsOf() == null ? LocalDate.now(ZoneOffset.UTC) : options.ageAsOf();
        return options.season() == null
            ? analyzer.analyze(options.leagueId(), ageAsOf, options.minimumProfileAsOf())
            : analyzer.analyze(options.leagueId(), options.season(), ageAsOf, options.minimumProfileAsOf());
    }

    static void print(LeagueAgeProductionContextAnalyzer.AgeProductionReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("League age production context");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Season: " + report.season());
        System.out.println("Age as-of: " + report.ageAsOf());
        System.out.println("Profile source: " + report.profileSource());
        System.out.println("Production source: " + report.productionSource());
        if (report.minimumProfileAsOf() != null) {
            System.out.println("Minimum profile as-of: " + report.minimumProfileAsOf());
        }
        System.out.printf("Joint age+production coverage: %d/%d (%.1f%%)  rate-covered=%d%n",
            report.jointCoveredPlayers(), report.totalPlayers(), report.jointCoveragePercent(), report.rateCoveredPlayers());
        System.out.println("No aging curve or age-adjusted score is applied.");

        for (var team : report.teams()) {
            System.out.printf("%s  joint=%d/%d (%.1f%%)  age=%d  production=%d  rates=%d  [%s]%n",
                team.teamName(), team.jointCoveredPlayers(), team.players().size(), team.jointCoveragePercent(),
                team.ageCoveredPlayers(), team.productionCoveredPlayers(), team.rateCoveredPlayers(), team.teamId());
            for (var player : team.players()) {
                System.out.printf("  %s  %s  age=%s (%s)  games=%d  passY/g=%s passTD/g=%s INT/g=%s  rushY/g=%s rushTD/g=%s  rec/g=%s recY/g=%s recTD/g=%s FL/g=%s  [%s]%n",
                    player.playerName(), player.position(), format(player.age()), player.ageProvenance(), player.gamesPlayed(),
                    format(player.passingYardsPerGame()), format(player.passingTouchdownsPerGame()), format(player.interceptionsPerGame()),
                    format(player.rushingYardsPerGame()), format(player.rushingTouchdownsPerGame()),
                    format(player.receptionsPerGame()), format(player.receivingYardsPerGame()),
                    format(player.receivingTouchdownsPerGame()), format(player.fumblesLostPerGame()), player.playerId());
            }
        }
    }

    static void printUsage() {
        System.out.println("  butler league age-production-context <league-id> [season] [--age-as-of YYYY-MM-DD] [--minimum-profile-as-of YYYY-MM-DD]");
    }

    private static String format(Number value) {
        return value == null ? "-" : String.format("%.2f", value.doubleValue());
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

    record Options(String leagueId, Integer season, LocalDate ageAsOf, LocalDate minimumProfileAsOf) {}
}
