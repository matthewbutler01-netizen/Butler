package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverTargetRosterProductionComparabilityAudit;

import java.nio.file.Path;

/** BF-611 operator surface, gated by BF-623 exact personalized target verification. */
public final class ButlerSleeperLiveWaiverTargetRosterProductionComparabilityAuditCli {
    private ButlerSleeperLiveWaiverTargetRosterProductionComparabilityAuditCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperLiveWaiverTargetRosterProductionComparabilityAudit <butler-league-id>; exact Sleeper user/league/roster must be bound by BF-622");
            }
            String leagueId = args[0].trim();
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            ButlerPersonalizedTargetCliSupport.printVerified(target);
            print(new SleeperLiveWaiverTargetRosterProductionComparabilityAudit(database)
                .audit(leagueId, target.sleeperUserId()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverTargetRosterProductionComparabilityAudit.AuditReport report) {
        System.out.println("Sleeper 2026 governed target-roster production comparability audit");
        System.out.println("Policy: " + report.policyId());
        System.out.println("BF-610 context policy: " + report.contextPolicyId());
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
        System.out.println("BF-609 candidates / evidence-reviewable: "
            + report.candidateCount() + "/" + report.reviewableCandidateCount());
        System.out.println("Production season audited: " + report.productionSeason());
        System.out.println("Target roster players starter/bench/reserve/taxi: "
            + report.targetPlayerCount() + " | " + report.starterCount() + "/"
            + report.benchCount() + "/" + report.reserveCount() + "/" + report.taxiCount());
        System.out.println("Target prior-production PRESENT / MISSING: "
            + report.priorProductionPresent() + "/" + report.priorProductionMissing());
        System.out.println("2025 production source coverage: " + report.sourceCoverage());
        System.out.println("Target roster production comparability (not a player ranking):");

        for (var coverage : report.players()) {
            var player = coverage.target();
            String starter = player.starterOrdinal() == null
                ? ""
                : " starterOrdinal=" + player.starterOrdinal() + " lineupSlot=" + value(player.lineupSlot());
            System.out.println("  " + player.sleeperPlayerId()
                + " | rosterSlot=" + player.rosterSlot()
                + starter
                + " | name=" + value(player.displayName())
                + " | pos=" + value(player.position())
                + " | nflTeam=" + value(player.nflTeam())
                + " | coverage=" + coverage.state()
                + " | butlerPlayer=" + value(player.butlerPlayerId()));
            for (var production : coverage.production()) {
                System.out.println("      production source=" + production.source()
                    + " as_of=" + production.asOfDate()
                    + " gp=" + production.gamesPlayed()
                    + " pass=" + production.passingYards() + "yd/" + production.passingTouchdowns() + "td"
                    + " rush=" + production.rushingYards() + "yd/" + production.rushingTouchdowns() + "td"
                    + " rec=" + production.receptions() + "/" + production.receivingYards() + "yd/"
                    + production.receivingTouchdowns() + "td");
            }
        }

        System.out.println("Target-roster production comparability state: AUDITED_READ_ONLY");
        System.out.println();
        System.out.println("Boundary: BF-611 measures governed 2025 production evidence coverage only after BF-623 verifies the persisted requesting-user account+league+roster binding. Missing 2025 production is an evidence gap, not a negative player grade. BF-611 does not score roster needs, rank players, pair add/drop candidates, recommend a transaction, provide FAAB guidance, or emit value/confidence/probability claims.");
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
