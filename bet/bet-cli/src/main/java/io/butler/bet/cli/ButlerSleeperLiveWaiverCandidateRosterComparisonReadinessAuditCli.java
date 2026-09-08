package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverCandidateRosterComparisonReadinessAudit;

import java.nio.file.Path;

/** BF-613 operator surface for candidate-vs-roster comparison readiness. */
public final class ButlerSleeperLiveWaiverCandidateRosterComparisonReadinessAuditCli {
    private ButlerSleeperLiveWaiverCandidateRosterComparisonReadinessAuditCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 2
                || args[0] == null || args[0].isBlank()
                || args[1] == null || args[1].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperLiveWaiverCandidateRosterComparisonReadinessAudit <butler-league-id> <sleeper-owner-id>");
            }
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            print(new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit(database)
                .audit(args[0].trim(), args[1].trim()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.AuditReport report) {
        System.out.println("Sleeper 2026 candidate-vs-roster comparison readiness audit");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Butler league: " + report.leagueId());
        System.out.println("BF-603 market snapshot: " + report.marketSnapshotId());
        System.out.println("Referenced BF-602 waiver snapshot: " + report.waiverSnapshotId());
        System.out.println("Sleeper league / target roster: " + report.sleeperLeagueId() + " / " + report.rosterId());
        System.out.println("Provider season/status/leg: " + report.providerSeason() + "/"
            + report.providerStatus() + "/" + value(report.providerLeg()));
        System.out.println("Exact target Sleeper owner: " + report.sleeperOwnerId());
        System.out.println("BF-609 candidates total / reviewable: "
            + report.candidateCount() + "/" + report.reviewableCandidateCount());
        System.out.println("BF-609 blocked CURRENT_TEAM_UNKNOWN / DEPTH_EVIDENCE_MISSING: "
            + report.currentTeamUnknownCandidates() + "/" + report.depthEvidenceMissingCandidates());
        System.out.println("Reviewable candidate prior-production PRESENT / MISSING: "
            + report.reviewableWithPriorProduction() + "/" + report.reviewableWithoutPriorProduction());
        System.out.println("Target roster players starter/bench/reserve/taxi: "
            + report.targetPlayerCount() + " | " + report.starterCount() + "/"
            + report.benchCount() + "/" + report.reserveCount() + "/" + report.taxiCount());
        System.out.println("Target roster prior-production PRESENT / MISSING: "
            + report.targetPriorProductionPresent() + "/" + report.targetPriorProductionMissing());

        System.out.println("Reviewable candidate position coverage (with-prior / without-prior / total):");
        report.candidatePositionCoverage().forEach((position, coverage) ->
            System.out.println("  " + position + " | " + coverage.withPriorProduction() + "/"
                + coverage.withoutPriorProduction() + "/" + coverage.total()));

        System.out.println("Target roster position coverage (with-prior / without-prior | starter/bench/reserve/taxi | total):");
        report.rosterPositionCoverage().forEach((position, coverage) ->
            System.out.println("  " + position + " | " + coverage.withPriorProduction() + "/"
                + coverage.withoutPriorProduction() + " | " + coverage.starter() + "/"
                + coverage.bench() + "/" + coverage.reserve() + "/" + coverage.taxi()
                + " | " + coverage.total()));

        if (!report.missingRosterProduction().isEmpty()) {
            System.out.println("Exact target-roster identities with no governed 2025 NFL production found:");
            for (var player : report.missingRosterProduction()) {
                System.out.println("  " + player.sleeperPlayerId() + " | " + value(player.displayName())
                    + " | pos=" + player.position() + " | rosterSlot=" + player.rosterSlot());
            }
        }

        System.out.println("Comparison-readiness state: " + report.state());
        System.out.println();
        System.out.println("Boundary: BF-613 authorizes only the design of a governed candidate-vs-roster comparison methodology. READY_FOR_COMPARISON_METHODOLOGY does not mean any waiver candidate is better than any roster player. BF-613 does not score players or roster needs, select winners, rank waiver candidates, pair adds/drops, recommend transactions, provide FAAB guidance, or emit value/confidence/probability/manager-evaluation claims.");
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
