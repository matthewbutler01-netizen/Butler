package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperCorpusAcquisitionPlanner;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** Operator entry point for read-only deterministic Sleeper corpus candidate discovery. */
public final class ButlerSleeperCorpusAcquisitionPlanCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperCorpusAcquisitionPlanCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperCorpusAcquisitionPlanner(database)
                .plan(options.anchorLeagueId(), options.anchorTeamId(), options.targetSeason()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Sleeper corpus acquisition planning interrupted: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Sleeper corpus acquisition planning failed: " + e.getMessage());
            System.exit(1);
        } catch (SQLException e) {
            System.err.println("Database error while planning Sleeper corpus acquisition: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (args == null || args.length != 3) throw usage();
        String leagueId = requireText(args[0], "anchor-butler-league-id");
        String teamId = requireText(args[1], "anchor-butler-team-id");
        int season;
        try {
            season = Integer.parseInt(requireText(args[2], "target-season"));
        } catch (NumberFormatException e) {
            throw usage();
        }
        if (season < 1999 || season > 2100) throw usage();
        return new Options(leagueId, teamId, season);
    }

    static void print(SleeperCorpusAcquisitionPlanner.AcquisitionPlan plan) {
        System.out.println("Sleeper corpus acquisition plan");
        System.out.println("Policy: " + plan.policyId());
        System.out.println("Anchor Butler league: " + plan.anchorButlerLeagueId());
        System.out.println("Anchor Butler team: " + plan.anchorButlerTeamId());
        System.out.println("Anchor Sleeper league: " + plan.anchorSleeperLeagueId());
        System.out.println("Anchor Sleeper roster: " + plan.anchorSleeperRosterId());
        System.out.println("Resolved Sleeper owner: " + plan.sleeperOwnerId());
        System.out.println("Target season: " + plan.targetSeason());
        System.out.println("Candidate leagues: " + plan.candidates().size());
        for (var candidate : plan.candidates()) {
            System.out.println("  " + candidate.sleeperLeagueId() + " | " + candidate.leagueName());
            System.out.println("    season=" + candidate.season()
                + " league-type=" + candidate.leagueType()
                + " draft-rounds=" + candidate.draftRounds()
                + " rosters=" + candidate.rosterCount());
            System.out.println("    roster-positions=" + candidate.rosterPositions());
            System.out.println("    state=" + candidate.state()
                + (candidate.existingButlerLeagueId() == null
                    ? ""
                    : " butler-league=" + candidate.existingButlerLeagueId()));
        }
        System.out.println("Boundary: " + plan.boundary());
        System.out.println("Interpretation: all target-season Sleeper NFL leagues for the resolved anchor owner are "
            + "reported in Sleeper league-ID order. No candidate is selected from lineup outcomes or readiness impact.");
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
            "Usage: sleeperCorpusAcquisitionPlan --args=\"<anchor-butler-league-id> <anchor-butler-team-id> <target-season>\"");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    record Options(String anchorLeagueId, String anchorTeamId, int targetSeason) {}
}
