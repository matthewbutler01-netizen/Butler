package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperSeasonProviderPointsEvidenceImporter;

import java.nio.file.Path;

/** Operator surface for BF-560 historical provider-points evidence persistence. */
public final class ButlerSleeperSeasonProviderPointsEvidenceSyncCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperSeasonProviderPointsEvidenceSyncCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperSeasonProviderPointsEvidenceImporter(database)
                .syncSeason(options.leagueId(), options.season()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 2) {
            throw new IllegalArgumentException(
                "Usage: sleeperSeasonProviderPointsEvidenceSync <butler-league-id> <season>");
        }
        String leagueId = requireText(args[0], "butler-league-id");
        int season = parseInt(args[1], "season");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        return new Options(leagueId, season);
    }

    static void print(SleeperSeasonProviderPointsEvidenceImporter.ImportResult result) {
        System.out.println("Sleeper season provider-points evidence sync");
        System.out.println("Policy: " + result.policyId());
        System.out.println("League: " + result.leagueName() + " [" + result.leagueId() + "]");
        System.out.println("Season: " + result.season());
        System.out.println("Historical Sleeper league: " + result.providerLeagueId());
        System.out.println("Source: " + result.source());
        System.out.println("Source surface: " + result.sourceSurface());
        System.out.println("As-of: " + result.asOfDate());
        System.out.println("Populated weeks: " + result.populatedWeeks());
        System.out.println("Rows persisted/read-back: " + result.rowsPersisted() + "/" + result.rowsReadBack());
        System.out.println("Observed DEF identities (" + result.defenseIdentities().size() + "): "
            + (result.defenseIdentities().isEmpty() ? "none" : result.defenseIdentities()));
        System.out.println("State: " + result.state());
        System.out.println();
        System.out.println("Boundary: provider-points persistence and provenance only. This command does not infer sparse raw-stat keys as zero, replace Butler exact scoring, change potential-lineup scoring, add K/DEF eligibility, perform provider-vs-nflverse precision/overlap calibration, widen BF-518/BF-521 readiness, tune thresholds, adjust rankings, create confidence, rank managers, or make recommendations.");
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
