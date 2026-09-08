package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverTargetRosterContextAudit;

import java.nio.file.Path;

/** BF-610 operator surface, gated by BF-623 exact personalized target verification. */
public final class ButlerSleeperLiveWaiverTargetRosterContextAuditCli {
    private ButlerSleeperLiveWaiverTargetRosterContextAuditCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1
                || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperLiveWaiverTargetRosterContextAudit <butler-league-id>; exact Sleeper user/league/roster must be bound by BF-622");
            }
            String leagueId = args[0].trim();
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            ButlerPersonalizedTargetCliSupport.printVerified(target);
            print(new SleeperLiveWaiverTargetRosterContextAudit(database)
                .audit(leagueId, target.sleeperUserId()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverTargetRosterContextAudit.AuditReport report) {
        System.out.println("Sleeper 2026 governed live target-roster waiver context audit");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Butler league: " + report.leagueId());
        System.out.println("BF-603 market snapshot: " + report.marketSnapshotId());
        System.out.println("Referenced BF-602 waiver snapshot: " + report.waiverSnapshotId());
        System.out.println("Sleeper league: " + report.sleeperLeagueId());
        System.out.println("Provider season/status/leg: " + report.providerSeason() + "/"
            + report.providerStatus() + "/" + value(report.providerLeg()));
        System.out.println("BF-623 verified target Sleeper owner: " + report.sleeperOwnerId());
        System.out.println("Provider owner display/team name: " + value(report.ownerDisplayName())
            + " / " + value(report.ownerTeamName()));
        System.out.println("Exact target roster id: " + report.rosterId());
        System.out.println("Butler team id/name: " + report.butlerTeamId() + " / " + report.butlerTeamName());
        System.out.println("Persisted lineup slots: " + report.lineupSlots());
        System.out.println("Live starting slots: " + report.startingSlots());
        System.out.println("BF-609 candidates / evidence-reviewable: "
            + report.candidateCount() + "/" + report.reviewableCandidateCount());
        System.out.println("Target roster players starter/bench/reserve/taxi: "
            + report.targetPlayerCount() + " | " + report.starterCount() + "/"
            + report.benchCount() + "/" + report.reserveCount() + "/" + report.taxiCount());
        System.out.println("Target exact canonical mapped/unmapped: "
            + report.exactMappedTargetPlayers() + "/" + report.unmappedTargetPlayers());
        System.out.println("Exact target roster context (not a player ranking):");

        for (var player : report.targetPlayers()) {
            String starter = player.starterOrdinal() == null
                ? ""
                : " starterOrdinal=" + player.starterOrdinal() + " lineupSlot=" + value(player.lineupSlot());
            System.out.println("  " + player.sleeperPlayerId()
                + " | rosterSlot=" + player.rosterSlot()
                + starter
                + " | mapping=" + player.mappingState()
                + " | name=" + value(player.displayName())
                + " | pos=" + value(player.position())
                + " | nflTeam=" + value(player.nflTeam())
                + " | butlerPlayer=" + value(player.butlerPlayerId()));
        }

        System.out.println("Target-roster context state: READY_CONTEXT_ONLY");
        System.out.println();
        System.out.println("Boundary: BF-610 proves exact live target-roster context only after BF-623 verifies the persisted requesting-user account+league+roster binding. It does not score roster needs, identify a player to drop, rank waiver candidates, recommend an add/drop transaction, provide FAAB guidance, or emit confidence/probability/player-value claims.");
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
