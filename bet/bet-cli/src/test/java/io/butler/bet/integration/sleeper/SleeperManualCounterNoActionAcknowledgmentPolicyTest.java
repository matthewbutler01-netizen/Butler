package io.butler.bet.integration.sleeper;

import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionRequestRepository;
import io.butler.bet.execution.TradeCounterExecutionOutcomePolicy;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SleeperManualCounterNoActionAcknowledgmentPolicyTest {
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-05T00:15:00Z");
    private static final Instant ACKNOWLEDGED_AT = PRESENTED_AT.plusSeconds(30);

    @Test
    void exactMessageNoActionAcknowledgmentIsEligibleForFailedAndGrantClose() {
        var handoff = messageHandoff();
        var decision = SleeperManualCounterNoActionAcknowledgmentPolicy.acknowledge(
            handoff, request(handoff,
                SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
                ACKNOWLEDGED_AT));

        assertEquals(SleeperManualCounterNoActionAcknowledgmentPolicy.State.ACKNOWLEDGED,
            decision.state());
        assertEquals(
            SleeperManualCounterNoActionAcknowledgmentPolicy.ReasonCode.EXACT_NO_EXTERNAL_ACTION_ACKNOWLEDGED,
            decision.reasonCode());
        assertEquals(
            SleeperManualCounterNoActionAcknowledgmentPolicy.LocalTerminalEligibility.CONFIRMED_NO_ACTION_FAILURE,
            decision.localTerminalEligibility());
        assertEquals(TradeCounterExecutionAttemptRepository.State.FAILED,
            decision.attemptTerminalState());
        assertEquals(TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME,
            decision.grantDisposition());
        assertEquals(TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            decision.action());
        assertEquals(ACKNOWLEDGED_AT, decision.acknowledgedAt());
    }

    @Test
    void exactTradeNoActionAcknowledgmentUsesSameEstablishedNoActionTerminalSemantics() {
        var handoff = tradeHandoff();
        var decision = SleeperManualCounterNoActionAcknowledgmentPolicy.acknowledge(
            handoff, request(handoff,
                SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
                ACKNOWLEDGED_AT));

        assertEquals(SleeperManualCounterNoActionAcknowledgmentPolicy.State.ACKNOWLEDGED,
            decision.state());
        assertEquals(TradeCounterExecutionAttemptRepository.State.FAILED,
            decision.attemptTerminalState());
        assertEquals(TradeCounterExecutionOutcomePolicy.GrantDisposition.CONSUME,
            decision.grantDisposition());
        assertEquals(TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            decision.action());
        assertEquals(TradeCounterAuthorizationPolicy.DestinationType.LEAGUE,
            decision.destination().type());
    }

    @Test
    void confirmationMustMatchByteForByteIncludingWhitespaceAndCase() {
        var handoff = messageHandoff();

        var whitespace = SleeperManualCounterNoActionAcknowledgmentPolicy.acknowledge(
            handoff, request(handoff, " NO_EXTERNAL_ACTION_TAKEN ", ACKNOWLEDGED_AT));
        var lowercase = SleeperManualCounterNoActionAcknowledgmentPolicy.acknowledge(
            handoff, request(handoff, "no_external_action_taken", ACKNOWLEDGED_AT));

        assertNotAcknowledged(whitespace);
        assertNotAcknowledged(lowercase);
    }

    @Test
    void mismatchedTrustedCoordinatesAreInconclusiveAndCannotCloseGrant() {
        var handoff = tradeHandoff();
        var request = new SleeperManualCounterNoActionAcknowledgmentPolicy.AcknowledgmentRequest(
            "grant-other",
            handoff.handoffId(),
            handoff.payloadSha256(),
            SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            ACKNOWLEDGED_AT);

        var decision = SleeperManualCounterNoActionAcknowledgmentPolicy.acknowledge(handoff, request);

        assertEquals(SleeperManualCounterNoActionAcknowledgmentPolicy.State.INCONCLUSIVE,
            decision.state());
        assertEquals(
            SleeperManualCounterNoActionAcknowledgmentPolicy.ReasonCode.ACKNOWLEDGMENT_COORDINATES_MISMATCH,
            decision.reasonCode());
        assertNoTerminalEligibility(decision);
    }

    @Test
    void acknowledgmentCannotPredateFirstPresentedHandoff() {
        var handoff = messageHandoff();
        var decision = SleeperManualCounterNoActionAcknowledgmentPolicy.acknowledge(
            handoff, request(handoff,
                SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
                PRESENTED_AT.minusSeconds(1)));

        assertEquals(SleeperManualCounterNoActionAcknowledgmentPolicy.State.INCONCLUSIVE,
            decision.state());
        assertEquals(
            SleeperManualCounterNoActionAcknowledgmentPolicy.ReasonCode.ACKNOWLEDGMENT_PREDATES_HANDOFF,
            decision.reasonCode());
        assertNoTerminalEligibility(decision);
    }

    @Test
    void unsupportedHandoffShapeIsInconclusiveEvenWithExactPhrase() {
        var handoff = unsupportedMessageHandoffWithTradeReadback();
        var decision = SleeperManualCounterNoActionAcknowledgmentPolicy.acknowledge(
            handoff, request(handoff,
                SleeperManualCounterNoActionAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
                ACKNOWLEDGED_AT));

        assertEquals(SleeperManualCounterNoActionAcknowledgmentPolicy.State.INCONCLUSIVE,
            decision.state());
        assertEquals(
            SleeperManualCounterNoActionAcknowledgmentPolicy.ReasonCode.HANDOFF_NOT_SUPPORTED_MANUAL_ACTION,
            decision.reasonCode());
        assertNoTerminalEligibility(decision);
    }

    private static void assertNotAcknowledged(
        SleeperManualCounterNoActionAcknowledgmentPolicy.Decision decision) {
        assertEquals(SleeperManualCounterNoActionAcknowledgmentPolicy.State.NOT_ACKNOWLEDGED,
            decision.state());
        assertEquals(
            SleeperManualCounterNoActionAcknowledgmentPolicy.ReasonCode.CONFIRMATION_NOT_EXACT,
            decision.reasonCode());
        assertNoTerminalEligibility(decision);
    }

    private static void assertNoTerminalEligibility(
        SleeperManualCounterNoActionAcknowledgmentPolicy.Decision decision) {
        assertEquals(SleeperManualCounterNoActionAcknowledgmentPolicy.LocalTerminalEligibility.NONE,
            decision.localTerminalEligibility());
        assertNull(decision.attemptTerminalState());
        assertEquals(TradeCounterExecutionOutcomePolicy.GrantDisposition.RETAIN_ACTIVE,
            decision.grantDisposition());
        assertNull(decision.acknowledgedAt());
    }

    private static SleeperManualCounterNoActionAcknowledgmentPolicy.AcknowledgmentRequest request(
        SleeperManualCounterHandoffRepository.PresentedHandoff handoff,
        String confirmation,
        Instant acknowledgedAt) {
        return new SleeperManualCounterNoActionAcknowledgmentPolicy.AcknowledgmentRequest(
            handoff.grantId(),
            handoff.handoffId(),
            handoff.payloadSha256(),
            confirmation,
            acknowledgedAt);
    }

    private static SleeperManualCounterHandoffRepository.PresentedHandoff messageHandoff() {
        return handoff(
            "handoff-message",
            "claim-message",
            "attempt-message",
            "grant-message",
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22"),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT.name(),
            SleeperManualCounterHandoffService.ReconciliationMode.NO_OFFICIAL_READBACK,
            "b".repeat(64));
    }

    private static SleeperManualCounterHandoffRepository.PresentedHandoff tradeHandoff() {
        return handoff(
            "handoff-trade",
            "claim-trade",
            "attempt-trade",
            "grant-trade",
            TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "league-1"),
            TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON.name(),
            SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK,
            "c".repeat(64));
    }

    private static SleeperManualCounterHandoffRepository.PresentedHandoff unsupportedMessageHandoffWithTradeReadback() {
        return handoff(
            "handoff-unsupported",
            "claim-unsupported",
            "attempt-unsupported",
            "grant-unsupported",
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22"),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT.name(),
            SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK,
            "d".repeat(64));
    }

    private static SleeperManualCounterHandoffRepository.PresentedHandoff handoff(
        String handoffId,
        String claimId,
        String attemptId,
        String grantId,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        String payloadKind,
        SleeperManualCounterHandoffService.ReconciliationMode reconciliationMode,
        String payloadSha256) {
        return new SleeperManualCounterHandoffRepository.PresentedHandoff(
            handoffId,
            SleeperManualCounterHandoffRepository.JOURNAL_POLICY_ID,
            SleeperManualCounterHandoffService.SERVICE_ID,
            SleeperPlatformCapabilityPolicy.POLICY_ID,
            TradeCounterExecutionRequestRepository.REQUEST_POLICY_ID,
            claimId,
            attemptId,
            grantId,
            "a".repeat(64),
            action,
            destination,
            payloadKind,
            payloadSha256,
            reconciliationMode,
            PRESENTED_AT);
    }
}
