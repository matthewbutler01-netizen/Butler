package io.butler.bet.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.butler.bet.data.TradeCounterExecutionAttemptRepository;
import io.butler.bet.intelligence.TradeAssetAnalyzer;
import io.butler.bet.intelligence.TradeCounterAuthorizationPolicy;
import io.butler.bet.intelligence.TradeCounterMaterializedPackagePolicy;
import io.butler.bet.intelligence.TradeCounterNegotiationMessagePolicy;
import io.butler.bet.intelligence.TradeCounterProposalIdentityPolicy;

import java.util.Objects;

/**
 * Materializes exact execution-journal payload bytes only from a freshly revalidated governed counter.
 * The trade JSON is Butler's manual-handoff contract; it is not a Sleeper write API request.
 */
public final class TradeCounterExecutionPayloadPolicy {
    public static final String POLICY_ID =
        "trade-counter-execution-payload-v1-fresh-authorized-governed-artifacts";
    public static final String TRADE_PAYLOAD_SCHEMA = "butler-counter-trade-request-v1";

    private static final ObjectMapper JSON = new ObjectMapper();

    private TradeCounterExecutionPayloadPolicy() {}

    public enum State {
        PAYLOAD_AVAILABLE,
        NOT_AVAILABLE,
        INCONCLUSIVE
    }

    public enum ReasonCode {
        GOVERNED_NEGOTIATION_MESSAGE_PAYLOAD,
        GOVERNED_COUNTER_TRADE_PAYLOAD,
        FRESH_PROPOSAL_DRIFTED,
        FRESH_PROPOSAL_INCONCLUSIVE,
        FRESH_COUNTER_ARTIFACTS_UNAVAILABLE
    }

    public static Result materialize(
        TradeCounterAuthorizationPolicy.AuthorizationGrant grant,
        TradeCounterProposalIdentityPolicy.Identity freshIdentity,
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized,
        TradeCounterNegotiationMessagePolicy.MessageResult message) {
        Objects.requireNonNull(grant, "grant must not be null");
        Objects.requireNonNull(freshIdentity, "freshIdentity must not be null");
        Objects.requireNonNull(materialized, "materialized must not be null");
        Objects.requireNonNull(message, "message must not be null");

        var revalidation = TradeCounterAuthorizationPolicy.revalidate(grant, freshIdentity);
        if (revalidation.state() == TradeCounterAuthorizationPolicy.RevalidationState.INCONCLUSIVE) {
            return result(State.INCONCLUSIVE, ReasonCode.FRESH_PROPOSAL_INCONCLUSIVE, null);
        }
        if (revalidation.state() == TradeCounterAuthorizationPolicy.RevalidationState.DRIFTED) {
            return result(State.NOT_AVAILABLE, ReasonCode.FRESH_PROPOSAL_DRIFTED, null);
        }

        requireSameCoordinates(freshIdentity, materialized, message);
        if (freshIdentity.state() != TradeCounterProposalIdentityPolicy.State.IDENTIFIED
            || materialized.state() != TradeCounterMaterializedPackagePolicy.State.MATERIALIZED
            || message.state() != TradeCounterNegotiationMessagePolicy.State.MESSAGE_AVAILABLE) {
            return result(State.NOT_AVAILABLE, ReasonCode.FRESH_COUNTER_ARTIFACTS_UNAVAILABLE, null);
        }

        Payload payload = switch (grant.action()) {
            case SEND_NEGOTIATION_MESSAGE -> new Payload(
                POLICY_ID,
                grant.grantId(),
                freshIdentity.fingerprint(),
                grant.action(),
                grant.destination(),
                TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT,
                message.text());
            case SUBMIT_COUNTER_TRADE -> new Payload(
                POLICY_ID,
                grant.grantId(),
                freshIdentity.fingerprint(),
                grant.action(),
                grant.destination(),
                TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON,
                tradeJson(grant, freshIdentity, materialized));
        };

        return result(
            State.PAYLOAD_AVAILABLE,
            grant.action() == TradeCounterAuthorizationPolicy.Action.SEND_NEGOTIATION_MESSAGE
                ? ReasonCode.GOVERNED_NEGOTIATION_MESSAGE_PAYLOAD
                : ReasonCode.GOVERNED_COUNTER_TRADE_PAYLOAD,
            payload);
    }

    private static void requireSameCoordinates(
        TradeCounterProposalIdentityPolicy.Identity identity,
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized,
        TradeCounterNegotiationMessagePolicy.MessageResult message) {
        boolean materializedMatches = identity.leagueId().equals(materialized.leagueId())
            && identity.season() == materialized.season()
            && identity.source().equals(materialized.source())
            && Objects.equals(identity.minimumAsOfDate(), materialized.minimumAsOfDate())
            && identity.perspective() == materialized.perspective()
            && TradeCounterMaterializedPackagePolicy.POLICY_ID.equals(identity.materializedPackagePolicyId());
        boolean messageMatches = identity.leagueId().equals(message.leagueId())
            && identity.season() == message.season()
            && identity.source().equals(message.source())
            && Objects.equals(identity.minimumAsOfDate(), message.minimumAsOfDate())
            && identity.perspective() == message.perspective();
        if (!materializedMatches || !messageMatches) {
            throw new IllegalArgumentException("fresh counter identity, packages, and message coordinates must match");
        }
    }

    private static String tradeJson(
        TradeCounterAuthorizationPolicy.AuthorizationGrant grant,
        TradeCounterProposalIdentityPolicy.Identity identity,
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schema", TRADE_PAYLOAD_SCHEMA);
        root.put("proposalFingerprint", identity.fingerprint());
        root.put("leagueId", identity.leagueId());
        root.put("season", identity.season());
        root.put("source", identity.source());
        if (identity.minimumAsOfDate() == null) root.putNull("minimumAsOfDate");
        else root.put("minimumAsOfDate", identity.minimumAsOfDate().toString());
        root.put("perspective", identity.perspective().name());
        root.put("destinationLeagueId", grant.destination().id());
        root.set("sideA", packageJson(materialized.revisedSideA()));
        root.set("sideB", packageJson(materialized.revisedSideB()));
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize governed counter trade payload", e);
        }
    }

    private static ObjectNode packageJson(TradeAssetAnalyzer.TradePackage tradePackage) {
        Objects.requireNonNull(tradePackage, "materialized trade package must not be null");
        ObjectNode node = JSON.createObjectNode();
        ArrayNode players = node.putArray("players");
        tradePackage.playerIds().forEach(players::add);
        ArrayNode picks = node.putArray("draftPicks");
        tradePackage.draftPickIds().forEach(picks::add);
        return node;
    }

    private static Result result(State state, ReasonCode reasonCode, Payload payload) {
        return new Result(POLICY_ID, state, reasonCode, payload);
    }

    public record Result(
        String policyId,
        State state,
        ReasonCode reasonCode,
        Payload payload) {
        public Result {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            if ((state == State.PAYLOAD_AVAILABLE) != (payload != null)) {
                throw new IllegalArgumentException("only PAYLOAD_AVAILABLE may carry payload");
            }
        }
    }

    public record Payload(
        String policyId,
        String grantId,
        String proposalFingerprint,
        TradeCounterAuthorizationPolicy.Action action,
        TradeCounterAuthorizationPolicy.Destination destination,
        TradeCounterExecutionAttemptRepository.PayloadKind payloadKind,
        String payloadText) {
        public Payload {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            requireText(grantId, "grantId");
            requireFingerprint(proposalFingerprint);
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(payloadKind, "payloadKind must not be null");
            if (payloadText == null || payloadText.isEmpty()) {
                throw new IllegalArgumentException("payloadText must not be empty");
            }
            boolean compatible = switch (action) {
                case SEND_NEGOTIATION_MESSAGE ->
                    destination.type() == TradeCounterAuthorizationPolicy.DestinationType.MANAGER
                        && payloadKind == TradeCounterExecutionAttemptRepository.PayloadKind.NEGOTIATION_MESSAGE_TEXT;
                case SUBMIT_COUNTER_TRADE ->
                    destination.type() == TradeCounterAuthorizationPolicy.DestinationType.LEAGUE
                        && payloadKind == TradeCounterExecutionAttemptRepository.PayloadKind.COUNTER_TRADE_REQUEST_JSON;
            };
            if (!compatible) {
                throw new IllegalArgumentException("execution payload action, destination, and kind are incompatible");
            }
        }
    }

    private static void requireFingerprint(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("proposalFingerprint must be lowercase SHA-256");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
