package io.butler.bet.cli;

import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterCandidateSelectionPolicy;
import io.butler.bet.intelligence.TradeCounterNegotiationMessagePolicy;
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

class ButlerTradeCounterProposalNegotiationMessageCliTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void printsGovernedMessageWithoutClaimingItWasSent() throws Exception {
        var envelope = counterEnvelope();
        var message = TradeCounterNegotiationMessagePolicy.compose(envelope);

        String output = capture(envelope, message);

        assertTrue(output.contains("Counter negotiation message policy: "
            + TradeCounterNegotiationMessagePolicy.POLICY_ID));
        assertTrue(output.contains("Counter negotiation message state: MESSAGE_AVAILABLE"));
        assertTrue(output.contains("Counter negotiation actor: OTHER_MANAGER"));
        assertTrue(output.contains(
            "Negotiation message: I'd counter if you add Player Three to your side of the deal."));
        assertTrue(output.contains("Read-only wording only; Butler does not send this message."));
    }

    @Test
    void noActionPrintsNoMessage() throws Exception {
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
        var message = TradeCounterNegotiationMessagePolicy.compose(envelope);

        String output = capture(envelope, message);

        assertTrue(output.contains("Counter negotiation message state: NO_MESSAGE"));
        assertTrue(output.contains("No negotiation message is available."));
    }

    private static String capture(
        TradeCounterProposalEnvelopePolicy.Envelope envelope,
        TradeCounterNegotiationMessagePolicy.MessageResult message) throws Exception {
        Method method = ButlerTradeCounterProposalCli.class.getDeclaredMethod(
            "printMessage",
            TradeCounterProposalEnvelopePolicy.Envelope.class,
            TradeCounterNegotiationMessagePolicy.MessageResult.class);
        method.setAccessible(true);

        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            method.invoke(null, envelope, message);
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
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of()),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()));
    }
}
