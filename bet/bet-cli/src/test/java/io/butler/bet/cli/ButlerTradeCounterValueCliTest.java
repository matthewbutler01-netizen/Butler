package io.butler.bet.cli;

import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterSingleAssetCandidateAnalyzer;
import io.butler.bet.intelligence.TradeCounterValueContextAnalyzer;
import io.butler.bet.intelligence.TradeCounterValueTargetAnalyzer;
import io.butler.bet.intelligence.TradeFairnessMeasurementPolicy;
import io.butler.bet.intelligence.TradeFairnessPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterValueCliTest {
    private static final LocalDate MINIMUM_AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void parsesMixedTradePackagesSourceAndFreshnessBoundary() {
        var options = ButlerTradeCounterValueCli.parse(new String[]{
            "trade", "counter-value", "l1",
            "player:p1,pick:k1", "p2,pick:k2",
            "dynastyprocess", "--minimum-as-of", "2026-09-01"});

        assertEquals("l1", options.leagueId());
        assertEquals(List.of("p1"), options.sideA().playerIds());
        assertEquals(List.of("k1"), options.sideA().draftPickIds());
        assertEquals(List.of("p2"), options.sideB().playerIds());
        assertEquals(List.of("k2"), options.sideB().draftPickIds());
        assertEquals("dynastyprocess", options.source());
        assertEquals(MINIMUM_AS_OF, options.minimumAsOf());
    }

    @Test
    void parsesFreshnessBoundaryWithoutSource() {
        var options = ButlerTradeCounterValueCli.parse(new String[]{
            "trade", "counter-value", "l1", "p1", "p2",
            "--minimum-as-of", "2026-09-01"});

        assertNull(options.source());
        assertEquals(MINIMUM_AS_OF, options.minimumAsOf());
    }

    @Test
    void printsGovernedTargetsAndRankedCandidatesWithoutRecommendationOrAction() {
        var trade = trade(side(105.0, false), side(95.0, false));
        var context = TradeCounterValueContextAnalyzer.compose(trade);
        var candidates = candidateReport(context);
        String output = capture(trade, context, candidates);

        assertTrue(output.contains("Trade counter-value evidence (asset-neutral market target)"));
        assertTrue(output.contains("Counter-value evidence available: true"));
        assertTrue(output.contains("Current market fairness: OUTSIDE_FAIRNESS_BAND"));
        assertTrue(output.contains("ADD_TO_LOWER_VALUE_PACKAGE SIDE_B"));
        assertTrue(output.contains("REMOVE_FROM_HIGHER_VALUE_PACKAGE SIDE_A"));
        assertTrue(output.contains("Single-asset candidate policy: "
            + TradeCounterSingleAssetCandidateAnalyzer.POLICY_ID));
        assertTrue(output.contains("Single-asset market-fair candidates: 1"));
        assertTrue(output.contains("#1 ADD_ASSET_TO_LOWER_PACKAGE SIDE_B PLAYER B Extra [b-extra-5]"));
        assertTrue(output.contains("fairness=MARKET_FAIR"));
        assertTrue(output.contains(
            "Ranking is evidence ordering only; no candidate is selected and no COUNTER action is emitted."));
        assertFalse(output.contains("Action:"));
        assertFalse(output.contains("Package recommendation:"));
    }

    @Test
    void printsAlreadyFairTradeWithoutSyntheticAdjustmentOrCandidate() {
        var trade = trade(side(102.0, false), side(100.0, false));
        var context = TradeCounterValueContextAnalyzer.compose(trade);
        var candidates = fairCandidateReport(context);
        String output = capture(trade, context, candidates);

        assertTrue(output.contains("Current market fairness: MARKET_FAIR"));
        assertTrue(output.contains(
            "Required market-value adjustment: none; the trade is already inside the governed fairness band."));
        assertTrue(output.contains("Single-asset market-fair candidates: 0"));
        assertTrue(output.contains(
            "No candidate adjustment is needed because the current trade is already MARKET_FAIR."));
        assertFalse(output.contains("ADD_TO_LOWER_VALUE_PACKAGE"));
        assertFalse(output.contains("REMOVE_FROM_HIGHER_VALUE_PACKAGE"));
    }

    @Test
    void unavailableEvidencePrintsTargetAndCandidateReasonsWithoutPartialResults() {
        var trade = trade(missingSide(), side(95.0, false));
        var context = TradeCounterValueContextAnalyzer.compose(trade);
        var candidates = unavailableCandidateReport(context);
        String output = capture(trade, context, candidates);

        assertTrue(output.contains("Counter-value evidence available: false"));
        assertTrue(output.contains("Trade counter value target requires complete market-value coverage."));
        assertTrue(output.contains("No partial or stale package total is used to construct a target."));
        assertTrue(output.contains("Single-asset candidate evidence available: false"));
        assertTrue(output.contains("Single-asset market-fair candidates: 0"));
        assertTrue(output.contains("No candidate is selected and no COUNTER action is emitted."));
        assertFalse(output.contains("Current symmetric market-value gap:"));
        assertFalse(output.contains("ADD_TO_LOWER_VALUE_PACKAGE"));
    }

    @Test
    void retainedTwoArgumentRendererStillPrintsTargetEvidenceOnly() {
        var trade = trade(side(105.0, false), side(95.0, false));
        var context = TradeCounterValueContextAnalyzer.compose(trade);
        String output = captureTargetOnly(trade, context);

        assertTrue(output.contains("Current market fairness: OUTSIDE_FAIRNESS_BAND"));
        assertFalse(output.contains("Single-asset candidate policy:"));
    }

    private static TradeCounterSingleAssetCandidateAnalyzer.CandidateReport candidateReport(
        TradeCounterValueContextAnalyzer.CounterValueContextReport context) {
        var target = context.target();
        var addTarget = target.options().stream()
            .filter(option -> option.type()
                == TradeCounterValueTargetAnalyzer.AdjustmentType.ADD_TO_LOWER_VALUE_PACKAGE)
            .findFirst().orElseThrow();
        double resultingGap = TradeFairnessMeasurementPolicy.symmetricGapPercent(105.0, 100.0);
        var candidate = new TradeCounterSingleAssetCandidateAnalyzer.Candidate(
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            "b-extra-5",
            "B Extra",
            "t2",
            "Team Two",
            5.0,
            MINIMUM_AS_OF,
            addTarget.requiredValueChange(),
            5.0 - addTarget.requiredValueChange(),
            105.0,
            100.0,
            resultingGap,
            TradeFairnessPolicy.Classification.MARKET_FAIR);
        return new TradeCounterSingleAssetCandidateAnalyzer.CandidateReport(
            TradeCounterSingleAssetCandidateAnalyzer.POLICY_ID,
            TradeCounterValueContextAnalyzer.POLICY_ID,
            io.butler.bet.intelligence.TradeCounterValueTargetAnalyzer.POLICY_ID,
            "l1", "source", MINIMUM_AS_OF, true, null,
            TradeFairnessPolicy.Classification.OUTSIDE_FAIRNESS_BAND,
            List.of(candidate));
    }

    private static TradeCounterSingleAssetCandidateAnalyzer.CandidateReport fairCandidateReport(
        TradeCounterValueContextAnalyzer.CounterValueContextReport context) {
        return new TradeCounterSingleAssetCandidateAnalyzer.CandidateReport(
            TradeCounterSingleAssetCandidateAnalyzer.POLICY_ID,
            context.policyId(),
            context.targetPolicyId(),
            "l1", "source", MINIMUM_AS_OF, true, null,
            TradeFairnessPolicy.Classification.MARKET_FAIR,
            List.of());
    }

    private static TradeCounterSingleAssetCandidateAnalyzer.CandidateReport unavailableCandidateReport(
        TradeCounterValueContextAnalyzer.CounterValueContextReport context) {
        return new TradeCounterSingleAssetCandidateAnalyzer.CandidateReport(
            TradeCounterSingleAssetCandidateAnalyzer.POLICY_ID,
            context.policyId(),
            context.targetPolicyId(),
            "l1", "source", MINIMUM_AS_OF, false,
            context.insufficiencyReason(), null, List.of());
    }

    private static String capture(
        TradeAssetAnalyzer.TradeReport trade,
        TradeCounterValueContextAnalyzer.CounterValueContextReport context,
        TradeCounterSingleAssetCandidateAnalyzer.CandidateReport candidates) {
        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerTradeCounterValueCli.print(trade, context, candidates);
        } finally {
            System.setOut(original);
        }
        return bytes.toString();
    }

    private static String captureTargetOnly(
        TradeAssetAnalyzer.TradeReport trade,
        TradeCounterValueContextAnalyzer.CounterValueContextReport context) {
        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            ButlerTradeCounterValueCli.print(trade, context);
        } finally {
            System.setOut(original);
        }
        return bytes.toString();
    }

    private static TradeAssetAnalyzer.TradeReport trade(
        TradeAssetAnalyzer.TradeSide sideA,
        TradeAssetAnalyzer.TradeSide sideB) {
        return new TradeAssetAnalyzer.TradeReport(
            "l1", "source", MINIMUM_AS_OF, sideA, sideB);
    }

    private static TradeAssetAnalyzer.TradeSide side(double value, boolean stale) {
        var player = new TradeAssetAnalyzer.TradePlayer(
            "p-" + value,
            "Player " + value,
            "WR",
            "NFL",
            value == 105.0 || value == 102.0 ? "t1" : "t2",
            value == 105.0 || value == 102.0 ? "Team One" : "Team Two",
            value,
            stale ? MINIMUM_AS_OF.minusDays(1) : MINIMUM_AS_OF,
            stale);
        return new TradeAssetAnalyzer.TradeSide(
            List.of(player), List.of(), value, 1, 0, 0, 0);
    }

    private static TradeAssetAnalyzer.TradeSide missingSide() {
        var player = new TradeAssetAnalyzer.TradePlayer(
            "missing", "Missing Player", "WR", "NFL", "t1", "Team One",
            null, null, false);
        return new TradeAssetAnalyzer.TradeSide(
            List.of(player), List.of(), 0.0, 0, 1, 0, 0);
    }
}
