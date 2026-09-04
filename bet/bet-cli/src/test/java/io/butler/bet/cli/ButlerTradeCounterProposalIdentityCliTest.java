package io.butler.bet.cli;

import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterCandidateSelectionPolicy;
import io.butler.bet.intelligence.TradeCounterMaterializedPackagePolicy;
import io.butler.bet.intelligence.TradeCounterOpportunityPolicy;
import io.butler.bet.intelligence.TradeCounterProposalEnvelopePolicy;
import io.butler.bet.intelligence.TradeCounterProposalIdentityPolicy;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeCounterProposalIdentityCliTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);
    private static final String FIXTURE_FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";

    @Test
    void printsAuditFingerprintWithoutCallingItAuthorization() throws Exception {
        var envelope = counterEnvelope();
        var materialized = TradeCounterMaterializedPackagePolicy.materialize(envelope);
        var identity = TradeCounterProposalIdentityPolicy.identify(envelope, materialized);

        String output = capture(envelope, materialized, identity);

        assertTrue(output.contains("Counter proposal identity policy: "
            + TradeCounterProposalIdentityPolicy.POLICY_ID));
        assertTrue(output.contains("Counter proposal identity state: IDENTIFIED"));
        assertTrue(output.contains("Counter proposal identity algorithm: SHA-256 canonical-version=1"));
        assertTrue(output.contains("Counter proposal fingerprint: " + FIXTURE_FINGERPRINT));
        assertTrue(output.contains(
            "Fingerprint is audit identity only; it is not authorization to send or execute the trade."));
    }

    @Test
    void noActionPrintsNoFingerprint() throws Exception {
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
            new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of("k1")),
            new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of()));
        var materialized = TradeCounterMaterializedPackagePolicy.materialize(envelope);
        var identity = TradeCounterProposalIdentityPolicy.identify(envelope, materialized);

        String output = capture(envelope, materialized, identity);

        assertTrue(output.contains("Counter proposal identity state: NO_IDENTITY"));
        assertTrue(output.contains("No counter proposal fingerprint is available."));
        assertFalse(output.contains("Counter proposal fingerprint:"));
    }

    private static String capture(
        TradeCounterProposalEnvelopePolicy.Envelope envelope,
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized,
        TradeCounterProposalIdentityPolicy.Identity identity) throws Exception {
        Method method = ButlerTradeCounterProposalCli.class.getDeclaredMethod(
            "printIdentity",
            TradeCounterProposalEnvelopePolicy.Envelope.class,
            TradeCounterMaterializedPackagePolicy.MaterializedCounter.class,
            TradeCounterProposalIdentityPolicy.Identity.class);
        method.setAccessible(true);

        PrintStream original = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            method.invoke(null, envelope, materialized, identity);
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
