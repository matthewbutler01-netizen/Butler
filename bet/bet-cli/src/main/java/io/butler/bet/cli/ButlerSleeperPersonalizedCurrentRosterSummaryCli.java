package io.butler.bet.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperPersonalizedCurrentRosterSummary;

import java.nio.file.Path;

/** BF-645 read-only current live roster presentation for the exact BF-623 target. */
public final class ButlerSleeperPersonalizedCurrentRosterSummaryCli {
    private ButlerSleeperPersonalizedCurrentRosterSummaryCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException("Usage: sleeperPersonalizedCurrentRosterSummary <butler-league-id>");
            }
            String leagueId = args[0].trim();
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            ButlerPersonalizedTargetCliSupport.printVerified(target);
            var report = new SleeperPersonalizedCurrentRosterSummary(database).summarize(target);

            System.out.println();
            System.out.println("BF-645 - live personalized current roster summary");
            System.out.println("Policy: " + report.policyId());
            System.out.println("Target: " + report.leagueName() + " | " + value(report.teamName()) + " | roster " + report.rosterId());
            System.out.println("Observed at UTC: " + report.observedAtUtc());
            System.out.println("Roster state: " + report.state());
            System.out.println("Starter lineup slots: " + report.lineupSlotState());
            System.out.println("Player count: " + report.players().size());
            System.out.println("BF-645 roster JSON: " + new ObjectMapper().writeValueAsString(report));
            System.out.println("Boundary: BF-645 is read-only current-roster presentation. It does not rank players, recompute recommendations, refresh evidence, write snapshots/audits, set FAAB, or submit/cancel/replace a Sleeper transaction.");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
