package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeCounterExecutionReadinessPolicyTest {
    private static final String FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final String OTHER_FINGERPRINT =
        "2f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void matchingFreshIdentityIsReadyWithoutConsumingGrant() {
        var grant = grant();
        var result = TradeCounterExecutionReadinessPolicy.assess(
            grant, false, true, identified(FINGERPRINT));

        assertEquals(TradeCounterExecutionReadinessPolicy.POLICY_ID, result.policyId());
        assertEquals(TradeCounterAuthorizationPolicy.POLICY_ID, result.authorizationPolicyId());
        assertEquals(TradeCounterProposalIdentityPolicy.POLICY_ID, result.identityPolicyId());
        assertEquals(grant.grantId(), result.grantId());
        assertEquals(FINGERPRINT, result.authorizedFingerprint());
        assertEquals(FINGERPRINT, result.freshFingerprint());
        assertEquals(grant.action(), result.action());
        assertEquals(grant.destination(), result.destination());
        assertEquals(TradeCounterExecutionReadinessPolicy.State.READY, result.state());
        assertEquals(TradeCounterAuthorizationPolicy.RevalidationState.MATCH,
            result.revalidationState());
    }

    @Test
    void changedIdentifiedFingerprintIsDrifted() {
        var result = TradeCounterExecutionReadinessPolicy.assess(
            grant(), false, true, identified(OTHER_FINGERPRINT));

        assertEquals(TradeCounterExecutionReadinessPolicy.State.DRIFTED, result.state());
        assertEquals(TradeCounterAuthorizationPolicy.RevalidationState.DRIFTED,
            result.revalidationState());
        assertEquals(OTHER_FINGERPRINT, result.freshFingerprint());
    }

    @Test
    void freshNoIdentityIsDriftedWithoutSyntheticFingerprint() {
        var result = TradeCounterExecutionReadinessPolicy.assess(
            grant(), false, true, noIdentity());

        assertEquals(TradeCounterExecutionReadinessPolicy.State.DRIFTED, result.state());
        assertEquals(TradeCounterAuthorizationPolicy.RevalidationState.DRIFTED,
            result.revalidationState());
        assertNull(result.freshFingerprint());
    }

    @Test
    void freshInconclusiveEvidenceIsInconclusive() {
        var result = TradeCounterExecutionReadinessPolicy.assess(
            grant(), false, true, inconclusive());

        assertEquals(TradeCounterExecutionReadinessPolicy.State.INCONCLUSIVE, result.state());
        assertEquals(TradeCounterAuthorizationPolicy.RevalidationState.INCONCLUSIVE,
            result.revalidationState());
        assertNull(result.freshFingerprint());
    }

    @Test
    void consumedGrantBlocksBeforeFreshReplayEvaluation() {
        var result = TradeCounterExecutionReadinessPolicy.assess(
            grant(), true, false, null);

        assertEquals(TradeCounterExecutionReadinessPolicy.State.BLOCKED_ALREADY_CONSUMED,
            result.state());
        assertNull(result.revalidationState());
        assertNull(result.freshFingerprint());
        assertThrows(IllegalArgumentException.class, () ->
            TradeCounterExecutionReadinessPolicy.assess(
                grant(), true, true, identified(FINGERPRINT)));
    }

    @Test
    void missingReplayContextBlocksBeforeFreshReplayEvaluation() {
        var result = TradeCounterExecutionReadinessPolicy.assess(
            grant(), false, false, null);

        assertEquals(TradeCounterExecutionReadinessPolicy.State.BLOCKED_MISSING_REPLAY_CONTEXT,
            result.state());
        assertNull(result.revalidationState());
        assertNull(result.freshFingerprint());
        assertThrows(IllegalArgumentException.class, () ->
            TradeCounterExecutionReadinessPolicy.assess(
                grant(), false, false, identified(FINGERPRINT)));
    }

    @Test
    void activeGrantWithReplayRequiresFreshIdentity() {
        assertThrows(NullPointerException.class, () ->
            TradeCounterExecutionReadinessPolicy.assess(grant(), false, true, null));
    }

    @Test
    void freshIdentityCoordinateDriftIsDriftedEvenWithSameFingerprint() {
        var fresh = new TradeCounterProposalIdentityPolicy.Identity(
            TradeCounterProposalIdentityPolicy.POLICY_ID,
            TradeCounterProposalEnvelopePolicy.POLICY_ID,
            TradeCounterMaterializedPackagePolicy.POLICY_ID,
            TradeCounterProposalIdentityPolicy.ALGORITHM,
            TradeCounterProposalIdentityPolicy.CANONICAL_VERSION,
            "league-1",
            2027,
            "source",
            AS_OF,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            TradeCounterProposalIdentityPolicy.State.IDENTIFIED,
            TradeCounterProposalIdentityPolicy.ReasonCode.GOVERNED_COUNTER_IDENTIFIED,
            FINGERPRINT);

        var result = TradeCounterExecutionReadinessPolicy.assess(grant(), false, true, fresh);

        assertEquals(TradeCounterExecutionReadinessPolicy.State.DRIFTED, result.state());
        assertEquals(TradeCounterAuthorizationPolicy.RevalidationState.DRIFTED,
            result.revalidationState());
        assertEquals(FINGERPRINT, result.freshFingerprint());
    }

    private static TradeCounterAuthorizationPolicy.AuthorizationGrant grant() {
        var request = TradeCounterAuthorizationPolicy.request(
            identified(FINGERPRINT),
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER,
                "manager-22"));
        return TradeCounterAuthorizationPolicy.authorize(
            request, request.requiredConfirmation()).grant();
    }

    private static TradeCounterProposalIdentityPolicy.Identity identified(String fingerprint) {
        return identity(
            TradeCounterProposalIdentityPolicy.State.IDENTIFIED,
            TradeCounterProposalIdentityPolicy.ReasonCode.GOVERNED_COUNTER_IDENTIFIED,
            fingerprint);
    }

    private static TradeCounterProposalIdentityPolicy.Identity noIdentity() {
        return identity(
            TradeCounterProposalIdentityPolicy.State.NO_IDENTITY,
            TradeCounterProposalIdentityPolicy.ReasonCode.COUNTER_PROPOSAL_NO_ACTION,
            null);
    }

    private static TradeCounterProposalIdentityPolicy.Identity inconclusive() {
        return identity(
            TradeCounterProposalIdentityPolicy.State.INCONCLUSIVE,
            TradeCounterProposalIdentityPolicy.ReasonCode.COUNTER_PROPOSAL_INCONCLUSIVE,
            null);
    }

    private static TradeCounterProposalIdentityPolicy.Identity identity(
        TradeCounterProposalIdentityPolicy.State state,
        TradeCounterProposalIdentityPolicy.ReasonCode reason,
        String fingerprint) {
        return new TradeCounterProposalIdentityPolicy.Identity(
            TradeCounterProposalIdentityPolicy.POLICY_ID,
            TradeCounterProposalEnvelopePolicy.POLICY_ID,
            TradeCounterMaterializedPackagePolicy.POLICY_ID,
            TradeCounterProposalIdentityPolicy.ALGORITHM,
            TradeCounterProposalIdentityPolicy.CANONICAL_VERSION,
            "league-1",
            2026,
            "source",
            AS_OF,
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            state,
            reason,
            fingerprint);
    }
}
