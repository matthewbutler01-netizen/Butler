package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverFinalRecommendationBundle;

import java.nio.file.Path;

/** BF-618 through BF-620 final recommendation plus BF-624 cross-position selection, gated by BF-623 identity proof. */
public final class ButlerSleeperLiveWaiverFinalRecommendationBundleCli {
    private ButlerSleeperLiveWaiverFinalRecommendationBundleCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1
                || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperLiveWaiverFinalRecommendationBundle <butler-league-id>; exact Sleeper user/league/roster must be bound by BF-622");
            }
            String leagueId = args[0].trim();
            Database database = new Database(Path.of("butler.db"));
            database.initialize();
            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            ButlerPersonalizedTargetCliSupport.printVerified(target);
            print(new SleeperLiveWaiverFinalRecommendationBundle(database)
                .run(leagueId, target.sleeperUserId()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void print(SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport report) {
        System.out.println("Sleeper 2026 governed live waiver FINAL recommendation bundle (BF-618 through BF-620 + BF-624)");
        System.out.println("Butler league / BF-623 verified owner: " + report.leagueId() + " / " + report.sleeperOwnerId());
        System.out.println("Sleeper league / target roster: " + report.sleeperLeagueId() + " / " + report.rosterId());
        System.out.println("BF-603 market / BF-602 waiver snapshot: " + report.marketSnapshotId() + " / " + report.waiverSnapshotId());
        System.out.println("Final live provider season/status/leg: " + report.providerSeason() + "/"
            + report.providerStatus() + "/" + value(report.providerLeg()));
        System.out.println();

        System.out.println("BF-618 — final selection methodology");
        System.out.println("Policy: " + report.methodology().policyId());
        System.out.println("Historical / newcomer finalists: " + report.methodology().historicalFinalists()
            + "/" + report.methodology().newcomerFinalists());
        System.out.println("Historical finalist positions: " + report.methodology().historicalFinalistPositions());
        System.out.println("Add rule: " + report.methodology().addWinnerRule());
        System.out.println("Evidence rule: " + report.methodology().evidenceRule());
        System.out.println("Cross-position rule: " + report.methodology().crossPositionRule());
        System.out.println("BF-624 policy: " + SleeperLiveWaiverFinalRecommendationBundle.BF624_POLICY_ID);
        System.out.println("Newcomer rule: " + report.methodology().newcomerRule());
        System.out.println("Drop rule: " + report.methodology().dropRule());
        System.out.println("Protected-target rule: " + report.methodology().protectedTargetRule());
        System.out.println("BF-618 state: " + report.methodology().state());
        System.out.println();

        System.out.println("BF-619/BF-624 — exact add/drop selection");
        System.out.println("Selection state: " + report.selection().state());
        System.out.println("Direct governed comparisons evaluated: " + report.selection().directComparisons().size());
        if (report.selection().selectedAdd() != null) {
            System.out.println("Selected add: " + player(report.selection().selectedAdd()));
        }
        if (report.selection().selectedDrop() != null) {
            System.out.println("Selected drop: " + player(report.selection().selectedDrop()));
        }
        System.out.println();

        System.out.println("BF-620 — final live freshness + recommendation");
        System.out.println("Recommendation state: " + report.state());
        if (report.state() == SleeperLiveWaiverFinalRecommendationBundle.RecommendationState.RECOMMEND_ADD_DROP) {
            System.out.println("BUTLER RECOMMENDATION: ADD " + report.recommendedAdd().displayName()
                + " (Sleeper " + report.recommendedAdd().sleeperPlayerId() + ")"
                + " / DROP " + report.recommendedDrop().displayName()
                + " (Sleeper " + report.recommendedDrop().sleeperPlayerId() + ")");
            if (report.methodology().historicalFinalistPositions().size() > 1) {
                System.out.println("Reason: BF-624 produced the unique complete add/drop transaction whose governed supported-subtotal-per-game improvement is strictly greater on every compatible common evidence source. No position preference or hidden market/depth/injury tiebreaker was used.");
            } else {
                System.out.println("Reason: the add is the unique historical finalist directionally supported over every other same-position historical finalist under the frozen BF-614 evidence method, and the drop is the unique weakest production-backed exact-position BENCH/RESERVE comparator already directionally supported for replacement by BF-615.");
            }
        } else {
            System.out.println("BUTLER RECOMMENDATION: NO GOVERNED TRANSACTION YET");
            System.out.println("Reason: the governed final method did not produce one unique evidence-supported add/drop pair. Cross-position ties or incompatible evidence remain unresolved; Butler will not manufacture a tiebreaker.");
        }

        System.out.println("Newcomer-review alternatives remain nonnumeric and are NOT ranked against the recommendation:");
        if (report.newcomerReviewAlternatives().isEmpty()) {
            System.out.println("  none");
        } else {
            for (var value : report.newcomerReviewAlternatives()) {
                System.out.println("  " + player(value));
            }
        }
        System.out.println();
        System.out.println("Boundary: this is a read-only Butler add/drop recommendation emitted only after BF-623 re-verifies the persisted requesting-user account+league+roster binding. It does not submit a Sleeper transaction, set a FAAB bid, claim confidence/probability, or use position preference, market attention, depth, injury, names, or deterministic IDs as hidden football-value tiebreakers.");
    }

    private static String player(SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer value) {
        return value.sleeperPlayerId() + " | " + value.displayName()
            + " | pos=" + value.position()
            + " | role=" + value.role()
            + " | team=" + text(value.currentTeam())
            + " | status=" + text(value.currentStatus())
            + " | injury=" + text(value.injuryStatus())
            + " | depth=" + text(value.depthChartPosition()) + "/" + value(value.depthChartOrder());
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "none" : value.toString();
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
