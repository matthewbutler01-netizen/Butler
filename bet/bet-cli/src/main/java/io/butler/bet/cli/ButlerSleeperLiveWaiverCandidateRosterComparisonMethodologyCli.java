package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverCandidateRosterComparisonMethodology;

import java.nio.file.Path;

/** BF-614 operator surface, gated by BF-623 exact personalized target verification. */
public final class ButlerSleeperLiveWaiverCandidateRosterComparisonMethodologyCli {
    private ButlerSleeperLiveWaiverCandidateRosterComparisonMethodologyCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperLiveWaiverCandidateRosterComparisonMethodologyAudit <butler-league-id>; exact Sleeper user/league/roster must be bound by BF-622");
            }
            String leagueId = args[0].trim();
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            ButlerPersonalizedTargetCliSupport.printVerified(target);
            print(new SleeperLiveWaiverCandidateRosterComparisonMethodology(database)
                .audit(leagueId, target.sleeperUserId()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport report) {
        System.out.println("Sleeper 2026 governed candidate-vs-roster comparison methodology audit");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Butler league: " + report.leagueId());
        System.out.println("BF-603 market snapshot: " + report.marketSnapshotId());
        System.out.println("Referenced BF-602 waiver snapshot: " + report.waiverSnapshotId());
        System.out.println("Sleeper league / target roster: " + report.sleeperLeagueId() + " / " + report.rosterId());
        System.out.println("Provider season/status/leg: " + report.providerSeason() + "/"
            + report.providerStatus() + "/" + value(report.providerLeg()));
        System.out.println("BF-623 verified target Sleeper owner: " + report.sleeperOwnerId());
        System.out.println("BF-613 candidates total / reviewable: "
            + report.candidateCount() + "/" + report.reviewableCandidateCount());
        System.out.println("Reviewable prior-production PRESENT / MISSING: "
            + report.reviewableWithPriorProduction() + "/" + report.reviewableWithoutPriorProduction());
        System.out.println("Target roster starter/bench/reserve/taxi: "
            + report.targetPlayerCount() + " | " + report.starterCount() + "/" + report.benchCount()
            + "/" + report.reserveCount() + "/" + report.taxiCount());
        System.out.println("Target prior-production PRESENT / MISSING: "
            + report.targetPriorProductionPresent() + "/" + report.targetPriorProductionMissing());

        System.out.println("Exact persisted league scoring settings:");
        report.exactLeagueScoringSettings().forEach((key, value) ->
            System.out.println("  " + key + "=" + value));
        System.out.println("Supported scoring rules (league weight / raw dimension / minimum raw schema):");
        report.supportedScoringRules().forEach((key, rule) ->
            System.out.println("  " + key + " | " + rule.pointsPerUnit() + " | "
                + rule.rawDimension() + " | v" + rule.minimumRawSchemaVersion()));
        System.out.println("Unsupported/excluded league scoring settings (not silently zeroed):");
        if (report.unsupportedScoringSettings().isEmpty()) {
            System.out.println("  none");
        } else {
            report.unsupportedScoringSettings().forEach((key, value) ->
                System.out.println("  " + key + "=" + value));
        }
        System.out.println("Required core scoring keys: " + report.requiredCoreScoringKeys());
        System.out.println("Replacement comparator slots: " + report.replacementSlots());
        System.out.println("Position rule: " + report.positionRule());
        System.out.println("Source rule: " + report.sourceRule());
        System.out.println("Numeric rule: " + report.numericRule());
        System.out.println("Newcomer rule: " + report.newcomerRule());
        System.out.println("Missing target-production rule: " + report.missingRosterProductionRule());
        System.out.println("Descriptive context rule: " + report.descriptiveContextRule());
        System.out.println("Protected target identities with no governed 2025 production:");
        if (report.protectedMissingProduction().isEmpty()) {
            System.out.println("  none");
        } else {
            for (var player : report.protectedMissingProduction()) {
                System.out.println("  " + player.sleeperPlayerId() + " | " + value(player.displayName())
                    + " | pos=" + value(player.position()) + " | rosterSlot=" + value(player.rosterSlot()));
            }
        }
        System.out.println("Comparison methodology state: " + report.state());
        System.out.println();
        System.out.println("Boundary: BF-614 freezes the rules BF-615 must execute only after BF-623 verifies the persisted requesting-user account+league+roster binding. It does not rank waiver candidates, choose a winner, identify a player to drop, pair an add/drop transaction, provide FAAB guidance, or emit player-value, confidence, probability, or manager-evaluation claims. The supported scoring subtotal is not full fantasy points when league scoring contains unsupported dimensions.");
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
