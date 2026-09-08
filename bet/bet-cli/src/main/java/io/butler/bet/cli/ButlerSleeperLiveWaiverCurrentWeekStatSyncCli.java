package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverCurrentWeekStatSync;

import java.nio.file.Path;

/** BF-607 operator surface for immutable current-week market-active raw-stat evidence. */
public final class ButlerSleeperLiveWaiverCurrentWeekStatSyncCli {
    private ButlerSleeperLiveWaiverCurrentWeekStatSyncCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException("Usage: sleeperLiveWaiverCurrentWeekStatSync <butler-league-id>");
            }
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            print(new SleeperLiveWaiverCurrentWeekStatSync(database).sync(args[0].trim()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverCurrentWeekStatSync.SyncReport report) {
        System.out.println("Sleeper 2026 market-active current-week stat sync");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Source: " + report.source());
        System.out.println("Observation state: " + report.observationState());
        System.out.println("Current-week stat snapshot: " + report.statSnapshotId());
        System.out.println("Referenced BF-603 market snapshot: " + report.marketSnapshotId());
        System.out.println("Referenced BF-606 availability snapshot: " + report.availabilitySnapshotId());
        System.out.println("Butler league: " + report.leagueId());
        System.out.println("Sleeper league: " + report.sleeperLeagueId());
        System.out.println("Provider season/status/leg: " + report.providerSeason() + "/"
            + report.providerStatus() + "/" + report.providerLeg());
        System.out.println("Sleeper NFL state season/week/type: " + report.stateSeason() + "/"
            + report.stateWeek() + "/" + report.stateSeasonType());
        System.out.println("Market-active candidates: " + report.candidateCount());
        System.out.println("Weekly stat source PRESENT / ABSENT: "
            + report.sourcePresentCount() + "/" + report.sourceAbsentCount());
        System.out.println("Explicit opportunity-key coverage pass_att/rush_att/rec_tgt/rec: "
            + report.withPassAttCount() + "/" + report.withRushAttCount() + "/"
            + report.withRecTgtCount() + "/" + report.withReceptionsCount());
        System.out.println("Observed at UTC: " + report.observedAtUtc());
        System.out.println("Exact current-week observations:");
        for (var candidate : report.candidates()) {
            System.out.println("  " + candidate.sleeperPlayerId()
                + " | " + candidate.displayName()
                + " | pos=" + value(candidate.position())
                + " | add=" + candidate.addCount()
                + " | drop=" + candidate.dropCount()
                + " | frame=" + candidate.frameMembership()
                + " | source=" + candidate.sourceState()
                + " | pass_att=" + value(candidate.passAtt())
                + " | rush_att=" + value(candidate.rushAtt())
                + " | targets=" + value(candidate.recTgt())
                + " | rec=" + value(candidate.receptions())
                + " | pass_yd=" + value(candidate.passYd())
                + " | rush_yd=" + value(candidate.rushYd())
                + " | rec_yd=" + value(candidate.recYd())
                + " | pass_td=" + value(candidate.passTd())
                + " | rush_td=" + value(candidate.rushTd())
                + " | rec_td=" + value(candidate.recTd()));
        }
        System.out.println("Immutable current-week stat snapshots retained for league: "
            + report.snapshotCountForLeague());
        System.out.println("Current-week stat state: PERSISTED_VERIFIED");
        System.out.println();
        System.out.println("Boundary: BF-607 preserves a timestamped current-week Sleeper raw-stat observation. Current-week finality is unproven; a missing player row or missing stat key is not interpreted as zero, DNP, or a final result. This evidence is not a projection, waiver ranking, FAAB recommendation, add/drop recommendation, manager evaluation, confidence, or probability output.");
    }

    private static String value(Object value) { return value == null ? "none" : value.toString(); }
}
