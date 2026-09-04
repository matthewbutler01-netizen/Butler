package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TradeCounterAuthorizationPolicyTest {
    private static final String FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final String OTHER_FINGERPRINT =
        "2f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @Test
    void buildsExactSingleUseMessageAuthorizationRequest() {
        var request = TradeCounterAuthorizationPolicy.request(
            identified(FINGERPRINT),
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER,
                "manager-22"));

        assertEquals(TradeCounterAuthorizationPolicy.POLICY_ID, request.policyId());
        assertEquals(TradeCounterProposalIdentityPolicy.POLICY_ID, request.identityPolicyId());
        assertEquals(1, request.maxUses());
        assertEquals(
            "AUTHORIZE_ONCE action=SEND_NEGOTIATION_MESSAGE proposal=" + FINGERPRINT
                + " destination=MANAGER:manager-22",
            request.requiredConfirmation());
    }

    @Test
    void tradeSubmissionAuthorizationMustTargetExactProposalLeague() {
        var valid = TradeCounterAuthorizationPolicy.request(
            identified(FINGERPRINT),
            TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE,
                "league-1"));
        assertEquals("league-1", valid.destination().id());

        assertThrows(IllegalArgumentException.class, () -> TradeCounterAuthorizationPolicy.request(
            identified(FINGERPRINT),
            TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE,
                "other-league")));
    }

    @Test
    void actionAndDestinationTypesCannotBeMixed() {
        assertThrows(IllegalArgumentException.class, () -> TradeCounterAuthorizationPolicy.request(
            identified(FINGERPRINT),
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE,
                "league-1")));
        assertThrows(IllegalArgumentException.class, () -> TradeCounterAuthorizationPolicy.request(
            identified(FINGERPRINT),
            TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER,
                "manager-22")));
    }

    @Test
    void blanketApprovalDoesNotAuthorizeSpecificProposal() {
        var request = messageRequest();

        var approved = TradeCounterAuthorizationPolicy.authorize(
            request, request.requiredConfirmation());
        var blanket = TradeCounterAuthorizationPolicy.authorize(request, "approved");
        var continueText = TradeCounterAuthorizationPolicy.authorize(request, "continue");

        assertEquals(TradeCounterAuthorizationPolicy.DecisionState.AUTHORIZED, approved.state());
        assertNotNull(approved.grant());
        assertEquals(1, approved.grant().maxUses());
        assertEquals(FINGERPRINT, approved.grant().proposalFingerprint());
        assertEquals(request.action(), approved.grant().action());
        assertEquals(request.destination(), approved.grant().destination());
        assertNotNull(UUID.fromString(approved.grant().grantId()));

        assertEquals(TradeCounterAuthorizationPolicy.DecisionState.REJECTED, blanket.state());
        assertNull(blanket.grant());
        assertEquals(TradeCounterAuthorizationPolicy.DecisionState.REJECTED, continueText.state());
        assertNull(continueText.grant());
    }

    @Test
    void directRequestConstructionCannotWeakenConfirmationPhrase() {
        var identity = identified(FINGERPRINT);
        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.MANAGER,
            "manager-22");

        assertThrows(IllegalArgumentException.class, () ->
            new TradeCounterAuthorizationPolicy.AuthorizationRequest(
                TradeCounterAuthorizationPolicy.POLICY_ID,
                TradeCounterProposalIdentityPolicy.POLICY_ID,
                identity.leagueId(),
                identity.season(),
                identity.source(),
                identity.minimumAsOfDate(),
                identity.perspective(),
                identity.fingerprint(),
                TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
                destination,
                "approved",
                1));
    }

    @Test
    void authorizationRequiresIdentifiedProposal() {
        assertThrows(IllegalArgumentException.class, () -> TradeCounterAuthorizationPolicy.request(
            identity(TradeCounterProposalIdentityPolicy.State.NO_IDENTITY, null),
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER,
                "manager-22")));
    }

    @Test
    void revalidationRequiresExactFreshIdentityMatch() {
        var authorized = TradeCounterAuthorizationPolicy.authorize(
            messageRequest(), messageRequest().requiredConfirmation()).grant();

        var match = TradeCounterAuthorizationPolicy.revalidate(
            authorized, identified(FINGERPRINT));
        var drifted = TradeCounterAuthorizationPolicy.revalidate(
            authorized, identified(OTHER_FINGERPRINT));
        var gone = TradeCounterAuthorizationPolicy.revalidate(
            authorized, identity(TradeCounterProposalIdentityPolicy.State.NO_IDENTITY, null));
        var inconclusive = TradeCounterAuthorizationPolicy.revalidate(
            authorized, identity(TradeCounterProposalIdentityPolicy.State.INCONCLUSIVE, null));

        assertEquals(TradeCounterAuthorizationPolicy.RevalidationState.MATCH, match.state());
        assertEquals(TradeCounterAuthorizationPolicy.RevalidationState.DRIFTED, drifted.state());
        assertEquals(TradeCounterAuthorizationPolicy.RevalidationState.DRIFTED, gone.state());
        assertEquals(TradeCounterAuthorizationPolicy.RevalidationState.INCONCLUSIVE, inconclusive.state());
    }

    private static TradeCounterAuthorizationPolicy.AuthorizationRequest messageRequest() {
        return TradeCounterAuthorizationPolicy.request(
            identified(FINGERPRINT),
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER,
                "manager-22"));
    }

    private static TradeCounterProposalIdentityPolicy.Identity identified(String fingerprint) {
        return identity(TradeCounterProposalIdentityPolicy.State.IDENTIFIED, fingerprint);
    }

    private static TradeCounterProposalIdentityPolicy.Identity identity(
        TradeCounterProposalIdentityPolicy.State state,
        String fingerprint) {
        var reason = switch (state) {
            case IDENTIFIED -> TradeCounterProposalIdentityPolicy.ReasonCode.GOVERNED_COUNTER_IDENTIFIED;
            case NO_IDENTITY -> TradeCounterProposalIdentityPolicy.ReasonCode.COUNTER_PROPOSAL_NO_ACTION;
            case INCONCLUSIVE -> TradeCounterProposalIdentityPolicy.ReasonCode.COUNTER_PROPOSAL_INCONCLUSIVE;
        };
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
