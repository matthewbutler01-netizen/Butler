package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverAvailabilitySync;

import java.nio.file.Path;

/** BF-606 operator surface for immutable current market-active availability metadata. */
public final class ButlerSleeperLiveWaiverAvailabilitySyncCli {
    private ButlerSleeperLiveWaiverAvailabilitySyncCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException("Usage: sleeperLiveWaiverAvailabilitySync <butler-league-id>");
            }
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            print(new SleeperLiveWaiverAvailabilitySync(database).sync(args[0].trim()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverAvailabilitySync.SyncReport report) {
        System.out.println("Sleeper 2026 market-active waiver availability sync");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Source: " + report.source());
        System.out.println("Availability snapshot: " + report.availabilitySnapshotId());
        System.out.println("Referenced BF-603 market snapshot: " + report.marketSnapshotId());
        System.out.println("Butler league: " + report.leagueId());
        System.out.println("Sleeper league: " + report.sleeperLeagueId());
        System.out.println("Provider season/status/leg: " + report.providerSeason() + "/"
            + report.providerStatus() + "/" + report.providerLeg());
        System.out.println("Market-active candidates: " + report.candidateCount());
        System.out.println("Current player source PRESENT / ABSENT: "
            + report.sourcePresentCount() + "/" + report.sourceAbsentCount());
        System.out.println("Raw metadata coverage team/status/injury/practice/depth-position/depth-order: "
            + report.withTeamCount() + "/" + report.withStatusCount() + "/"
            + report.withInjuryStatusCount() + "/" + report.withPracticeParticipationCount() + "/"
            + report.withDepthChartPositionCount() + "/" + report.withDepthChartOrderCount());
        System.out.println("Observed at UTC: " + report.observedAtUtc());
        System.out.println("Exact market-active current metadata:");
        for (var candidate : report.candidates()) {
            System.out.println("  " + candidate.sleeperPlayerId()
                + " | " + candidate.displayName()
                + " | pos=" + value(candidate.position())
                + " | add=" + candidate.addCount()
                + " | drop=" + candidate.dropCount()
                + " | frame=" + candidate.frameMembership()
                + " | source=" + candidate.sourceState()
                + " | team=" + value(candidate.currentTeam())
                + " | status=" + value(candidate.currentStatus())
                + " | injury=" + value(candidate.injuryStatus())
                + " | injury_start=" + value(candidate.injuryStartDate())
                + " | practice=" + value(candidate.practiceParticipation())
                + " | depth_pos=" + value(candidate.depthChartPosition())
                + " | depth_order=" + value(candidate.depthChartOrder()));
        }
        System.out.println("Immutable availability snapshots retained for league: " + report.snapshotCountForLeague());
        System.out.println("Availability state: PERSISTED_VERIFIED");
        System.out.println();
        System.out.println("Boundary: BF-606 preserves raw Sleeper availability and depth metadata for the exact BF-603 market-active frame. Missing fields are not interpreted as healthy, available, or valuable. Depth data is not a fantasy ranking. No FAAB, add/drop recommendation, winner selection, manager evaluation, start/sit grade, confidence, or probability is emitted.");
    }

    private static String value(Object value) {
        if (value == null) return "none";
        String text = value.toString();
        return text.isBlank() ? "none" : text;
    }
}
