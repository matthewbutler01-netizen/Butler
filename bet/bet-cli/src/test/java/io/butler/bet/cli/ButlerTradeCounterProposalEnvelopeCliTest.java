package io.butler.bet.cli;

import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterCandidateSelectionPolicy;
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

class ButlerTradeCounterProposalEnvelopeCliTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void printsBoundPerspectiveAndOriginalPackages() throws Exception {
        var options = new ButlerTradeCounterDecisionCli.Options(
            "l1",
            2026,
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of("k1")),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()),
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            "source",
            AS_OF);
        var proposal = counterResult();
        var envelope = TradeCounterProposalEnvelopePolicy.bind(
            proposal, options.perspective(), options.sideA(), options.sideB());

        String output = capture(options, proposal, envelope);

        assertTrue(output.contains("Counter proposal envelope policy: " + TradeCounterProposalEnvelopePolicy.POLICY_ID));
        assertTrue(output.contains("Envelope perspective: SIDE_A_TEAM"));
        assertTrue(output.contains("Original Side A package: players=[p1] picks=[k1]"));
        assertTrue(output.contains("Original Side B package: players=[p2] picks=[]"));
        assertTrue(output.contains("Proposal binding verified against original trade packages."));
    }

    private static String capture(
        ButlerTradeCounterDecisionCli.Options options,
        TradeCounterProposalPolicy.Result proposal,
        TradeCounterProposalEnvelopePolicy.Envelope envelope) throws Exception {
        Method method = ButlerTradeCounterProposalCli.class.getDeclaredMethod(
            "printEnvelope",
            ButlerTradeCounterDecisionCli.Options.class,
            TradeCounterProposalPolicy.Result.class,
            TradeCounterProposalEnvelopePolicy.Envelope.class);
        method.setAccessible(true);

        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            method.invoke(null, options, proposal, envelope);
        } finally {
            System.setOut(original);
        }
        return bytes.toString();
    }

    private static TradeCounterProposalPolicy.Result counterResult() {
        var counter = new TradeCounterProposalPolicy.Proposal(
            1,
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            "p3",
            "Player 3",
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
        return new TradeCounterProposalPolicy.Result(
            TradeCounterProposalPolicy.POLICY_ID,
            TradeCounterOpportunityPolicy.POLICY_ID,
            TradeCounterCandidateSelectionPolicy.POLICY_ID,
            "l1", 2026, "source", AS_OF,
            TradeCounterProposalPolicy.Action.COUNTER,
            TradeCounterProposalPolicy.ReasonCode.UNIQUE_SELECTED_CANDIDATE,
            counter);
    }
}
