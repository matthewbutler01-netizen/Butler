package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverRecommendationAuditHistory;

import java.nio.file.Path;

/** BF-628 operator surface for read-only governed recommendation audit history and integrity verification. */
public final class ButlerSleeperLiveWaiverRecommendationAuditHistoryCli {
    private ButlerSleeperLiveWaiverRecommendationAuditHistoryCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperLiveWaiverRecommendationAuditHistory <butler-league-id>; exact Sleeper user/league/roster must be bound by BF-622");
            }
            String leagueId = args[0].trim();
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            ButlerPersonalizedTargetCliSupport.printVerified(target);
            print(new SleeperLiveWaiverRecommendationAuditHistory(database).inspect(target));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverRecommendationAuditHistory.HistoryReport report) {
        System.out.println("BF-628 - read-only governed recommendation audit history + integrity verification");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Butler league / BF-623 verified owner: " + report.leagueId() + " / " + report.sleeperOwnerId());
        System.out.println("Sleeper league / target roster: " + report.sleeperLeagueId() + " / " + report.rosterId());
        System.out.println("History state: " + report.state());
        System.out.println("Immutable audit records: " + report.entries().size());
        for (var entry : report.entries()) {
            System.out.println("  Audit: " + entry.auditId() + " | captured=" + entry.capturedAtUtc());
            System.out.println("    provider=" + entry.providerSeason() + "/" + entry.providerStatus() + "/" + value(entry.providerLeg()));
            System.out.println("    BF-603 market / BF-602 waiver=" + entry.marketSnapshotId() + " / " + entry.waiverSnapshotId());
            System.out.println("    selection / recommendation=" + entry.selectionState() + " / " + entry.recommendationState());
            System.out.println("    add / drop Sleeper ids=" + value(entry.addSleeperPlayerId()) + " / " + value(entry.dropSleeperPlayerId()));
            System.out.println("    integrity=" + entry.integrityState());
        }
        System.out.println();
        System.out.println("Boundary: BF-628 is read-only. It does not capture or rewrite audit records, rerun recommendation selection, submit a Sleeper transaction, set FAAB, mutate the Sleeper league, or change recommendation methodology.");
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
