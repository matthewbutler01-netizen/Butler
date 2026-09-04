package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeCounterProposalIdentityPolicyTest {
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);
    private static final String FIXTURE_FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";

    @Test
    void locksCanonicalSha256Fixture() {
        var envelope = counterEnvelope("Player Three", "Team B", "p3", 5.0);
        var materialized = TradeCounterMaterializedPackagePolicy.materialize(envelope);

        var identity = TradeCounterProposalIdentityPolicy.identify(envelope, materialized);

        assertEquals(TradeCounterProposalIdentityPolicy.State.IDENTIFIED, identity.state());
        assertEquals(FIXTURE_FINGERPRINT, identity.fingerprint());
        assertEquals("SHA-256", identity.algorithm());
        assertEquals("1", identity.canonicalVersion());
    }

    @Test
    void cosmeticDisplayNamesDoNotChangeProposalIdentity() {
        var first = counterEnvelope("Player Three", "Team B", "p3", 5.0);
        var renamed = counterEnvelope("Renamed Player", "Renamed Team", "p3", 5.0);

        var firstIdentity = TradeCounterProposalIdentityPolicy.identify(
            first, TradeCounterMaterializedPackagePolicy.materialize(first));
        var renamedIdentity = TradeCounterProposalIdentityPolicy.identify(
            renamed, TradeCounterMaterializedPackagePolicy.materialize(renamed));

        assertEquals(firstIdentity.fingerprint(), renamedIdentity.fingerprint());
    }

    @Test
    void substantiveAssetOrEvidenceChangeChangesIdentity() {
        var first = counterEnvelope("Player Three", "Team B", "p3", 5.0);
        var different = counterEnvelope("Player Four", "Team B", "p4", 6.0);

        var firstIdentity = TradeCounterProposalIdentityPolicy.identify(
            first, TradeCounterMaterializedPackagePolicy.materialize(first));
        var differentIdentity = TradeCounterProposalIdentityPolicy.identify(
            different, TradeCounterMaterializedPackagePolicy.materialize(different));

        assertNotEquals(firstIdentity.fingerprint(), differentIdentity.fingerprint());
    }

    @Test
    void perspectiveChangeChangesIdentity() {
        var first = counterEnvelope("Player Three", "Team B", "p3", 5.0);
        var oppositePerspective = TradeCounterProposalEnvelopePolicy.bind(
            counterResult("Player Three", "Team B", "p3", 5.0),
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_B_TEAM,
            originalSideA(), originalSideB());

        var firstIdentity = TradeCounterProposalIdentityPolicy.identify(
            first, TradeCounterMaterializedPackagePolicy.materialize(first));
        var oppositeIdentity = TradeCounterProposalIdentityPolicy.identify(
            oppositePerspective,
            TradeCounterMaterializedPackagePolicy.materialize(oppositePerspective));

        assertNotEquals(firstIdentity.fingerprint(), oppositeIdentity.fingerprint());
    }

    @Test
    void noActionAndInconclusiveDoNotReceiveFingerprint() {
        var noAction = nonCounterEnvelope(
            TradeCounterProposalPolicy.Action.NO_ACTION,
            TradeCounterProposalPolicy.ReasonCode.AMBIGUOUS_SELECTION);
        var noActionIdentity = TradeCounterProposalIdentityPolicy.identify(
            noAction, TradeCounterMaterializedPackagePolicy.materialize(noAction));
        assertEquals(TradeCounterProposalIdentityPolicy.State.NO_IDENTITY, noActionIdentity.state());
        assertNull(noActionIdentity.fingerprint());

        var inconclusive = nonCounterEnvelope(
            TradeCounterProposalPolicy.Action.INCONCLUSIVE,
            TradeCounterProposalPolicy.ReasonCode.COUNTER_DECISION_INCONCLUSIVE);
        var inconclusiveIdentity = TradeCounterProposalIdentityPolicy.identify(
            inconclusive, TradeCounterMaterializedPackagePolicy.materialize(inconclusive));
        assertEquals(TradeCounterProposalIdentityPolicy.State.INCONCLUSIVE, inconclusiveIdentity.state());
        assertNull(inconclusiveIdentity.fingerprint());
    }

    @Test
    void rejectsMaterializedArtifactFromDifferentCoordinates() {
        var envelope = counterEnvelope("Player Three", "Team B", "p3", 5.0);
        var valid = TradeCounterMaterializedPackagePolicy.materialize(envelope);
        var mismatched = new TradeCounterMaterializedPackagePolicy.MaterializedCounter(
            TradeCounterMaterializedPackagePolicy.POLICY_ID,
            TradeCounterProposalEnvelopePolicy.POLICY_ID,
            TradeCounterProposalPolicy.POLICY_ID,
            "different-league",
            valid.season(),
            valid.source(),
            valid.minimumAsOfDate(),
            valid.perspective(),
            valid.state(),
            valid.reasonCode(),
            valid.originalSideA(),
            valid.originalSideB(),
            valid.revisedSideA(),
            valid.revisedSideB());

        assertThrows(IllegalArgumentException.class,
            () -> TradeCounterProposalIdentityPolicy.identify(envelope, mismatched));
    }

    @Test
    void preservesPolicyAndCoordinateProvenance() {
        var envelope = counterEnvelope("Player Three", "Team B", "p3", 5.0);
        var identity = TradeCounterProposalIdentityPolicy.identify(
            envelope, TradeCounterMaterializedPackagePolicy.materialize(envelope));

        assertEquals(TradeCounterProposalIdentityPolicy.POLICY_ID, identity.policyId());
        assertEquals(TradeCounterProposalEnvelopePolicy.POLICY_ID, identity.envelopePolicyId());
        assertEquals(TradeCounterMaterializedPackagePolicy.POLICY_ID,
            identity.materializedPackagePolicyId());
        assertEquals("l1", identity.leagueId());
        assertEquals(2026, identity.season());
        assertEquals("source", identity.source());
        assertEquals(AS_OF, identity.minimumAsOfDate());
        assertEquals(TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            identity.perspective());
    }

    private static TradeCounterProposalEnvelopePolicy.Envelope counterEnvelope(
        String displayName,
        String teamName,
        String assetId,
        double assetValue) {
        return TradeCounterProposalEnvelopePolicy.bind(
            counterResult(displayName, teamName, assetId, assetValue),
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            originalSideA(), originalSideB());
    }

    private static TradeCounterProposalPolicy.Result counterResult(
        String displayName,
        String teamName,
        String assetId,
        double assetValue) {
        var proposal = new TradeCounterProposalPolicy.Proposal(
            1,
            TradeCounterSingleAssetCandidateAnalyzer.AdjustmentType.ADD_ASSET_TO_LOWER_PACKAGE,
            TradeCounterValueTargetAnalyzer.Side.SIDE_B,
            TradeCounterSingleAssetCandidateAnalyzer.AssetType.PLAYER,
            assetId,
            displayName,
            "B",
            teamName,
            assetValue,
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
            proposal);
    }

    private static TradeCounterProposalEnvelopePolicy.Envelope nonCounterEnvelope(
        TradeCounterProposalPolicy.Action action,
        TradeCounterProposalPolicy.ReasonCode reason) {
        var result = new TradeCounterProposalPolicy.Result(
            TradeCounterProposalPolicy.POLICY_ID,
            TradeCounterOpportunityPolicy.POLICY_ID,
            TradeCounterCandidateSelectionPolicy.POLICY_ID,
            "l1", 2026, "source", AS_OF,
            action, reason, null);
        return TradeCounterProposalEnvelopePolicy.bind(
            result,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            originalSideA(), originalSideB());
    }

    private static TradeAssetAnalyzer.TradePackage originalSideA() {
        return new TradeAssetAnalyzer.TradePackage(List.of("p1"), List.of("k1"));
    }

    private static TradeAssetAnalyzer.TradePackage originalSideB() {
        return new TradeAssetAnalyzer.TradePackage(List.of("p2"), List.of());
    }
}
