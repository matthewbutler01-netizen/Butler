package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic;

import java.nio.file.Path;

/** Read-only BF-594 operator surface for duplicate-roster transaction provenance. */
public final class ButlerSleeperSeasonDuplicateRosterTransactionProvenanceDiagnosticCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperSeasonDuplicateRosterTransactionProvenanceDiagnosticCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic(database)
                .diagnose(options.leagueId(), options.season()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 2) {
            throw new IllegalArgumentException(
                "Usage: sleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic <butler-league-id> <season>");
        }
        String leagueId = requireText(args[0], "butler-league-id");
        int season = parseInt(args[1], "season");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        return new Options(leagueId, season);
    }

    static void print(SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic.DiagnosticReport report) {
        System.out.println("Sleeper season duplicate-roster transaction provenance diagnostic");
        System.out.println("Policy: " + report.policyId());
        System.out.println("League: " + report.leagueName() + " [" + report.leagueId() + "]");
        System.out.println("Season: " + report.season());
        System.out.println("Historical Sleeper league: " + report.sleeperLeagueId());
        System.out.println("Transaction round scan: " + report.firstRound() + "-" + report.lastRound());
        System.out.println("Cross-roster duplicate identities: " + report.players().size());
        System.out.println();

        if (report.players().isEmpty()) {
            System.out.println("No BF-593 cross-roster duplicate identities found.");
        } else {
            for (var player : report.players()) {
                System.out.println("player=" + player.playerId()
                    + " | duplicate weeks=" + player.duplicateWeeks()
                    + " | state=" + player.state()
                    + " | final provider rosters=" + compact(player.finalRosterIds()));
                if (player.transactions().isEmpty()) {
                    System.out.println("  matching complete transactions: none");
                } else {
                    for (var transaction : player.transactions()) {
                        System.out.println("  round=" + transaction.round()
                            + " | transaction=" + transaction.transactionId()
                            + " | type=" + transaction.type()
                            + " | leg=" + nullable(transaction.leg())
                            + " | created=" + nullable(transaction.created())
                            + " | status_updated=" + nullable(transaction.statusUpdated())
                            + " | roster_ids=" + transaction.rosterIds()
                            + " | add roster=" + nullable(transaction.addedRosterId())
                            + " | drop roster=" + nullable(transaction.droppedRosterId()));
                    }
                }
            }
        }

        System.out.println();
        System.out.println("Boundary: read-only source provenance only. Transaction and final-roster evidence are descriptive and do not choose a canonical historical roster. This command does not deduplicate matchup rows, rewrite provider evidence, persist provider points, reconstruct transactions beyond explicit Sleeper fields, change scoring or lineup eligibility, widen BF-518/BF-521 readiness, tune thresholds, rank managers, or make recommendations.");
    }

    private static String compact(java.util.List<Integer> values) {
        return values.isEmpty() ? "none" : values.toString();
    }

    private static String nullable(Object value) {
        return value == null ? "none" : value.toString();
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
