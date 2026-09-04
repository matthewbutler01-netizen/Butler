package io.butler.bet.cli;

import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterCandidateSelectionPolicy;
import io.butler.bet.intelligence.TradeCounterMaterializedPackagePolicy;
import io.butler.bet.intelligence.TradeCounterOpportunityPolicy;
import io.butler.bet.intelligence.TradeCounterProposalEnvelopePolicy;
import io.butler.bet.intelligence.TradeCounterProposalPolicy;
import io.butler.bet.intelligence.TradeCounterSingleAssetCandidateAnalyzer;
import io.butler.bet.intelligence.TradeCounterValueTargetAnalyzer;
import io.butler.bet.intelligence.TradeFairnessPolicy;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterProposalMaterializedPackageCliTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void printsCompleteRevisedPackagesForCounter() throws Exception {
        var envelope = counterEnvelope();
        var materialized = TradeCounterMaterializedPackagePolicy.materialize(envelope);

        String output = capture(envelope, materialized);

        assertTrue(output.contains("Counter materialized package policy: "
            + TradeCounterMaterializedPackagePolicy.POLICY_ID));
        assertTrue(output.contains("Counter materialized package state: MATERIALIZED"));
        assertTrue(output.contains("Revised Side A package: players=[p1] picks=[k1]"));
        assertTrue(output.contains("Revised Side B package: players=[p2, p3] picks=[]"));
        assertTrue(output.contains("Complete counter packages materialized from the bound single-asset proposal."));
        assertTrue(output.contains("Read-only package snapshot only; Butler does not submit or mutate the trade."));
    }

    @Test
    void noActionPrintsNoRevisedPackages() throws Exception {
        var result = new TradeCounterProposalPolicy.Result(
            TradeCounterProposalPolicy.POLICY_ID,
            TradeCounterOpportunityPolicy.POLICY_ID,
            TradeCounterCandidateSelectionPolicy.POLICY_ID,
            "l1", 2026, "source", AS_OF,
            TradeCounterProposalPolicy.Action.NO_ACTION,
            TradeCounterProposalPolicy.ReasonCode.AMBIGUOUS_SELECTION,
            null);
        var envelope = TradeCounterProposalEnvelopePolicy.bind(
            result,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()));
        var materialized = TradeCounterMaterializedPackagePolicy.materialize(envelope);

        String output = capture(envelope, materialized);

        assertTrue(output.contains("Counter materialized package state: NO_PACKAGE"));
        assertTrue(output.contains("No revised counter packages are available."));
    }

    private static String capture(
        TradeCounterProposalEnvelopePolicy.Envelope envelope,
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized) throws Exception {
        Method method = ButlerTradeCounterProposalCli.class.getDeclaredMethod(
            "printMaterialized",
            TradeCounterProposalEnvelopePolicy.Envelope.class,
            TradeCounterMaterializedPackagePolicy.MaterializedCounter.class);
        method.setAccessible(true);

        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            method.invoke(null, envelope, materialized);
        } finally {
            System.setOut(original);
        }
        return bytes.toString();
    }

    private static TradeCounterProposalEnvelopePolicy.Envelope counterEnvelope() {
        var proposal = new TradeCounterProposalPolicy.Proposal(
            1,
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            "p3",
            "Player Three",
            "B",
            "Team B",
            5.0,
            AS_OF,
            4.0,
            1.0,
            100.0,
            104.0,
            3.921568627,
            TradeFairnessPolicy.Classification.MARKET_FAIR);
        var result = new TradeCounterProposalPolicy.Result(
            TradeCounterProposalPolicy.POLICY_ID,
            TradeCounterOpportunityPolicy.POLICY_ID,
            TradeCounterCandidateSelectionPolicy.POLICY_ID,
            "l1", 2026, "source", AS_OF,
            TradeCounterProposalPolicy.Action.COUNTER,
            TradeCounterProposalPolicy.ReasonCode.UNIQUE_SELECTED_CANDIDATE,
            proposal);
        return TradeCounterProposalEnvelopePolicy.bind(
            result,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of("k1")),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()));
    }
}
