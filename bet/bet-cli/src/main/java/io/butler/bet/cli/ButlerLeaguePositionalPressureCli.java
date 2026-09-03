package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeaguePositionalPressureAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Read-only CLI for governed lineup-aware positional pressure tiers. */
public final class ButlerLeaguePositionalPressureCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeaguePositionalPressureCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            var analyzer = new LeaguePositionalPressureAnalyzer(initializedDatabase());
            var report = options.minimumAsOf() == null
                ? (options.source() == null ? analyzer.analyze(options.leagueId()) : analyzer.analyze(options.leagueId(), options.source()))
                : (options.source() == null ? analyzer.analyze(options.leagueId(), options.minimumAsOf())
                    : analyzer.analyze(options.leagueId(), options.source(), options.minimumAsOf()));
            print(report);
        } catch (SQLException e) {
            System.err.println("Database error while building positional pressure tiers: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length < 3 || args.length > 6) {
            throw new IllegalArgumentException("league positional-pressure requires league id with optional source/minimum-as-of");
        }
        String leagueId = requireText(args[2], "league-id");
        if (args.length == 3) return new Options(leagueId, null, null);
        if (args.length == 4) {
            if ("--minimum-as-of".equalsIgnoreCase(args[3])) throw new IllegalArgumentException("--minimum-as-of requires YYYY-MM-DD");
            return new Options(leagueId, requireText(args[3], "source"), null);
        }
        if (args.length == 5 && "--minimum-as-of".equalsIgnoreCase(args[3])) {
            return new Options(leagueId, null, parseDate(args[4]));
        }
        if (args.length == 6 && "--minimum-as-of".equalsIgnoreCase(args[4])) {
            return new Options(leagueId, requireSource(args[3]), parseDate(args[5]));
        }
        throw new IllegalArgumentException("invalid positional-pressure arguments");
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0]) && "positional-pressure".equalsIgnoreCase(args[1]);
    }

    static void print(LeaguePositionalPressureAnalyzer.PositionalPressureReport report) {
        System.out.println("League lineup-aware positional pressure");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Source: " + report.source());
        if (report.minimumAsOfDate() != null) System.out.println("Minimum as-of: " + report.minimumAsOfDate());
        System.out.println("Pressure policy: " + report.policyId());
        System.out.println("Lineup policy: " + report.lineupPolicyId());
        System.out.printf("Flex exposure: FLEX=%d SUPERFLEX=%d%n", report.flexSlots(), report.superFlexSlots());
        if (!report.unknownLineupSlots().isEmpty()) System.out.println("Unknown lineup slots: " + report.unknownLineupSlots());
        System.out.println("Ranking uses only the top N valued players where N is the direct starter requirement. FLEX/SUPERFLEX are separate context; no production/age weighting or recommendation is applied.");
        for (String position : java.util.List.of("QB", "RB", "WR", "TE")) {
            var pressure = report.positions().get(position);
            System.out.printf("%n%s direct-starters=%d available=%s%n", position, pressure.directStarterRequirement(), pressure.available());
            if (!pressure.available()) System.out.println("  Reason: " + pressure.insufficiencyReason());
            for (var team : pressure.teams()) {
                System.out.printf("  %s [%s]: tier=%s starter-coverage-value=%.2f total-position-value=%.2f players=%d valued=%d stale=%d missing=%d%n",
                    team.teamName(), team.teamId(), team.tier(), team.starterCoverageValue(), team.totalPositionValue(),
                    team.totalPlayers(), team.valuedPlayers(), team.stalePlayers(), team.missingPlayers());
            }
        }
    }

    private static String requireSource(String value) {
        String source = requireText(value, "source");
        if (source.startsWith("--")) throw new IllegalArgumentException("source must not be an option flag");
        return source;
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
