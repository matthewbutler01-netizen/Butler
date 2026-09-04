package io.butler.bet.integration.sleeper;

import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.data.TradeCounterExecutionRequestRepository;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SleeperManualMessageAcknowledgmentPolicyTest {
    private static final Instant PRESENTED_AT = Instant.parse("2026-09-04T22:40:00Z");
    private static final Instant ACKNOWLEDGED_AT = PRESENTED_AT.plusSeconds(30);
    private static final String FINGERPRINT = "b".repeat(64);
    private static final String PAYLOAD_HASH = "a".repeat(64);

    @Test
    void exactBoundConfirmationAcknowledgesManualMessage() {
        var handoff = messageHandoff();
        var request = request(
            handoff.grantId(), handoff.handoffId(), handoff.payloadSha256(),
            SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            ACKNOWLEDGED_AT);

        var decision = SleeperManualMessageAcknowledgmentPolicy.acknowledge(handoff, request);

        assertEquals(SleeperManualMessageAcknowledgmentPolicy.State.ACKNOWLEDGED, decision.state());
        assertEquals(
            SleeperManualMessageAcknowledgmentPolicy.ReasonCode.EXACT_MANUAL_MESSAGE_SENT_ACKNOWLEDGED,
            decision.reasonCode());
        assertEquals(
            SleeperManualMessageAcknowledgmentPolicy.LocalCompletionEligibility.MANUAL_MESSAGE_SUCCESS,
            decision.localCompletionEligibility());
        assertEquals(ACKNOWLEDGED_AT, decision.acknowledgedAt());
        assertEquals(PAYLOAD_HASH, decision.payloadSha256());
        assertEquals("handoff-1", decision.handoffId());
        assertEquals("grant-1", decision.grantId());
    }

    @Test
    void inexactConfirmationDoesNotAcknowledge() {
        var handoff = messageHandoff();
        var decision = SleeperManualMessageAcknowledgmentPolicy.acknowledge(
            handoff,
            request(handoff.grantId(), handoff.handoffId(), handoff.payloadSha256(),
                "sent_exact_message", ACKNOWLEDGED_AT));

        assertEquals(SleeperManualMessageAcknowledgmentPolicy.State.NOT_ACKNOWLEDGED, decision.state());
        assertEquals(
            SleeperManualMessageAcknowledgmentPolicy.ReasonCode.CONFIRMATION_NOT_EXACT,
            decision.reasonCode());
        assertEquals(
            SleeperManualMessageAcknowledgmentPolicy.LocalCompletionEligibility.NONE,
            decision.localCompletionEligibility());
        assertNull(decision.acknowledgedAt());
    }

    @Test
    void mismatchedGrantHandoffOrPayloadFailsClosed() {
        var handoff = messageHandoff();
        for (var request : new SleeperManualMessageAcknowledgmentPolicy.AcknowledgmentRequest[]{
            request("different-grant", handoff.handoffId(), handoff.payloadSha256(),
                SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION, ACKNOWLEDGED_AT),
            request(handoff.grantId(), "different-handoff", handoff.payloadSha256(),
                SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION, ACKNOWLEDGED_AT),
            request(handoff.grantId(), handoff.handoffId(), "c".repeat(64),
                SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION, ACKNOWLEDGED_AT)
        }) {
            var decision = SleeperManualMessageAcknowledgmentPolicy.acknowledge(handoff, request);
            assertEquals(SleeperManualMessageAcknowledgmentPolicy.State.INCONCLUSIVE, decision.state());
            assertEquals(
                SleeperManualMessageAcknowledgmentPolicy.ReasonCode.ACKNOWLEDGMENT_COORDINATES_MISMATCH,
                decision.reasonCode());
            assertEquals(
                SleeperManualMessageAcknowledgmentPolicy.LocalCompletionEligibility.NONE,
                decision.localCompletionEligibility());
            assertNull(decision.acknowledgedAt());
        }
    }

    @Test
    void acknowledgmentCannotPredatePresentation() {
        var handoff = messageHandoff();
        var decision = SleeperManualMessageAcknowledgmentPolicy.acknowledge(
            handoff,
            request(handoff.grantId(), handoff.handoffId(), handoff.payloadSha256(),
                SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
                PRESENTED_AT.minusSeconds(1)));

        assertEquals(SleeperManualMessageAcknowledgmentPolicy.State.INCONCLUSIVE, decision.state());
        assertEquals(
            SleeperManualMessageAcknowledgmentPolicy.ReasonCode.ACKNOWLEDGMENT_PREDATES_HANDOFF,
            decision.reasonCode());
        assertEquals(
            SleeperManualMessageAcknowledgmentPolicy.LocalCompletionEligibility.NONE,
            decision.localCompletionEligibility());
        assertNull(decision.acknowledgedAt());
    }

    @Test
    void tradeHandoffCannotUseManualMessageAcknowledgment() {
        var handoff = new SleeperManualCounterHandoffRepository.PresentedHandoff(
            "handoff-trade",
            SleeperManualCounterHandoffRepository.JOURNAL_POLICY_ID,
            SleeperManualCounterHandoffService.SERVICE_ID,
            SleeperPlatformCapabilityPolicy.POLICY_ID,
            TradeCounterExecutionRequestRepository.REQUEST_POLICY_ID,
            "claim-trade",
            "attempt-trade",
            "grant-trade",
            FINGERPRINT,
            TradeCounterAuthorizationPolicy.Action.SUBMIT_COUNTER_TRADE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.LEAGUE, "l1"),
            TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON.name(),
            PAYLOAD_HASH,
            SleeperManualCounterHandoffService.ReconciliationMode.SLEEPER_TRANSACTION_READBACK,
            PRESENTED_AT);
        var request = request(
            handoff.grantId(), handoff.handoffId(), handoff.payloadSha256(),
            SleeperManualMessageAcknowledgmentPolicy.REQUIRED_CONFIRMATION,
            ACKNOWLEDGED_AT);

        var decision = SleeperManualMessageAcknowledgmentPolicy.acknowledge(handoff, request);

        assertEquals(SleeperManualMessageAcknowledgmentPolicy.State.INCONCLUSIVE, decision.state());
        assertEquals(
            SleeperManualMessageAcknowledgmentPolicy.ReasonCode.HANDOFF_NOT_MANUAL_MESSAGE,
            decision.reasonCode());
        assertEquals(
            SleeperManualMessageAcknowledgmentPolicy.LocalCompletionEligibility.NONE,
            decision.localCompletionEligibility());
    }

    private static SleeperManualCounterHandoffRepository.PresentedHandoff messageHandoff() {
        return new SleeperManualCounterHandoffRepository.PresentedHandoff(
            "handoff-1",
            SleeperManualCounterHandoffRepository.JOURNAL_POLICY_ID,
            SleeperManualCounterHandoffService.SERVICE_ID,
            SleeperPlatformCapabilityPolicy.POLICY_ID,
            TradeCounterExecutionRequestRepository.REQUEST_POLICY_ID,
            "claim-1",
            "attempt-1",
            "grant-1",
            FINGERPRINT,
            TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE,
            new TradeCounterAuthorizationPolicy.Destination(
                TradeCounterAuthorizationPolicy.DestinationType.MANAGER, "manager-22"),
            TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT.name(),
            PAYLOAD_HASH,
            SleeperManualCounterHandoffService.ReconciliationMode.NO_OFFICIAL_READBACK,
            PRESENTED_AT);
    }

    private static SleeperManualMessageAcknowledgmentPolicy.AcknowledgmentRequest request(
        String grantId,
        String handoffId,
        String payloadHash,
        String confirmation,
        Instant acknowledgedAt) {
        return new SleeperManualMessageAcknowledgmentPolicy.AcknowledgmentRequest(
            grantId, handoffId, payloadHash, confirmation, acknowledgedAt);
    }
}
