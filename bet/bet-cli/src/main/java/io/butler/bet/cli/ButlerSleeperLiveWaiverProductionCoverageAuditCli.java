package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverProductionCoverageAudit;

import java.nio.file.Path;

/** BF-604 operator surface for read-only market-active prior-season production coverage. */
public final class ButlerSleeperLiveWaiverProductionCoverageAuditCli {
    private ButlerSleeperLiveWaiverProductionCoverageAuditCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException("Usage: sleeperLiveWaiverProductionCoverageAudit <butler-league-id>");
            }
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            print(new SleeperLiveWaiverProductionCoverageAudit(database).audit(args[0].trim()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverProductionCoverageAudit.AuditReport report) {
        System.out.println("Sleeper 2026 live waiver market-active production coverage audit");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Butler league: " + report.leagueId());
        System.out.println("Referenced BF-603 market snapshot: " + report.marketSnapshotId());
        System.out.println("BF-603 observed at UTC: " + report.marketObservedAtUtc());
        System.out.println("Production season audited: " + report.productionSeason());
        System.out.println("Market-active candidates: " + report.marketActiveCandidates());
        System.out.println("Coverage UNMAPPED_CANONICAL / MAPPED_NO_2025_PRODUCTION / MAPPED_WITH_2025_PRODUCTION: "
            + report.unmappedCanonical() + "/" + report.mappedNoProduction() + "/" + report.mappedWithProduction());
        System.out.println("2025 production source coverage: " + report.sourceCoverage());
        System.out.println("Market-active candidate coverage:");
        for (var candidate : report.candidates()) {
            var market = candidate.market();
            System.out.println("  " + market.sleeperPlayerId()
                + " | " + market.displayName()
                + " | pos=" + value(market.position())
                + " | team=" + value(market.nflTeam())
                + " | status=" + value(market.providerStatus())
                + " | add=" + market.addCount()
                + " | drop=" + market.dropCount()
                + " | net=" + market.netAddAttention()
                + " | frame=" + market.frameMembership()
                + " | coverage=" + candidate.state()
                + " | butler_player=" + value(candidate.butlerPlayerId()));
            for (var production : candidate.production()) {
                System.out.println("      production source=" + production.source()
                    + " as_of=" + production.asOfDate()
                    + " gp=" + production.gamesPlayed()
                    + " pass=" + production.passingYards() + "yd/" + production.passingTouchdowns() + "td"
                    + " rush=" + production.rushingYards() + "yd/" + production.rushingTouchdowns() + "td"
                    + " rec=" + production.receptions() + "/" + production.receivingYards() + "yd/"
                    + production.receivingTouchdowns() + "td");
            }
        }
        System.out.println("Coverage audit state: AUDITED_READ_ONLY");
        System.out.println();
        System.out.println("Boundary: BF-604 measures exact canonical identity and governed 2025 production coverage for the BF-603 market-active frame. It does not rank candidates, infer 2026 value from 2025 production, assign FAAB, recommend adds/drops, select a winner, evaluate managers, or emit confidence/probability claims.");
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
