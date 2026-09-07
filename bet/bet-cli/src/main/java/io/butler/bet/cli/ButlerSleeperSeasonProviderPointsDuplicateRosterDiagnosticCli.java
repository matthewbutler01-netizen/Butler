package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperSeasonProviderPointsDuplicateRosterDiagnostic;

import java.nio.file.Path;

/** Read-only BF-593 operator surface for duplicate historical Sleeper roster identities. */
public final class ButlerSleeperSeasonProviderPointsDuplicateRosterDiagnosticCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperSeasonProviderPointsDuplicateRosterDiagnosticCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperSeasonProviderPointsDuplicateRosterDiagnostic(database)
                .diagnose(options.leagueId(), options.season()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 2) {
            throw new IllegalArgumentException(
                "Usage: sleeperSeasonProviderPointsDuplicateRosterDiagnostic <butler-league-id> <season>");
        }
        String leagueId = requireText(args[0], "butler-league-id");
        int season = parseInt(args[1], "season");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        return new Options(leagueId, season);
    }

    static void print(SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DiagnosticReport report) {
        System.out.println("Sleeper season provider-points duplicate-roster diagnostic");
        System.out.println("Policy: " + report.policyId());
        System.out.println("League: " + report.leagueName() + " [" + report.leagueId() + "]");
        System.out.println("Season: " + report.season());
        System.out.println("Historical Sleeper league: " + report.sleeperLeagueId());
        System.out.println("Week scan: " + report.firstWeek() + "-" + report.lastWeek());
        System.out.println("Duplicate player-week observations: " + report.duplicates().size());
        System.out.println();

        if (report.duplicates().isEmpty()) {
            System.out.println("No duplicate roster identity observations found.");
        } else {
            for (var duplicate : report.duplicates()) {
                System.out.println("week " + duplicate.week()
                    + " | player=" + duplicate.playerId()
                    + " | kind=" + duplicate.kind());
                for (var row : duplicate.rows()) {
                    System.out.println("  roster=" + row.rosterId()
                        + " | matchup=" + (row.matchupId() == null ? "none" : row.matchupId())
                        + " | players occurrences=" + row.playerOccurrences()
                        + " | starter=" + row.inStarters()
                        + " | starter occurrences=" + row.starterOccurrences()
                        + " | provider points=" + (row.providerPoints() == null ? "missing" : row.providerPoints().toPlainString()));
                }
            }
        }

        System.out.println();
        System.out.println("Boundary: read-only source diagnostics only. This command does not choose a canonical roster, deduplicate provider observations, infer transactions or ownership, persist provider points, rewrite historical evidence, change scoring or lineup eligibility, widen BF-518/BF-521 readiness, tune thresholds, rank managers, or make recommendations.");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static int parseInt(String value, String field) {
        try {
            return Integer.parseInt(requireText(value, field));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be an integer: " + value, e);
        }
    }

    record Options(String leagueId, int season) {}
}
