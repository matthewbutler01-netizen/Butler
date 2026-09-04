package io.butler.bet.data;

import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import io.butler.bet.intelligence.TradeCounterExecutionReadinessPolicy;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeCounterExecutionClaimRepositoryTest {
    private static final String FINGERPRINT =
        "1f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final String OTHER_FINGERPRINT =
        "2f7c8beb37acdcc2f2d0f93e75a36bfb3bc5b4828e730330696ee05e8f1182f8";
    private static final Instant PREPARED_AT = Instant.parse("2026-09-04T18:30:00Z");
    private static final Instant CLAIMED_AT = Instant.parse("2026-09-04T18:31:00Z");

    @TempDir
    Path tempDir;

    @Test
    void readyAttemptIsAtomicallyClaimedAndMovedInFlightWithoutConsumingGrant() throws Exception {
        var fixture = fixture();
        var readiness = ready(fixture.grant());

        var result = fixture.claims().claim(
            fixture.attempt().attemptId(), readiness, CLAIMED_AT);
        var storedAttempt = fixture.attempts().findByAttemptId(
            fixture.attempt().attemptId()).orElseThrow();
        var storedGrant = new TradeCounterAuthorizationGrantRepository(fixture.database())
            .findById(fixture.grant().grantId()).orElseThrow();

        assertEquals(TradeCounterExecutionClaimRepository.ClaimState.CLAIMED, result.state());
        assertEquals(TradeCounterExecutionClaimRepository.CLAIM_POLICY_ID,
            result.claim().claimPolicyId());
        assertEquals(fixture.attempt().attemptId(), result.claim().attemptId());
        assertEquals(fixture.grant().grantId(), result.claim().grantId());
        assertEquals(FINGERPRINT, result.claim().proposalFingerprint());
        assertEquals(FINGERPRINT, result.claim().freshFingerprint());
        assertEquals(fixture.grant().action(), result.claim().action());
        assertEquals(fixture.grant().destination(), result.claim().destination());
        assertEquals(CLAIMED_AT, result.claim().claimedAt());
        assertEquals(TradeCounterExecutionAttemptRepository.State.IN_FLIGHT,
            storedAttempt.state());
        assertEquals(CLAIMED_AT, storedAttempt.inFlightAt());
        assertFalse(storedGrant.consumed());
    }

    @Test
    void repeatedSameReadyClaimReturnsExistingClaimAndCannotCreateSecond() throws Exception {
        var fixture = fixture();
        var readiness = ready(fixture.grant());

        var first = fixture.claims().claim(
            fixture.attempt().attemptId(), readiness, CLAIMED_AT);
        var second = fixture.claims().claim(
            fixture.attempt().attemptId(), readiness, CLAIMED_AT.plusSeconds(5));

        assertEquals(TradeCounterExecutionClaimRepository.ClaimState.CLAIMED, first.state());
        assertEquals(TradeCounterExecutionClaimRepository.ClaimState.ALREADY_CLAIMED, second.state());
        assertEquals(first.claim().claimId(), second.claim().claimId());
        assertEquals(CLAIMED_AT, second.claim().claimedAt());
        assertEquals(1, countClaims(fixture.database()));
    }

    @Test
    void nonReadyEvidenceNeverClaimsOrMovesAttempt() throws Exception {
        var fixture = fixture();
        var drifted = TradeCounterExecutionReadinessPolicy.assess(
            fixture.grant(), false, true, identified(OTHER_FINGERPRINT));

        var result = fixture.claims().claim(
            fixture.attempt().attemptId(), drifted, CLAIMED_AT);

        assertEquals(TradeCounterExecutionClaimRepository.ClaimState.READINESS_NOT_READY,
            result.state());
        assertNull(result.claim());
        assertTrue(fixture.claims().findByAttemptId(fixture.attempt().attemptId()).isEmpty());
        assertEquals(TradeCounterExecutionAttemptRepository.State.PREPARED,
            fixture.attempts().findByAttemptId(fixture.attempt().attemptId()).orElseThrow().state());
    }

    @Test
    void consumedGrantAfterReadyEvidenceBlocksClaim() throws Exception {
        var fixture = fixture();
        var readiness = ready(fixture.grant());
        var grants = new TradeCounterAuthorizationGrantRepository(fixture.database());
        assertEquals(TradeCounterAuthorizationGrantRepository.ConsumptionResult.CONSUMED,
            grants.consume(
                fixture.grant().grantId(),
                fixture.grant().proposalFingerprint(),
                fixture.grant().action(),
                fixture.grant().destination(),
                CLAIMED_AT.minusSeconds(1)));

        var result = fixture.claims().claim(
            fixture.attempt().attemptId(), readiness, CLAIMED_AT);

        assertEquals(TradeCounterExecutionClaimRepository.ClaimState.GRANT_NOT_ACTIVE,
            result.state());
        assertTrue(fixture.claims().findByAttemptId(fixture.attempt().attemptId()).isEmpty());
        assertEquals(TradeCounterExecutionAttemptRepository.State.PREPARED,
            fixture.attempts().findByAttemptId(fixture.attempt().attemptId()).orElseThrow().state());
    }

    @Test
    void mismatchedReadyCoordinatesFailClosed() throws Exception {
        var fixture = fixture();
        var otherGrant = authorizedGrant(OTHER_FINGERPRINT, "manager-99");
        new TradeCounterAuthorizationGrantRepository(fixture.database()).save(otherGrant);
        var otherReadiness = ready(otherGrant);

        var result = fixture.claims().claim(
            fixture.attempt().attemptId(), otherReadiness, CLAIMED_AT);

        assertEquals(TradeCounterExecutionClaimRepository.ClaimState.MISMATCH, result.state());
        assertNull(result.claim());
        assertEquals(TradeCounterExecutionAttemptRepository.State.PREPARED,
            fixture.attempts().findByAttemptId(fixture.attempt().attemptId()).orElseThrow().state());
    }

    @Test
    void terminalAttemptCannotBeClaimedRetroactively() throws Exception {
        Database database = new Database(tempDir.resolve("terminal.db"));
        var grants = new TradeCounterAuthorizationGrantRepository(database);
        grants.initialize();
        var grant = authorizedGrant(FINGERPRINT, "manager-22");
        grants.save(grant);
        var attempts = new TradeCounterExecutionAttemptRepository(database);
        var attempt = attempts.prepare(
            grant.grantId(),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            "I'd counter if you add Player X.",
            PREPARED_AT).attempt();

        assertEquals(TradeCounterExecutionAttemptRepository.TransitionState.TRANSITIONED,
            attempts.markInFlight(attempt.attemptId(), PREPARED_AT.plusSeconds(5)).state());
        assertEquals(TradeCounterExecutionAttemptRepository.TransitionState.TRANSITIONED,
            attempts.markUnknown(
                attempt.attemptId(), PREPARED_AT.plusSeconds(10), "legacy unknown state").state());

        var claims = new TradeCounterExecutionClaimRepository(database);
        var result = claims.claim(attempt.attemptId(), ready(grant), CLAIMED_AT);

        assertEquals(TradeCounterExecutionClaimRepository.ClaimState.ATTEMPT_NOT_PREPARED,
            result.state());
        assertTrue(claims.findByAttemptId(attempt.attemptId()).isEmpty());
        assertEquals(TradeCounterExecutionAttemptRepository.State.UNKNOWN,
            attempts.findByAttemptId(attempt.attemptId()).orElseThrow().state());
    }

    @Test
    void initializedClaimGateBlocksDirectPreparedToInFlightBypass() throws Exception {
        var fixture = fixture();

        assertThrows(SQLException.class, () -> fixture.attempts().markInFlight(
            fixture.attempt().attemptId(), CLAIMED_AT));
        assertEquals(TradeCounterExecutionAttemptRepository.State.PREPARED,
            fixture.attempts().findByAttemptId(fixture.attempt().attemptId()).orElseThrow().state());
        assertTrue(fixture.claims().findByAttemptId(fixture.attempt().attemptId()).isEmpty());
    }

    @Test
    void durableClaimIsImmutableAtDatabaseLayer() throws Exception {
        var fixture = fixture();
        var claimed = fixture.claims().claim(
            fixture.attempt().attemptId(), ready(fixture.grant()), CLAIMED_AT).claim();

        try (var connection = fixture.database().openConnection()) {
            assertThrows(SQLException.class, () -> {
                try (var statement = connection.prepareStatement("""
                    UPDATE trade_counter_execution_claims SET destination_id = ? WHERE claim_id = ?
                    """)) {
                    statement.setString(1, "manager-other");
                    statement.setString(2, claimed.claimId());
                    statement.executeUpdate();
                }
            });
        }

        var stored = fixture.claims().findByAttemptId(fixture.attempt().attemptId()).orElseThrow();
        assertEquals("manager-22", stored.destination().id());
    }

    @Test
    void missingAttemptReturnsTerminalNotFoundWithoutClaim() throws Exception {
        var fixture = fixture();

        var result = fixture.claims().claim("missing-attempt", ready(fixture.grant()), CLAIMED_AT);

        assertEquals(TradeCounterExecutionClaimRepository.ClaimState.ATTEMPT_NOT_FOUND,
            result.state());
        assertNull(result.claim());
    }

    private Fixture fixture() throws Exception {
        Database database = new Database(tempDir.resolve("claim.db"));
        var grants = new TradeCounterAuthorizationGrantRepository(database);
        grants.initialize();
        var grant = authorizedGrant(FINGERPRINT, "manager-22");
        grants.save(grant);
        var attempts = new TradeCounterExecutionAttemptRepository(database);
        var attempt = attempts.prepare(
            grant.grantId(),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
            "I'd counter if you add Player X.",
            PREPARED_AT).attempt();
        var claims = new TradeCounterExecutionClaimRepository(database);
        claims.initialize();
        return new Fixture(database, grant, attempt, attempts, claims);
    }

    private static TradeCounterExecutionReadinessPolicy.Result ready(
        TradeCounterAuthorizationPolicy.AuthorizationGrant grant) {
        return TradeCounterExecutionReadinessPolicy.assess(
            grant, false, true, identified(grant.proposalFingerprint()));
    }

    private static TradeCounterAuthorizationPolicy.AuthorizationGrant authorizedGrant(
        String fingerprint,
        String managerId) {
        var identity = identified(fingerprint);
        var destination = new TradeCounterAuthorizationPolicy.Destination(
            TradeCounterAuthorizationPolicy.DestinationType.MANAGER, managerId);
        var request = TradeCounterAuthorizationPolicy.request(
            identity,
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            destination);
        return TradeCounterAuthorizationPolicy.authorize(
            request, request.requiredConfirmation()).grant();
    }

    private static TradeCounterProposalIdentityPolicy.Identity identified(String fingerprint) {
        return new TradeCounterProposalIdentityPolicy.Identity(
            TradeCounterProposalIdentityPolicy.POLICY_ID,
            TradeCounterProposalEnvelopePolicy.POLICY_ID,
            TradeCounterMaterializedPackagePolicy.POLICY_ID,
            TradeCounterProposalIdentityPolicy.ALGORITHM,
            TradeCounterProposalIdentityPolicy.CANONICAL_VERSION,
            "league-1",
            2026,
            "source",
            LocalDate.of(2026, 9, 1),
            TradeTeamPerspectiveRecommendationPolicy.Perspective.SIDE_A_TEAM,
            TradeCounterProposalIdentityPolicy.State.IDENTIFIED,
            TradeCounterProposalIdentityPolicy.ReasonCode.GOVERNED_COUNTER_IDENTIFIED,
            fingerprint);
    }

    private static int countClaims(Database database) throws SQLException {
        try (var connection = database.openConnection();
             var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM trade_counter_execution_claims")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private record Fixture(
        Database database,
        TradeCounterAuthorizationPolicy.AuthorizationGrant grant,
        TradeCounterExecutionAttemptRepository.ExecutionAttempt attempt,
        TradeCounterExecutionAttemptRepository attempts,
        TradeCounterExecutionClaimRepository claims) {}
}
