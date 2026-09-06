package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperCohortCorpusAcquisitionPlanner;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** Read-only operator CLI for deterministic anchor-owner cohort corpus discovery. */
public final class ButlerSleeperCohortCorpusAcquisitionPlanCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperCohortCorpusAcquisitionPlanCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperCohortCorpusAcquisitionPlanner(database)
                .plan(options.anchorButlerLeagueId(), options.targetSeason()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Sleeper cohort corpus acquisition plan interrupted: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Sleeper cohort corpus acquisition plan failed: " + e.getMessage());
            System.exit(1);
        } catch (SQLException e) {
            System.err.println("Database error while planning cohort corpus acquisition: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 2) throw usage();
        String leagueId = requireText(args[0], "anchor-butler-league-id");
        int season;
        try {
            season = Integer.parseInt(requireText(args[1], "target-season"));
        } catch (NumberFormatException e) {
            throw usage();
        }
        if (season < 1999 || season > 2100) throw usage();
        return new Options(leagueId, season);
    }

    static void print(SleeperCohortCorpusAcquisitionPlanner.AcquisitionPlan plan) {
        System.out.println("Sleeper cohort corpus acquisition plan");
        System.out.println("Policy: " + plan.policyId());
        System.out.println("Anchor Butler league: " + plan.anchorButlerLeagueId());
        System.out.println("Anchor Sleeper league: " + plan.anchorSleeperLeagueId());
        System.out.println("Anchor rosters: " + plan.anchorRosterCount());
        System.out.println("Anchor owner identities: " + plan.anchorOwnerCount());
        System.out.println("Ownerless anchor rosters: " + plan.ownerlessAnchorRosters());
        System.out.println("Target season: " + plan.targetSeason());
        System.out.println("Candidate leagues: " + plan.candidates().size());
        for (var candidate : plan.candidates()) {
            System.out.println("  " + candidate.sleeperLeagueId() + " | " + candidate.leagueName());
            System.out.printf("    season=%d league-type=%d draft-rounds=%d rosters=%d%n",
                candidate.season(), candidate.leagueType(), candidate.draftRounds(), candidate.rosterCount());
            System.out.println("    roster-positions=" + candidate.rosterPositions());
            System.out.println("    exposed-by-anchor-owners=" + candidate.exposingOwnerIds());
            System.out.println("    state=" + candidate.state()
                + (candidate.existingButlerLeagueId() == null ? "" : " butler-league=" + candidate.existingButlerLeagueId()));
        }
        System.out.println("Boundary: " + plan.boundary());
        System.out.println("Interpretation: every target-season Sleeper NFL league reachable through every owner identity represented in the anchor league is reported exactly once in Sleeper league-ID order. No candidate is selected from lineup outcomes, league size, or readiness impact.");
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
            "Usage: sleeperCohortCorpusAcquisitionPlan --args=\"<anchor-butler-league-id> <target-season>\"");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    record Options(String anchorButlerLeagueId, int targetSeason) {}
}
