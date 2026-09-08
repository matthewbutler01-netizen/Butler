package io.butler.bet.sleeper;

import io.butler.bet.domain.PlayerSeasonProduction;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SleeperLiveWaiverCrossPositionTransactionSelectorTest {
    @Test
    void selectsUniqueCrossPositionTransactionImprovementWinner() throws Exception {
        var bundle = bundleReport();
        var production = productionRows("nflverse", "nflverse", 1000, 400, 800, 500);
        var selector = new SleeperLiveWaiverCrossPositionTransactionSelector(
            butlerId -> production.getOrDefault(butlerId, List.of()));

        var result = selector.select(bundle, bundle.shortlist().shortlist());

        assertEquals(SleeperLiveWaiverFinalRecommendationBundle.SelectionState.UNIQUE_ADD_DROP_SELECTED,
            result.state());
        assertEquals("A", result.selectedAdd().sleeperPlayerId());
        assertEquals("D1", result.selectedDrop().sleeperPlayerId());
        assertEquals(2, result.options().size());
        assertEquals(6.0d, result.options().stream()
            .filter(value -> value.add().sleeperPlayerId().equals("A"))
            .findFirst().orElseThrow().improvementBySource().get("nflverse"), 0.000001d);
    }

    @Test
    void equalCrossPositionTransactionImprovementRemainsUnresolved() throws Exception {
        var bundle = bundleReport();
        var production = productionRows("nflverse", "nflverse", 1000, 400, 1100, 500);
        var selector = new SleeperLiveWaiverCrossPositionTransactionSelector(
            butlerId -> production.getOrDefault(butlerId, List.of()));

        var result = selector.select(bundle, bundle.shortlist().shortlist());

        assertEquals(
            SleeperLiveWaiverFinalRecommendationBundle.SelectionState.CROSS_POSITION_TRANSACTION_IMPROVEMENT_UNRESOLVED,
            result.state());
        assertNull(result.selectedAdd());
        assertNull(result.selectedDrop());
    }

    @Test
    void incompatibleTransactionSourceLineageRemainsUnresolved() throws Exception {
        var bundle = bundleReport();
        var production = productionRows("nflverse", "provider-two", 1000, 400, 800, 500);
        var selector = new SleeperLiveWaiverCrossPositionTransactionSelector(
            butlerId -> production.getOrDefault(butlerId, List.of()));

        var result = selector.select(bundle, bundle.shortlist().shortlist());

        assertEquals(
            SleeperLiveWaiverFinalRecommendationBundle.SelectionState.CROSS_POSITION_TRANSACTION_EVIDENCE_INCOMPATIBLE,
            result.state());
        assertNull(result.selectedAdd());
        assertNull(result.selectedDrop());
    }

    private static SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundleReport() {
        var scoring = Map.of(
            "pass_yd", 0.0, "pass_td", 0.0, "rush_yd", 0.1, "rush_td", 0.0,
            "rec", 0.0, "rec_yd", 0.1, "rec_td", 0.0);
        var methodology = new SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyReport(
            SleeperLiveWaiverCandidateRosterComparisonMethodology.POLICY_ID,
            "L", "O", "M", "W", "S", 2026, "in_season", 1, 1,
            2, 2, 2, 0, 0, 0, 2, 0, 0, 2, 0, List.of(),
            scoring, Map.of(), Map.of(), List.of(), List.of("BENCH", "RESERVE"),
            "EXACT_POSITION_ONLY", "COMMON_2025_SOURCE_ONLY_ALL_COMMON_SOURCES_MUST_AGREE",
            "SUPPORTED_LEAGUE_SCORING_SUBTOTAL_PER_GAME_NOT_FULL_FANTASY_POINTS",
            "NEWCOMER_REVIEW_NONNUMERIC", "MISSING_TARGET_PRIOR_PRODUCTION_PROTECTED_UNRESOLVED",
            "MARKET_TEAM_STATUS_INJURY_DEPTH_DESCRIPTIVE_ONLY_NOT_ARITHMETIC",
            SleeperLiveWaiverCandidateRosterComparisonMethodology.MethodologyState.METHODOLOGY_FROZEN_NO_SELECTION);

        var rb = candidate("A", "bA", "RB Add", "RB");
        var wr = candidate("C", "bC", "WR Add", "WR");
        var rbDrop = roster("D1", "bD1", "RB Drop", "RB");
        var wrDrop = roster("D2", "bD2", "WR Drop", "WR");

        var rbPair = pair(rb, rbDrop);
        var wrPair = pair(wr, wrDrop);
        var rbExecution = new SleeperLiveWaiverComparisonExecutionBundle.CandidateComparison(
            rb, SleeperLiveWaiverComparisonExecutionBundle.CandidateExecutionState.HISTORICAL_PAIRWISE_COMPARISON_EXECUTED,
            List.of("D1"), List.of(rbPair));
        var wrExecution = new SleeperLiveWaiverComparisonExecutionBundle.CandidateComparison(
            wr, SleeperLiveWaiverComparisonExecutionBundle.CandidateExecutionState.HISTORICAL_PAIRWISE_COMPARISON_EXECUTED,
            List.of("D2"), List.of(wrPair));
        var pairCounts = new SleeperLiveWaiverComparisonExecutionBundle.PairCounts(2, 0, 0, 0, 0, 0, 0);
        var comparisons = new SleeperLiveWaiverComparisonExecutionBundle.ComparisonReport(
            SleeperLiveWaiverComparisonExecutionBundle.BF615_POLICY_ID,
            "M", "W", "L", "O", "S", 1, 2, 2, 2, 2, pairCounts,
            List.of(rbExecution, wrExecution), List.of(rbPair, wrPair),
            SleeperLiveWaiverComparisonExecutionBundle.ComparisonState.COMPARISONS_EXECUTED_EVIDENCE_ONLY);

        var oneSupported = new SleeperLiveWaiverComparisonExecutionBundle.PairCounts(1, 0, 0, 0, 0, 0, 0);
        var rbDecision = new SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistDecision(
            rb, SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistState.HISTORICAL_DIRECTIONAL_SHORTLIST,
            oneSupported, List.of("D1"), List.of("D1"));
        var wrDecision = new SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistDecision(
            wr, SleeperLiveWaiverComparisonExecutionBundle.CandidateShortlistState.HISTORICAL_DIRECTIONAL_SHORTLIST,
            oneSupported, List.of("D2"), List.of("D2"));
        var rbShortlist = new SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry(
            rb, SleeperLiveWaiverComparisonExecutionBundle.ShortlistLane.HISTORICAL_DIRECTIONAL,
            List.of("D1"), List.of("D1"), oneSupported);
        var wrShortlist = new SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry(
            wr, SleeperLiveWaiverComparisonExecutionBundle.ShortlistLane.HISTORICAL_DIRECTIONAL,
            List.of("D2"), List.of("D2"), oneSupported);
        var shortlist = new SleeperLiveWaiverComparisonExecutionBundle.ShortlistReport(
            SleeperLiveWaiverComparisonExecutionBundle.BF616_POLICY_ID,
            "M", "L", "O", 2, 2, 0,
            List.of(rbDecision, wrDecision), List.of(rbShortlist, wrShortlist),
            SleeperLiveWaiverComparisonExecutionBundle.ShortlistState.SHORTLIST_BUILT_EVIDENCE_ONLY);
        var readiness = new SleeperLiveWaiverComparisonExecutionBundle.DecisionReadinessReport(
            SleeperLiveWaiverComparisonExecutionBundle.BF617_POLICY_ID,
            "M", "L", "O", 2, 2, 0, List.of(rbShortlist, wrShortlist),
            SleeperLiveWaiverComparisonExecutionBundle.FinalDecisionAuthorizationState.READY_FOR_FINAL_WAIVER_DECISION_METHOD);
        return new SleeperLiveWaiverComparisonExecutionBundle.BundleReport(methodology, comparisons, shortlist, readiness);
    }

    private static SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry candidate(
        String sleeperId, String butlerId, String name, String position) {
        return new SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry(
            sleeperId, name, position, butlerId, true, 1, 0, 1, "ADD_ONLY",
            "TM", "Active", null, position, 2);
    }

    private static SleeperLiveWaiverComparisonExecutionBundle.RosterEntry roster(
        String sleeperId, String butlerId, String name, String position) {
        return new SleeperLiveWaiverComparisonExecutionBundle.RosterEntry(
            sleeperId, name, position, "BENCH", butlerId, true);
    }

    private static SleeperLiveWaiverComparisonExecutionBundle.PairComparison pair(
        SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry candidate,
        SleeperLiveWaiverComparisonExecutionBundle.RosterEntry roster) {
        return new SleeperLiveWaiverComparisonExecutionBundle.PairComparison(
            candidate, roster,
            SleeperLiveWaiverComparisonExecutionBundle.PairState.CANDIDATE_DIRECTIONALLY_SUPPORTED,
            List.of(), List.of(), List.of());
    }

    private static Map<String, List<PlayerSeasonProduction>> productionRows(
        String rbSource,
        String wrSource,
        int rbAddYards,
        int rbDropYards,
        int wrAddYards,
        int wrDropYards) {
        return Map.of(
            "bA", List.of(production("a", "bA", rbAddYards, 0, rbSource)),
            "bD1", List.of(production("d1", "bD1", rbDropYards, 0, rbSource)),
            "bC", List.of(production("c", "bC", 0, wrAddYards, wrSource)),
            "bD2", List.of(production("d2", "bD2", 0, wrDropYards, wrSource)));
    }

    private static PlayerSeasonProduction production(
        String id,
        String playerId,
        int rushYards,
        int receivingYards,
        String source) {
        return new PlayerSeasonProduction(
            id, playerId, 2025, 10, 0, 0, 0, rushYards, 0,
            0, receivingYards, 0, 0, source, LocalDate.of(2026, 1, 10));
    }
}
