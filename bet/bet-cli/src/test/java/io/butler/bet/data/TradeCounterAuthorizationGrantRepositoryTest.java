package io.butler.bet.data;

import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import io.butler.bet.intelligence.TradeCounterMaterializedPackagePolicy;
import io.butler.bet.intelligence.TradeCounterProposalEnvelopePolicy;
import io.butler.bet.intelligence.TradeCounterProposalIdentityPolicy;
import io.butler.bet.intelligence.TradeTeamPerspectiveRecommendationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterAuthorizationGrantRepositoryTest {
    private static final String FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final String OTHER_FINGERPRINT =
        "2f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 1);

    @TempDir
    Path tempDir;

    @Test
    void initializationIsIdempotentAndRoundTripsTrustedGrant() throws Exception {
        var repository = repository();
        repository.initialize();
        repository.initialize();
        var grant = messageGrant(FINGERPRINT, "manager-22");

        repository.save(grant);
        var stored = repository.findById(grant.grantId()).orElseThrow();

        assertEquals(grant.grantId(), stored.grant().grantId());
        assertEquals(grant.grantedAt(), stored.grant().grantedAt());
        assertEquals(grant.leagueId(), stored.grant().leagueId());
        assertEquals(grant.season(), stored.grant().season());
        assertEquals(grant.source(), stored.grant().source());
        assertEquals(grant.minimumAsOfDate(), stored.grant().minimumAsOfDate());
        assertEquals(grant.perspective(), stored.grant().perspective());
        assertEquals(grant.proposalFingerprint(), stored.grant().proposalFingerprint());
        assertEquals(grant.action(), stored.grant().action());
        assertEquals(grant.destination(), stored.grant().destination());
        assertEquals(1, stored.grant().maxUses());
        assertFalse(stored.consumed());
    }

    @Test
    void exactConsumeWinsOnceAndSecondAttemptFailsClosed() throws Exception {
        var repository = initializedRepository();
        var grant = messageGrant(FINGERPRINT, "manager-22");
        repository.save(grant);
        Instant consumedAt = Instant.parse("2026-09-04T14:40:00Z");

        var first = repository.consume(
            grant.grantId(), grant.proposalFingerprint(), grant.action(), grant.destination(), consumedAt);
        var second = repository.consume(
            grant.grantId(), grant.proposalFingerprint(), grant.action(), grant.destination(),
            Instant.parse("2026-09-04T14:41:00Z"));
        var stored = repository.findById(grant.grantId()).orElseThrow();

        assertEquals(TradeCounterAuthorizationGrantRepository.ConsumptionResult.CONSUMED, first);
        assertEquals(TradeCounterAuthorizationGrantRepository.ConsumptionResult.ALREADY_CONSUMED, second);
        assertTrue(stored.consumed());
        assertEquals(consumedAt, stored.consumedAt());
    }

    @Test
    void mismatchedExpectedCoordinatesNeverConsumeGrant() throws Exception {
        var repository = initializedRepository();
        var grant = messageGrant(FINGERPRINT, "manager-22");
        repository.save(grant);

        var wrongFingerprint = repository.consume(
            grant.grantId(), OTHER_FINGERPRINT, grant.action(), grant.destination(), Instant.now());
        var wrongDestination = repository.consume(
            grant.grantId(), grant.proposalFingerprint(), grant.action(),
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-99"),
            Instant.now());
        var stored = repository.findById(grant.grantId()).orElseThrow();

        assertEquals(TradeCounterAuthorizationGrantRepository.ConsumptionResult.MISMATCH, wrongFingerprint);
        assertEquals(TradeCounterAuthorizationGrantRepository.ConsumptionResult.MISMATCH, wrongDestination);
        assertFalse(stored.consumed());
    }

    @Test
    void unknownGrantIdReturnsNotFound() throws Exception {
        var repository = initializedRepository();
        var result = repository.consume(
            "unknown-grant",
            FINGERPRINT,
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22"),
            Instant.now());

        assertEquals(TradeCounterAuthorizationGrantRepository.ConsumptionResult.NOT_FOUND, result);
    }

    @Test
    void duplicateActiveIntentIsRejectedButFreshAuthorizationAfterConsumptionIsAllowed() throws Exception {
        var repository = initializedRepository();
        var first = messageGrant(FINGERPRINT, "manager-22");
        var duplicateIntent = messageGrant(FINGERPRINT, "manager-22");
        repository.save(first);

        assertThrows(SQLException.class, () -> repository.save(duplicateIntent));

        assertEquals(TradeCounterAuthorizationGrantRepository.ConsumptionResult.CONSUMED,
            repository.consume(first.grantId(), first.proposalFingerprint(), first.action(),
                first.destination(), Instant.now()));

        var fresh = messageGrant(FINGERPRINT, "manager-22");
        repository.save(fresh);
        assertNotNull(repository.findById(fresh.grantId()).orElseThrow());
    }

    @Test
    void distinctActionOrDestinationCanHaveSeparateActiveAuthorization() throws Exception {
        var repository = initializedRepository();
        var messageA = messageGrant(FINGERPRINT, "manager-22");
        var messageB = messageGrant(FINGERPRINT, "manager-23");
        var submit = submitGrant(FINGERPRINT);

        repository.save(messageA);
        repository.save(messageB);
        repository.save(submit);

        assertFalse(repository.findById(messageA.grantId()).orElseThrow().consumed());
        assertFalse(repository.findById(messageB.grantId()).orElseThrow().consumed());
        assertFalse(repository.findById(submit.grantId()).orElseThrow().consumed());
    }

    @Test
    void persistedSubmitGrantRemainsBoundToExactLeague() throws Exception {
        var repository = initializedRepository();
        var grant = submitGrant(FINGERPRINT);
        repository.save(grant);

        var stored = repository.findById(grant.grantId()).orElseThrow();

        assertEquals(TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            stored.grant().action());
        assertEquals(TradeCounterAuthorizationPolicy.DestinationType.LEAGUE,
            stored.grant().destination().type());
        assertEquals("league-1", stored.grant().destination().id());
        assertEquals(stored.grant().leagueId(), stored.grant().destination().id());
    }

    private TradeCounterAuthorizationGrantRepository repository() {
        return new TradeCounterAuthorizationGrantRepository(
            new Database(tempDir.resolve("authorization.db")));
    }

    private TradeCounterAuthorizationGrantRepository initializedRepository() throws SQLException {
        var repository = repository();
        repository.initialize();
        return repository;
    }

    private static TradeCounterAuthorizationPolicy.AuthorizationGrant messageGrant(
        String fingerprint,
        String managerId) {
        return authorizedGrant(
            fingerprint,
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, managerId));
    }

    private static TradeCounterAuthorizationPolicy.AuthorizationGrant submitGrant(String fingerprint) {
        return authorizedGrant(
            fingerprint,
            TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "league-1"));
    }

    private static TradeCounterAuthorizationPolicy.AuthorizationGrant authorizedGrant(
        String fingerprint,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination) {
        var identity = new TradeCounterProposalIdentityPolicy.Identity(
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
            TradeCounterProposalIdentityPolicy.State.IDENTIFIED,
            TradeCounterProposalIdentityPolicy.ReasonCode.GOVERNED_COUNTER_IDENTIFIED,
            fingerprint);
        var request = TradeCounterAuthorizationPolicy.request(identity, action, destination);
        var decision = TradeCounterAuthorizationPolicy.authorize(request, request.requiredConfirmation());
        assertEquals(TradeCounterAuthorizationPolicy.DecisionState.AUTHORIZED, decision.state());
        return decision.grant();
    }
}
