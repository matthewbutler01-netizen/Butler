package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverRecommendationActionabilityRevalidation;

import java.nio.file.Path;

/** BF-629 operator surface for read-only live actionability revalidation of the latest governed audit. */
public final class ButlerSleeperLiveWaiverRecommendationActionabilityRevalidationCli {
    private ButlerSleeperLiveWaiverRecommendationActionabilityRevalidationCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperLiveWaiverRecommendationActionabilityRevalidation <butler-league-id>; exact Sleeper user/league/roster must be bound by BF-622");
            }
            String leagueId = args[0].trim();
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            ButlerPersonalizedTargetCliSupport.printVerified(target);
            print(new SleeperLiveWaiverRecommendationActionabilityRevalidation(database).revalidate(target));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport report) {
        System.out.println("BF-629 - read-only live actionability revalidation of latest governed recommendation");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Butler league / BF-623 verified owner: " + report.leagueId() + " / " + report.sleeperOwnerId());
        System.out.println("Sleeper league / target roster: " + report.sleeperLeagueId() + " / " + report.rosterId());
        System.out.println("Latest audit: " + value(report.auditId()) + " | captured=" + value(report.capturedAtUtc()));
        System.out.println("Recorded recommendation state: " + value(report.recommendationState()));
        System.out.println("Recorded add / drop Sleeper ids: " + value(report.addSleeperPlayerId()) + " / " + value(report.dropSleeperPlayerId()));
        System.out.println("Current add roster id: " + value(report.addCurrentRosterId()) + " (none = currently unrostered)");
        System.out.println("Current drop roster id: " + value(report.dropCurrentRosterId()));
        System.out.println("Current Sleeper rosters observed: " + report.currentRosterCount());
        System.out.println("Actionability state: " + report.state());
        System.out.println();
        System.out.println("Boundary: BF-629 is read-only. It revalidates only whether the latest BF-628 integrity-verified audited move is still actionable from current Sleeper roster ownership. It does not rerank players, manufacture a replacement recommendation, set FAAB, submit a Sleeper transaction, mutate the league, or write audit history.");
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
