package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperCohortCorpusHydrator;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** Operator entry point for governed lineage-aware BF-555 corpus hydration. */
public final class ButlerSleeperCohortCorpusHydrateCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperCohortCorpusHydrateCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperCohortCorpusHydrator(database)
                .hydrate(options.anchorLeagueId(), options.firstSeason(), options.lastSeason()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Sleeper cohort corpus hydration interrupted: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Sleeper cohort corpus hydration failed: " + e.getMessage());
            System.exit(1);
        } catch (SQLException e) {
            System.err.println("Database error while hydrating Sleeper cohort corpus: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 3) throw usage();
        String anchorLeagueId = requireText(args[0], "anchor-league-id");
        int firstSeason = parseSeason(args[1]);
        int lastSeason = parseSeason(args[2]);
        if (firstSeason > lastSeason) throw usage();
        return new Options(anchorLeagueId, firstSeason, lastSeason);
    }

    static void print(SleeperCohortCorpusHydrator.HydrationResult result) {
        System.out.println("Sleeper cohort corpus hydration");
        System.out.println("Policy: " + result.policyId());
        System.out.println("Anchor Butler league: " + result.anchorButlerLeagueId());
        System.out.println("Anchor Sleeper league: " + result.anchorSleeperLeagueId());
        System.out.println("Requested seasons: " + result.firstSeason() + ".." + result.lastSeason());
        System.out.println("Discovered candidate season-leagues: " + result.discoveredCandidateSeasonLeagues());
        System.out.println("Provider lineage root groups: " + result.providerRootGroups());
        System.out.printf("Butler lineages: new=%d reused=%d blocked-or-import-failed=%d%n",
            result.newButlerLineages(), result.reusedButlerLineages(), result.blockedOrImportFailedLineages());
        System.out.printf("Season hydration results: success=%d failed=%d%n",
            result.successfulSeasons(), result.failedSeasons());

        for (var lineage : result.lineages()) {
            System.out.println("  lineage-root=" + lineage.rootSleeperLeagueId());
            System.out.println("    latest-candidate=" + lineage.latestCandidateSleeperLeagueId());
            System.out.println("    state=" + lineage.state()
                + (lineage.butlerLeagueId() == null ? "" : " butler-league=" + lineage.butlerLeagueId()));
            System.out.println("    discovered-seasons:");
            for (var candidate : lineage.candidates()) {
                System.out.printf("      %d -> %s | %s | rosters=%d type=%d exposed-by=%s%n",
                    candidate.season(), candidate.sleeperLeagueId(), candidate.leagueName(),
                    candidate.rosterCount(), candidate.leagueType(), candidate.exposingOwnerIds());
            }
            if (lineage.failure() != null) {
                System.out.println("    lineage-failure=" + lineage.failure());
                continue;
            }
            System.out.println("    season-evidence:");
            for (var season : lineage.seasons()) {
                if (season.state() == SleeperCohortCorpusHydrator.SeasonState.SUCCESS) {
                    var hydration = season.hydration();
                    System.out.printf("      %d SUCCESS sleeper=%s weeks=%d team-week-snapshots=%d new-player-mappings=%d as-of=%s%n",
                        season.candidate().season(), hydration.sleeperLeagueId(), hydration.weeksImported(),
                        hydration.teamWeekSnapshots(), hydration.newPlayersCreated(), hydration.asOfDate());
                } else {
                    System.out.printf("      %d FAILED sleeper=%s reason=%s%n",
                        season.candidate().season(), season.candidate().sleeperLeagueId(), season.failure());
                }
            }
        }

        System.out.println("Boundary: " + result.boundary());
        System.out.println("Interpretation: the complete BF-555 candidate frame is processed by provider lineage. "
            + "Historical seasons are not counted as independent Butler leagues, and failures remain visible rather than being replaced by preferred candidates.");
        System.out.println("Next evidence step: refresh nflverse weekly production for each hydrated season before BF-518/BF-521 audit.");
    }

    private static int parseSeason(String value) {
        try {
            int season = Integer.parseInt(requireText(value, "season"));
            if (season < 1999 || season > 2100) throw new NumberFormatException();
            return season;
        } catch (NumberFormatException e) {
            throw usage();
        }
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
            "Usage: sleeperCohortCorpusHydrate --args=\"<anchor-butler-league-id> <first-season> <last-season>\"");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    record Options(String anchorLeagueId, int firstSeason, int lastSeason) {}
}
