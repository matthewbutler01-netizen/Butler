package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverComparisonExecutionBundle;

import java.nio.file.Path;
import java.util.EnumMap;

/** One-command BF-615 through BF-617 operator surface, gated by BF-623 personalized identity proof. */
public final class ButlerSleeperLiveWaiverComparisonBundleCli {
    private ButlerSleeperLiveWaiverComparisonBundleCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1
                || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperLiveWaiverComparisonBundle <butler-league-id>; exact Sleeper user/league/roster must be bound by BF-622");
            }
            String leagueId = args[0].trim();
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            ButlerPersonalizedTargetCliSupport.printVerified(target);
            print(new SleeperLiveWaiverComparisonExecutionBundle(database)
                .run(leagueId, target.sleeperUserId()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverComparisonExecutionBundle.BundleReport report) {
        var methodology = report.methodology();
        var comparisons = report.comparisons();
        var shortlist = report.shortlist();
        var readiness = report.decisionReadiness();

        System.out.println("Sleeper 2026 governed live waiver comparison bundle (BF-615 through BF-617)");
        System.out.println("Butler league / BF-623 verified owner: " + comparisons.leagueId() + " / " + comparisons.sleeperOwnerId());
        System.out.println("Sleeper league / target roster: " + comparisons.sleeperLeagueId() + " / " + comparisons.rosterId());
        System.out.println("BF-603 market / BF-602 waiver snapshot: " + comparisons.marketSnapshotId()
            + " / " + comparisons.waiverSnapshotId());
        System.out.println("BF-614 methodology: " + methodology.state());
        System.out.println("Numeric evidence: supported league-scoring subtotal per game only; not full fantasy points.");
        System.out.println("Replacement pool: exact-position BENCH/RESERVE only.");
        System.out.println();

        System.out.println("BF-615 — governed pairwise comparison execution");
        System.out.println("Policy: " + comparisons.policyId());
        System.out.println("Candidates total / reviewable: " + comparisons.candidateCount() + "/" + comparisons.reviewableCandidateCount());
        System.out.println("Exact BENCH/RESERVE replacement pool: " + comparisons.replacementPoolCount());
        System.out.println("Executed exact-position pairs: " + comparisons.pairCount());
        var pc = comparisons.pairCounts();
        System.out.println("Pair states candidate-supported / roster-supported / tied / source-unresolved / no-common-source / protected-target-missing / newcomer-nonnumeric: "
            + pc.candidateDirectionallySupported() + "/" + pc.rosterDirectionallySupported() + "/"
            + pc.tiedAllCommonSources() + "/" + pc.sourceDirectionUnresolved() + "/"
            + pc.noCommonSource() + "/" + pc.targetPriorProductionProtected() + "/" + pc.newcomerNonnumeric());
        System.out.println("BF-615 state: " + comparisons.state());
        System.out.println();

        System.out.println("BF-616 — governed shortlist evidence surface");
        System.out.println("Policy: " + shortlist.policyId());
        EnumMap<SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistState, Integer> decisionCounts =
            new EnumMap<>(SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistState.class);
        for (var state : SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistState.values()) {
            decisionCounts.put(state, 0);
        }
        for (var decision : shortlist.decisions()) {
            decisionCounts.compute(decision.state(), (ignored, value) -> value + 1);
        }
        decisionCounts.forEach((state, count) -> System.out.println("  " + state + "=" + count));
        System.out.println("Historical directional shortlist / newcomer review shortlist: "
            + shortlist.historicalShortlistCount() + "/" + shortlist.newcomerShortlistCount());
        System.out.println("Shortlist entries (deterministic display order only; this is NOT a rank):");
        if (shortlist.shortlist().isEmpty()) {
            System.out.println("  none");
        } else {
            for (var entry : shortlist.shortlist()) {
                var c = entry.candidate();
                System.out.println("  " + c.sleeperPlayerId() + " | " + c.displayName()
                    + " | pos=" + c.position()
                    + " | lane=" + entry.lane()
                    + " | team=" + value(c.currentTeam())
                    + " | status=" + value(c.currentStatus())
                    + " | injury=" + value(c.injuryStatus())
                    + " | depth=" + value(c.depthChartPosition()) + "/" + value(c.depthChartOrder())
                    + " | market add/drop/net=" + c.addCount() + "/" + c.dropCount() + "/" + c.netAddAttention()
                    + " | candidate-supported-comparators=" + entry.candidateSupportedComparatorSleeperIds()
                    + " | eligible-comparators=" + entry.eligibleComparatorSleeperIds());
            }
        }
        System.out.println("BF-616 state: " + shortlist.state());
        System.out.println();

        System.out.println("BF-617 — final waiver decision-method readiness");
        System.out.println("Policy: " + readiness.policyId());
        System.out.println("Authorized shortlist total / historical / newcomer: "
            + readiness.shortlistCount() + "/" + readiness.historicalShortlistCount() + "/" + readiness.newcomerShortlistCount());
        System.out.println("BF-617 state: " + readiness.state());
        System.out.println();
        System.out.println("Boundary: BF-615–617 executes the frozen comparison methodology only after BF-623 verifies the persisted requesting-user account+league+roster binding. It does not rank the shortlist, select a winner, identify a final drop, recommend an add/drop transaction, provide FAAB guidance, or emit confidence/probability/player-value/manager-evaluation claims. Market attention, team/status, injury, and depth remain descriptive only.");
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }
}
