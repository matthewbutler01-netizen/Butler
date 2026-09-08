package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverRecommendationEvidenceAgeTelemetry;

import java.nio.file.Path;

/** BF-633 operator surface for read-only governed recommendation evidence age telemetry. */
public final class ButlerSleeperLiveWaiverRecommendationEvidenceAgeTelemetryCli {
    private ButlerSleeperLiveWaiverRecommendationEvidenceAgeTelemetryCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperLiveWaiverRecommendationEvidenceAgeTelemetry <butler-league-id>; exact Sleeper user/league/roster must be bound by BF-622");
            }
            String leagueId = args[0].trim();
            Database database = new Database(Path.of("butler.db"));
            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            ButlerPersonalizedTargetCliSupport.printVerified(target);
            print(new SleeperLiveWaiverRecommendationEvidenceAgeTelemetry(database).inspect(target));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport report) {
        System.out.println("BF-633 - read-only governed recommendation evidence-age telemetry");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Butler league / BF-623 verified owner: " + report.leagueId() + " / " + report.sleeperOwnerId());
        System.out.println("Sleeper league / target roster: " + report.sleeperLeagueId() + " / " + report.rosterId());
        System.out.println("Observed at UTC: " + report.observedAtUtc());
        System.out.println("Latest audit: " + value(report.auditId()));
        System.out.println("BF-631 evidence lineage: " + report.bf631State());
        System.out.println("Telemetry state: " + report.state());
        if (report.state() == SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.EVIDENCE_AGE_REPORTED) {
            System.out.println("Audit age seconds: " + report.auditAgeSeconds());
            System.out.println("Latest BF-603 observed / age seconds: "
                + report.latestMarketObservedAtUtc() + " / " + report.latestMarketAgeSeconds());
            System.out.println("Latest BF-602 observed / age seconds: "
                + report.latestWaiverObservedAtUtc() + " / " + report.latestWaiverAgeSeconds());
        }
        System.out.println();
        System.out.println("Boundary: BF-633 is read-only age telemetry. It applies no stale-after threshold and does not change BF-632 current/actionable status, rerun BF-620, rerank players, create a replacement recommendation, set FAAB, submit a Sleeper transaction, mutate the league, or write audit/waiver/market data.");
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
