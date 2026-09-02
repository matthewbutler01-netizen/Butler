package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueAgeContextAnalyzer;
import io.butler.bet.intelligence.LeaguePlayerProfileCoverageAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** Adds neutral age-context CLI handling while delegating all established commands unchanged. */
public final class ButlerAgeLauncher {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgeLauncher() {}

    public static void main(String[] args) {
        if (!isAgeContextCommand(args)) {
            ButlerEvidenceLauncher.main(args);
            return;
        }

        AgeContextOptions options;
        try {
            options = parseAgeContext(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printAgeContextUsage();
            return;
        }

        try {
            printAgeContext(analyze(options));
        } catch (SQLException e) {
            System.err.println("Database error while building league age context: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static boolean isAgeContextCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("league")
            && args[1].equalsIgnoreCase("age-context");
    }

    static AgeContextOptions parseAgeContext(String[] args) {
        if (!isAgeContextCommand(args) || args.length < 3) {
            throw new IllegalArgumentException("league age-context requires a league id");
        }
        String leagueId = requireText(args[2], "league-id");
        LocalDate ageAsOf = null;
        LocalDate minimumProfileAsOf = null;
        int index = 3;
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
                throw new IllegalArgumentException("unsupported age-context option: " + flag);
            }
        }
        return new AgeContextOptions(leagueId, ageAsOf, minimumProfileAsOf);
    }

    private static LeagueAgeContextAnalyzer.AgeContextReport analyze(AgeContextOptions options) throws SQLException {
        LeagueAgeContextAnalyzer analyzer = new LeagueAgeContextAnalyzer(initializedDatabase());
        if (options.ageAsOf() == null && options.minimumProfileAsOf() == null) {
            return analyzer.analyze(options.leagueId());
        }
        LocalDate ageAsOf = options.ageAsOf() == null ? LocalDate.now(ZoneOffset.UTC) : options.ageAsOf();
        return analyzer.analyze(options.leagueId(), ageAsOf,
            LeaguePlayerProfileCoverageAnalyzer.DEFAULT_PROVIDER_SOURCE, options.minimumProfileAsOf());
    }

    static void printAgeContext(LeagueAgeContextAnalyzer.AgeContextReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("League age context");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Age as-of: " + report.ageAsOf());
        System.out.println("Profile source: " + report.providerSource());
        if (report.minimumProviderAsOf() != null) {
            System.out.println("Minimum profile as-of: " + report.minimumProviderAsOf());
        }
        System.out.printf("Coverage: %d/%d (%.1f%%)  exact-birth=%d  provider-reported=%d%n",
            report.coveredPlayers(), report.totalPlayers(), report.coveragePercent(),
            report.exactBirthDatePlayers(), report.providerReportedPlayers());

        for (var team : report.teams()) {
            System.out.printf("%s  coverage=%d/%d (%.1f%%)  avg=%s  min=%s  max=%s  exact=%d  reported=%d  [%s]%n",
                team.teamName(), team.coveredPlayers(), team.totalPlayers(), team.coveragePercent(),
                formatAge(team.averageAge()), formatAge(team.minimumAge()), formatAge(team.maximumAge()),
                team.exactBirthDatePlayers(), team.providerReportedPlayers(), team.teamId());
            for (var position : team.positions().values()) {
                System.out.printf("  %s  coverage=%d/%d (%.1f%%)  avg=%s  min=%s  max=%s  exact=%d  reported=%d%n",
                    position.position(), position.coveredPlayers(), position.totalPlayers(), position.coveragePercent(),
                    formatAge(position.averageAge()), formatAge(position.minimumAge()), formatAge(position.maximumAge()),
                    position.exactBirthDatePlayers(), position.providerReportedPlayers());
            }
            team.players().stream().filter(player -> !player.ageAvailable()).forEach(player ->
                System.out.printf("  missing-age: %s  %s  slot=%s  [%s]%n",
                    player.playerName(), player.position(), player.rosterSlot(), player.playerId()));
        }
    }

    static void printAgeContextUsage() {
        System.out.println("  butler league age-context <league-id> [--age-as-of YYYY-MM-DD] [--minimum-profile-as-of YYYY-MM-DD]");
    }

    private static String formatAge(Number value) {
        if (value == null) return "-";
        return value instanceof Double || value instanceof Float ? String.format("%.1f", value.doubleValue()) : value.toString();
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
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

    record AgeContextOptions(String leagueId, LocalDate ageAsOf, LocalDate minimumProfileAsOf) {}
}
