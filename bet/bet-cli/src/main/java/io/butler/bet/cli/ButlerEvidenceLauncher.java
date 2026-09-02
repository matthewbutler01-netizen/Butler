package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueEvidenceOverviewAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Adds evidence-overview freshness controls while delegating all established commands unchanged. */
public final class ButlerEvidenceLauncher {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerEvidenceLauncher() {}

    public static void main(String[] args) {
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
        Database database = new Database(DATABASE_PATH);
        database.initialize();
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
