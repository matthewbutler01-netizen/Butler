package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LiveWaiverSnapshotRepository;
import io.butler.bet.domain.PlayerSeasonProduction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SleeperLiveWaiverFinalRecommendationBundleTest {
    @TempDir Path tempDir;

    @Test
    void selectsUniqueHistoricalAddAndExactSupportedDrop() throws Exception {
        Database database = database();
        var bundle = bundleReport(false);
        var service = service(database, bundle, productionRows(1000, 500, 300), freshness(), snapshotEntries());

        var report = service.run("L", "O");

        assertEquals(SleeperLiveWaiverFinalRecommendationBundle.MethodologyState.FINAL_SELECTION_METHOD_FROZEN,
            report.methodology().state());
        assertEquals(SleeperLiveWaiverFinalRecommendationBundle.SelectionState.UNIQUE_ADD_DROP_SELECTED,
            report.selection().state());
        assertEquals(SleeperLiveWaiverFinalRecommendationBundle.RecommendationState.RECOMMEND_ADD_DROP,
            report.state());
        assertEquals("A", report.recommendedAdd().sleeperPlayerId());
        assertEquals("D", report.recommendedDrop().sleeperPlayerId());
        assertEquals(1, report.newcomerReviewAlternatives().size());
        assertEquals("N", report.newcomerReviewAlternatives().get(0).sleeperPlayerId());
    }

    @Test
    void tieBetweenHistoricalFinalistsDoesNotUseDeterministicIdAsTiebreaker() throws Exception {
        Database database = database();
        var service = service(database, bundleReport(false), productionRows(500, 500, 300), freshness(), snapshotEntries());

        var report = service.run("L", "O");

        assertEquals(SleeperLiveWaiverFinalRecommendationBundle.SelectionState.NO_UNIQUE_HISTORICAL_ADD,
            report.selection().state());
        assertEquals(SleeperLiveWaiverFinalRecommendationBundle.RecommendationState.NO_GOVERNED_TRANSACTION,
            report.state());
        assertNull(report.recommendedAdd());
        assertNull(report.recommendedDrop());
    }

    @Test
    void protectedMissingProductionRosterPlayerCannotBecomeDrop() throws Exception {
        Database database = database();
        var bundle = bundleReport(true);
        var service = service(database, bundle, productionRows(1000, 500, 0), freshness(), snapshotEntries());

        var report = service.run("L", "O");

        assertEquals(SleeperLiveWaiverFinalRecommendationBundle.SelectionState.NO_GOVERNED_DROP_FOR_SELECTED_ADD,
            report.selection().state());
        assertEquals(SleeperLiveWaiverFinalRecommendationBundle.RecommendationState.NO_GOVERNED_TRANSACTION,
            report.state());
        assertEquals("A", report.selection().selectedAdd().sleeperPlayerId());
        assertNull(report.selection().selectedDrop());
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("butler-test.db"));
        database.initialize();
        return database;
    }

    private static SleeperLiveWaiverFinalRecommendationBundle service(
        Database database,
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        Map<String, List<PlayerSeasonProduction>> production,
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport freshness,
        List<LiveWaiverSnapshotRepository.Entry> snapshotEntries) {
        return new SleeperLiveWaiverFinalRecommendationBundle(
            database,
            (league, owner) -> bundle,
            (league, owner) -> freshness,
            butlerId -> production.getOrDefault(butlerId, List.of()),
            snapshotId -> snapshotEntries);
    }

    private static SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundleReport(boolean protectedDrop) {
        var scoring = Map.of(
            "pass_yd", 0.0, "pass_td", 0.0, "rush_yd", 0.1, "rush_td", 0.0,
            "rec", 0.0, "rec_yd", 0.0, "rec_td", 0.0);
        var methodology = new SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport(
            SleeperLiveWaiverCandidateRosterComparisonMethodology.POLICY_ID,
            "L", "O", "M", "W", "S", 2026, "in_season", 1, 1,
            3, 3, 2, 1, 1, 0, 1, 0, 0, protectedDrop ? 0 : 1, protectedDrop ? 1 : 0,
            protectedDrop ? List.of(new SleeperLiveWaiverCandidateRosterComparisonMethodology.ProtectedMissingProduction(
                "D", "Drop", "RB", "BENCH")) : List.of(),
            scoring, Map.of(), Map.of(), List.of(), List.of("BENCH", "RESERVE"),
            "EXACT_POSITION_ONLY", "COMMON_2025_SOURCE_ONLY_ALL_COMMON_SOURCES_MUST_AGREE",
            "SUPPORTED_LEAGUE_SCORING_SUBTOTAL_PER_GAME_NOT_FULL_FANTASY_POINTS",
            "NEWCOMER_REVIEW_NONNUMERIC", "MISSING_TARGET_PRIOR_PRODUCTION_PROTECTED_UNRESOLVED",
            "MARKET_TEAM_STATUS_INJURY_DEPTH_DESCRIPTIVE_ONLY_NOT_ARITHMETIC",
            SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyState.METHODOLOGY_FROZEN_NO_SELECTION);

        var a = candidate("A", "bA", "Add A", true);
        var b = candidate("B", "bB", "Add B", true);
        var n = candidate("N", "bN", "Newcomer", false);
        var d = new SleeperLiveWaiverComparisonExecutionBundle.RosterEntry(
            "D", "Drop", "RB", "BENCH", "bD", !protectedDrop);

        var pairA = new SleeperLiveWaiverComparisonExecutionBundle.PairComparison(
            a, d,
            protectedDrop
                ? SleeperLiveWaiverComparisonExecutionBundle.PairState.TARGET_PRIOR_PRODUCTION_PROTECTED
                : SleeperLiveWaiverComparisonExecutionBundle.PairState.CANDIDATE_DIRECTIONALLY_SUPPORTED,
            List.of(), List.of(), List.of());
        var pairB = new SleeperLiveWaiverComparisonExecutionBundle.PairComparison(
            b, d,
            protectedDrop
                ? SleeperLiveWaiverComparisonExecutionBundle.PairState.TARGET_PRIOR_PRODUCTION_PROTECTED
                : SleeperLiveWaiverComparisonExecutionBundle.PairState.ROSTER_DIRECTIONALLY_SUPPORTED,
            List.of(), List.of(), List.of());
        var pairN = new SleeperLiveWaiverComparisonExecutionBundle.PairComparison(
            n, d, SleeperLiveWaiverComparisonExecutionBundle.PairState.NEWCOMER_NONNUMERIC,
            List.of(), List.of(), List.of());

        var ca = new SleeperLiveWaiverComparisonExecutionBundle.CandidateComparison(
            a, SleeperLiveWaiverComparisonExecutionBundle.CandidateExecutionState.HISTORICAL_PAIRWISE_COMPARISON_EXECUTED,
            List.of("D"), List.of(pairA));
        var cb = new SleeperLiveWaiverComparisonExecutionBundle.CandidateComparison(
            b, SleeperLiveWaiverComparisonExecutionBundle.CandidateExecutionState.HISTORICAL_PAIRWISE_COMPARISON_EXECUTED,
            List.of("D"), List.of(pairB));
        var cn = new SleeperLiveWaiverComparisonExecutionBundle.CandidateComparison(
            n, SleeperLiveWaiverComparisonExecutionBundle.CandidateExecutionState.NEWCOMER_REVIEW_EXECUTED_NONNUMERIC,
            List.of("D"), List.of(pairN));

        var pairCounts = protectedDrop
            ? new SleeperLiveWaiverComparisonExecutionBundle.PairCounts(0, 0, 0, 0, 0, 2, 1)
            : new SleeperLiveWaiverComparisonExecutionBundle.PairCounts(1, 1, 0, 0, 0, 0, 1);
        var comparisons = new SleeperLiveWaiverComparisonExecutionBundle.ComparisonReport(
            SleeperLiveWaiverComparisonExecutionBundle.BF615_POLICY_ID,
            "M", "W", "L", "O", "S", 1, 3, 3, 1, 3, pairCounts,
            List.of(ca, cb, cn), List.of(pairA, pairB, pairN),
            SleeperLiveWaiverComparisonExecutionBundle.ComparisonState.COMPARISONS_EXECUTED_EVIDENCE_ONLY);

        var histCounts = protectedDrop
            ? new SleeperLiveWaiverComparisonExecutionBundle.PairCounts(0, 0, 0, 0, 0, 1, 0)
            : new SleeperLiveWaiverComparisonExecutionBundle.PairCounts(1, 0, 0, 0, 0, 0, 0);
        var bCounts = protectedDrop
            ? histCounts
            : new SleeperLiveWaiverComparisonExecutionBundle.PairCounts(0, 1, 0, 0, 0, 0, 0);
        var nCounts = new SleeperLiveWaiverComparisonExecutionBundle.PairCounts(0, 0, 0, 0, 0, 0, 1);
        var da = new SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistDecision(
            a, SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistState.HISTORICAL_DIRECTIONAL_SHORTLIST,
            histCounts, List.of("D"), protectedDrop ? List.of() : List.of("D"));
        var db = new SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistDecision(
            b, SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistState.HISTORICAL_DIRECTIONAL_SHORTLIST,
            bCounts, List.of("D"), List.of());
        var dn = new SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistDecision(
            n, SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistState.NEWCOMER_REVIEW_SHORTLIST,
            nCounts, List.of("D"), List.of());
        var sa = new SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry(
            a, SleeperLiveWaiverComparisonExecutionBundle.ShortlistLane.HISTORICAL_DIRECTIONAL,
            protectedDrop ? List.of() : List.of("D"), List.of("D"), histCounts);
        var sb = new SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry(
            b, SleeperLiveWaiverComparisonExecutionBundle.ShortlistLane.HISTORICAL_DIRECTIONAL,
            List.of(), List.of("D"), bCounts);
        var sn = new SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry(
            n, SleeperLiveWaiverComparisonExecutionBundle.ShortlistLane.NEWCOMER_REVIEW,
            List.of(), List.of("D"), nCounts);
        var shortlist = new SleeperLiveWaiverComparisonExecutionBundle.ShortlistReport(
            SleeperLiveWaiverComparisonExecutionBundle.BF616_POLICY_ID,
            "M", "L", "O", 3, 2, 1,
            List.of(da, db, dn), List.of(sa, sb, sn),
            SleeperLiveWaiverComparisonExecutionBundle.ShortlistState.SHORTLIST_BUILT_EVIDENCE_ONLY);
        var readiness = new SleeperLiveWaiverComparisonExecutionBundle.DecisionReadinessReport(
            SleeperLiveWaiverComparisonExecutionBundle.BF617_POLICY_ID,
            "M", "L", "O", 3, 2, 1, List.of(sa, sb, sn),
            SleeperLiveWaiverComparisonExecutionBundle.FinalDecisionAuthorizationState.READY_FOR_FINAL_WAIVER_DECISION_METHOD);
        return new SleeperLiveWaiverComparisonExecutionBundle.BundleReport(methodology, comparisons, shortlist, readiness);
    }

    private static SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry candidate(
        String sleeperId, String butlerId, String name, boolean prior) {
        return new SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry(
            sleeperId, name, "RB", butlerId, prior, 1, 0, 1, "ADD_ONLY",
            "TM", "Active", null, "RB", 2);
    }

    private static Map<String, List<PlayerSeasonProduction>> productionRows(int aYards, int bYards, int dYards) {
        return Map.of(
            "bA", List.of(production("a", "bA", aYards)),
            "bB", List.of(production("b", "bB", bYards)),
            "bD", dYards <= 0 ? List.of() : List.of(production("d", "bD", dYards)));
    }

    private static PlayerSeasonProduction production(String id, String playerId, int rushYards) {
        return new PlayerSeasonProduction(
            id, playerId, 2025, 10, 0, 0, 0, rushYards, 0,
            0, 0, 0, 0, "nflverse", LocalDate.of(2026, 1, 10));
    }

    private static SleeperLiveWaiverTargetRosterContextAudit.AuditReport freshness() {
        var target = new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
            "D", "BENCH", null, null, "bD", "Drop", "RB", "TM", "EXACT_CANONICAL");
        return new SleeperLiveWaiverTargetRosterContextAudit.AuditReport(
            SleeperLiveWaiverTargetRosterContextAudit.POLICY_ID,
            "L", "M", "W", "S", 2026, "in_season", 1, "O", "Owner", "Team",
            1, "T", "Team", List.of("RB", "BN"), List.of("RB"), 3, 3,
            1, 0, 1, 0, 0, 1, 0, List.of(target));
    }

    private static List<LiveWaiverSnapshotRepository.Entry> snapshotEntries() {
        return List.of(
            new LiveWaiverSnapshotRepository.Entry("A", "Add A", "RB", List.of("RB"), "TM", "Active",
                false, true, true, "LEAGUE_ELIGIBLE_POSITION_MATCH"),
            new LiveWaiverSnapshotRepository.Entry("D", "Drop", "RB", List.of("RB"), "TM", "Active",
                true, false, false, "ROSTERED"));
    }
}
