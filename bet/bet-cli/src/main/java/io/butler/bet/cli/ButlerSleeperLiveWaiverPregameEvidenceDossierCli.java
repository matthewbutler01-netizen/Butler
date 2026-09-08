package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverPregameEvidenceDossier;

import java.nio.file.Path;

/** BF-608 operator surface for the governed pregame waiver evidence dossier. */
public final class ButlerSleeperLiveWaiverPregameEvidenceDossierCli {
    private ButlerSleeperLiveWaiverPregameEvidenceDossierCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException("Usage: sleeperLiveWaiverPregameEvidenceDossier <butler-league-id>");
            }
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            print(new SleeperLiveWaiverPregameEvidenceDossier(database).audit(args[0].trim()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverPregameEvidenceDossier.DossierReport report) {
        System.out.println("Sleeper 2026 governed pregame waiver evidence dossier");
        System.out.println("Policy: " + report.policyId());
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
        System.out.println("Market-active candidates: " + report.candidateCount());
        System.out.println("Evidence coverage team/status/injury-flag/depth/prior-production/current-week-observed: "
            + report.teamKnownCount() + "/" + report.providerStatusKnownCount() + "/"
            + report.injuryFlagPresentCount() + "/" + report.depthEvidencePresentCount() + "/"
            + report.priorSeasonProductionPresentCount() + "/" + report.currentWeekObservedCount());
        System.out.println("Exact pregame candidate dossiers (BF-603 market order; not a player ranking):");

        for (var candidate : report.candidates()) {
            var market = candidate.market();
            var availability = candidate.availability();
            var week = candidate.currentWeek();
            System.out.println("  " + market.sleeperPlayerId()
                + " | " + market.displayName()
                + " | pos=" + value(market.position())
                + " | market add/drop/net=" + market.addCount() + "/" + market.dropCount() + "/" + market.netAddAttention()
                + " | frame=" + market.frameMembership()
                + " | team=" + value(availability.currentTeam())
                + " | status=" + value(availability.currentStatus())
                + " | injury=" + value(availability.injuryStatus())
                + " | practice=" + value(availability.practiceParticipation())
                + " | depth=" + value(availability.depthChartPosition()) + "/" + value(availability.depthChartOrder())
                + " | evidence=" + candidate.teamEvidenceState() + ","
                    + candidate.providerStatusEvidenceState() + ","
                    + candidate.injuryEvidenceState() + ","
                    + candidate.depthEvidenceState() + ","
                    + candidate.priorSeasonProductionState() + ","
                    + candidate.currentWeekEvidenceState()
                + " | week source=" + week.sourceState());

            if (candidate.priorSeasonProduction().isEmpty()) {
                System.out.println("      2025 production: none (explicit coverage gap)");
            } else {
                for (var production : candidate.priorSeasonProduction()) {
                    System.out.println("      2025 production " + production.source() + "@" + production.asOfDate()
                        + " | gp=" + production.gamesPlayed()
                        + " pass=" + production.passingYards() + "yd/" + production.passingTouchdowns() + "td"
                        + " rush=" + production.rushingYards() + "yd/" + production.rushingTouchdowns() + "td"
                        + " rec=" + production.receptions() + "/" + production.receivingYards() + "yd/"
                        + production.receivingTouchdowns() + "td");
                }
            }
            if ("SOURCE_PRESENT".equals(week.sourceState())) {
                System.out.println("      Week " + report.stateWeek() + " observed: pass_att=" + value(week.passAtt())
                    + " rush_att=" + value(week.rushAtt()) + " targets=" + value(week.recTgt())
                    + " rec=" + value(week.receptions()) + " pass_yd=" + value(week.passYd())
                    + " rush_yd=" + value(week.rushYd()) + " rec_yd=" + value(week.recYd()));
            } else {
                System.out.println("      Week " + report.stateWeek()
                    + " observed: UNOBSERVED (no zero/DNP/finality inference)");
            }
        }

        System.out.println("Pregame dossier state: READY_EVIDENCE_ONLY");
        System.out.println();
        System.out.println("Boundary: BF-608 composes existing governed evidence only. Market attention, prior-season production, current team/status/injury/depth metadata, and current-week source presence are not converted into a player score or value claim. Missing injury/practice/depth/current-week evidence remains unknown or unobserved. No waiver ranking, FAAB guidance, add/drop recommendation, winner selection, manager evaluation, start/sit grade, confidence, or probability is emitted.");
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
