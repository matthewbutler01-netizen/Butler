package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverPregameEvidenceReadinessAudit;

import java.nio.file.Path;

/** BF-609 operator surface for pregame waiver evidence-readiness strata. */
public final class ButlerSleeperLiveWaiverPregameEvidenceReadinessAuditCli {
    private ButlerSleeperLiveWaiverPregameEvidenceReadinessAuditCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException("Usage: sleeperLiveWaiverPregameEvidenceReadinessAudit <butler-league-id>");
            }
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            print(new SleeperLiveWaiverPregameEvidenceReadinessAudit(database).audit(args[0].trim()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverPregameEvidenceReadinessAudit.ReadinessReport report) {
        System.out.println("Sleeper 2026 governed pregame waiver evidence-readiness audit");
        System.out.println("Policy: " + report.policyId());
        System.out.println("BF-608 dossier policy: " + report.dossierPolicyId());
        System.out.println("Butler league: " + report.leagueId());
        System.out.println("BF-603 market snapshot: " + report.marketSnapshotId());
        System.out.println("BF-603 observed at UTC: " + report.marketObservedAtUtc());
        System.out.println("BF-606 availability snapshot: " + report.availabilitySnapshotId());
        System.out.println("BF-606 observed at UTC: " + report.availabilityObservedAtUtc());
        System.out.println("BF-607 current-week snapshot: " + report.currentWeekSnapshotId());
        System.out.println("BF-607 observed at UTC: " + report.currentWeekObservedAtUtc());
        System.out.println("BF-607 observation state: " + report.currentWeekObservationState());
        System.out.println("NFL state season/week/type: " + report.stateSeason() + "/"
            + report.stateWeek() + "/" + report.stateSeasonType());
        System.out.println("Candidates classified: " + report.candidateCount());
        System.out.println("Primary readiness strata (mutually exclusive; not player ranks):");

        for (var stratum : SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.values()) {
            var summary = report.summaries().get(stratum);
            System.out.println("  " + stratum
                + " | total=" + summary.total()
                + " | injury-flag=" + summary.injuryFlagPresent()
                + " | week-observed=" + summary.currentWeekObserved()
                + " | market ADD_ONLY/DROP_ONLY/BOTH=" + summary.addOnly() + "/"
                + summary.dropOnly() + "/" + summary.both());
            for (var candidate : report.candidates()) {
                if (candidate.primaryStratum() != stratum) continue;
                var dossier = candidate.dossier();
                var market = dossier.market();
                var availability = dossier.availability();
                System.out.println("    " + market.sleeperPlayerId()
                    + " | " + market.displayName()
                    + " | pos=" + value(market.position())
                    + " | market=" + market.frameMembership()
                    + " add/drop/net=" + market.addCount() + "/" + market.dropCount() + "/" + market.netAddAttention()
                    + " | team=" + value(availability.currentTeam())
                    + " | status=" + value(availability.currentStatus())
                    + " | injury=" + value(availability.injuryStatus())
                    + " | depth=" + value(availability.depthChartPosition()) + "/" + value(availability.depthChartOrder())
                    + " | prior=" + dossier.priorSeasonProductionState()
                    + " | week=" + dossier.currentWeekEvidenceState());
            }
        }

        System.out.println("Pregame evidence-readiness state: CLASSIFIED_EVIDENCE_ONLY");
        System.out.println();
        System.out.println("Boundary: BF-609 strata describe evidence availability only. REVIEWABLE means the pregame dossier has the named evidence lanes; it does not mean the player is better, should be added, or should receive FAAB. CURRENT_TEAM_UNKNOWN and DEPTH_EVIDENCE_MISSING are evidence gaps, not negative player grades. No player score, waiver rank, shortlist winner, FAAB guidance, add/drop recommendation, start/sit recommendation, manager evaluation, confidence, probability, or fantasy-value prediction is emitted.");
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
