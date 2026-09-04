package io.butler.bet.cli;

import io.butler.bet.intelligence.TradeCounterProposalPolicy;
import io.butler.bet.intelligence.TradeCounterSingleAssetCandidateAnalyzer;
import io.butler.bet.intelligence.TradeCounterValueTargetAnalyzer;
import io.butler.bet.intelligence.TradeFairnessPolicy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerTradeCounterProposalFormattingTest {
    @Test
    void formatsAddProposalAsExplicitSideOperation() throws Exception {
        assertEquals(
            "ADD PLAYER Player X [px] TO SIDE_B",
            format(proposal(
                TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
                TradeCounterValueTargetAnalyzer.Side.SIDE_B,
                TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
                "px", "Player X")));
    }

    @Test
    void formatsRemoveProposalAsExplicitSideOperation() throws Exception {
        assertEquals(
            "REMOVE DRAFT_PICK 2027 1st [k1] FROM SIDE_A",
            format(proposal(
                TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.REMOVE_ASSET_FROM_HIGHER_PACKAGE,
                TradeCounterValueTargetAnalyzer.Side.SIDE_A,
                TradeCounterSingleAssetCandidateAnalyzer.AssetType.DRAFT_PICK,
                "k1", "2027 1st")));
    }

    private static String format(TradeCounterProposalPolicy.Proposal proposal) throws Exception {
        Method method = ButlerTradeCounterProposalCli.class.getDeclaredMethod(
            "formatAdjustment", TradeCounterProposalPolicy.Proposal.class);
        method.setAccessible(true);
        return (String) method.invoke(null, proposal);
    }

    private static TradeCounterProposalPolicy.Proposal proposal(
        TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType adjustment,
        TradeCounterValueTargetAnalyzer.Side side,
        TradeCounterSingleAssetCandidateAnalyzer.AssetType assetType,
        String assetId,
        String displayName) {
        return new TradeCounterProposalPolicy.Proposal(
            1,
            adjustment,
            side,
            assetType,
            assetId,
            displayName,
            "team-1",
            "Team 1",
            5.0,
            LocalDate.of(2026, 9, 1),
            4.0,
            1.0,
            100.0,
            104.0,
            3.921568627,
            TradeFairnessPolicy.Classification.MARKET_FAIR);
    }
}
