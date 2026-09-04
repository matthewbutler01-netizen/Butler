package io.butler.bet.intelligence;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Produces a deterministic audit fingerprint for a bound, materialized COUNTER proposal.
 * The fingerprint identifies reviewed evidence; it is not permission to send or execute a trade.
 */
public final class TradeCounterProposalIdentityPolicy {
    public static final String POLICY_ID =
        "trade-counter-proposal-identity-v1-canonical-bound-packages-sha256";
    public static final String ALGORITHM = "SHA-256";
    public static final String CANONICAL_VERSION = "1";

    private TradeCounterProposalIdentityPolicy() {}

    public enum State {
        IDENTIFIED,
        NO_IDENTITY,
        INCONCLUSIVE
    }

    public enum ReasonCode {
        GOVERNED_COUNTER_IDENTIFIED,
        COUNTER_PROPOSAL_NO_ACTION,
        COUNTER_PROPOSAL_INCONCLUSIVE
    }

    public static Identity identify(
        TradeCounterProposalEnvelopePolicy.Envelope envelope,
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        Objects.requireNonNull(materialized, "materialized must not be null");
        requireMatchingArtifacts(envelope, materialized);

        return switch (envelope.action()) {
            case INCONCLUSIVE -> {
                if (materialized.state() != TradeCounterMaterializedPackagePolicy.State.INCONCLUSIVE) {
                    throw new IllegalArgumentException(
                        "inconclusive proposal requires inconclusive materialized package state");
                }
                yield result(
                    envelope,
                    State.INCONCLUSIVE,
                    ReasonCode.COUNTER_PROPOSAL_INCONCLUSIVE,
                    null);
            }
            case NO_ACTION -> {
                if (materialized.state() != TradeCounterMaterializedPackagePolicy.State.NO_PACKAGE) {
                    throw new IllegalArgumentException(
                        "NO_ACTION proposal requires NO_PACKAGE materialized state");
                }
                yield result(
                    envelope,
                    State.NO_IDENTITY,
                    ReasonCode.COUNTER_PROPOSAL_NO_ACTION,
                    null);
            }
            case COUNTER -> {
                if (materialized.state() != TradeCounterMaterializedPackagePolicy.State.MATERIALIZED) {
                    throw new IllegalArgumentException(
                        "COUNTER proposal requires MATERIALIZED package state");
                }
                yield result(
                    envelope,
                    State.IDENTIFIED,
                    ReasonCode.GOVERNED_COUNTER_IDENTIFIED,
                    fingerprint(envelope, materialized));
            }
        };
    }

    private static void requireMatchingArtifacts(
        TradeCounterProposalEnvelopePolicy.Envelope envelope,
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized) {
        if (!TradeCounterProposalEnvelopePolicy.POLICY_ID.equals(materialized.envelopePolicyId())
            || !envelope.leagueId().equals(materialized.leagueId())
            || envelope.season() != materialized.season()
            || !envelope.source().equals(materialized.source())
            || !Objects.equals(envelope.minimumAsOfDate(), materialized.minimumAsOfDate())
            || envelope.perspective() != materialized.perspective()
            || !envelope.originalSideA().equals(materialized.originalSideA())
            || !envelope.originalSideB().equals(materialized.originalSideB())) {
            throw new IllegalArgumentException(
                "counter proposal envelope and materialized packages must match");
        }
    }

    private static String fingerprint(
        TradeCounterProposalEnvelopePolicy.Envelope envelope,
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized) {
        var proposal = Objects.requireNonNull(envelope.proposal(), "COUNTER envelope requires proposal");
        byte[] canonical = canonicalBytes(envelope, materialized, proposal);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(ALGORITHM).digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " is unavailable", e);
        }
    }

    private static byte[] canonicalBytes(
        TradeCounterProposalEnvelopePolicy.Envelope envelope,
        TradeCounterMaterializedPackagePolicy.MaterializedCounter materialized,
        TradeCounterProposalPolicy.Proposal proposal) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                writeText(output, POLICY_ID);
                writeText(output, CANONICAL_VERSION);
                writeText(output, TradeCounterProposalPolicy.POLICY_ID);
                writeText(output, TradeCounterProposalEnvelopePolicy.POLICY_ID);
                writeText(output, TradeCounterMaterializedPackagePolicy.POLICY_ID);
                writeText(output, envelope.leagueId());
                output.writeInt(envelope.season());
                writeText(output, envelope.source());
                writeDate(output, envelope.minimumAsOfDate());
                writeText(output, envelope.perspective().name());
                writePackage(output, envelope.originalSideA());
                writePackage(output, envelope.originalSideB());
                writePackage(output, materialized.revisedSideA());
                writePackage(output, materialized.revisedSideB());
                output.writeInt(proposal.marketRank());
                writeText(output, proposal.adjustmentType().name());
                writeText(output, proposal.side().name());
                writeText(output, proposal.assetType().name());
                writeText(output, proposal.assetId());
                writeText(output, proposal.teamId());
                writeDouble(output, proposal.assetValue());
                writeDate(output, proposal.asOfDate());
                writeDouble(output, proposal.requiredValueChange());
                writeDouble(output, proposal.excessValue());
                writeDouble(output, proposal.resultingSideAValue());
                writeDouble(output, proposal.resultingSideBValue());
                writeDouble(output, proposal.resultingGapPercent());
                writeText(output, proposal.resultingFairness().name());
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("unexpected in-memory canonical encoding failure", e);
        }
    }

    private static void writePackage(
        DataOutputStream output,
        TradeAssetAnalyzer.TradePackage tradePackage) throws IOException {
        writeTexts(output, tradePackage.playerIds());
        writeTexts(output, tradePackage.draftPickIds());
    }

    private static void writeTexts(DataOutputStream output, List<String> values) throws IOException {
        output.writeInt(values.size());
        for (String value : values) writeText(output, value);
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] encoded = Objects.requireNonNull(value, "canonical text must not be null")
            .getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static void writeDate(DataOutputStream output, LocalDate value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) output.writeLong(value.toEpochDay());
    }

    private static void writeDouble(DataOutputStream output, double value) throws IOException {
        output.writeLong(Double.doubleToLongBits(value));
    }

    private static Identity result(
        TradeCounterProposalEnvelopePolicy.Envelope envelope,
        State state,
        ReasonCode reasonCode,
        String fingerprint) {
        return new Identity(
            POLICY_ID,
            TradeCounterProposalEnvelopePolicy.POLICY_ID,
            TradeCounterMaterializedPackagePolicy.POLICY_ID,
            ALGORITHM,
            CANONICAL_VERSION,
            envelope.leagueId(),
            envelope.season(),
            envelope.source(),
            envelope.minimumAsOfDate(),
            envelope.perspective(),
            state,
            reasonCode,
            fingerprint);
    }

    public record Identity(
        String policyId,
        String envelopePolicyId,
        String materializedPackagePolicyId,
        String algorithm,
        String canonicalVersion,
        String leagueId,
        int season,
        String source,
        LocalDate minimumAsOfDate,
        TradeTeamPerspectiveRecommendationPolicy.Perspective perspective,
        State state,
        ReasonCode reasonCode,
        String fingerprint) {
        public Identity {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!TradeCounterProposalEnvelopePolicy.POLICY_ID.equals(envelopePolicyId)) {
                throw new IllegalArgumentException("unexpected envelopePolicyId");
            }
            if (!TradeCounterMaterializedPackagePolicy.POLICY_ID.equals(materializedPackagePolicyId)) {
                throw new IllegalArgumentException("unexpected materializedPackagePolicyId");
            }
            if (!ALGORITHM.equals(algorithm)) throw new IllegalArgumentException("unexpected algorithm");
            if (!CANONICAL_VERSION.equals(canonicalVersion)) {
                throw new IllegalArgumentException("unexpected canonicalVersion");
            }
            if (leagueId == null || leagueId.isBlank()) throw new IllegalArgumentException("leagueId must not be blank");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
            if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
            Objects.requireNonNull(perspective, "perspective must not be null");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            if (state == State.IDENTIFIED) {
                if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("IDENTIFIED requires lowercase SHA-256 fingerprint");
                }
            } else if (fingerprint != null) {
                throw new IllegalArgumentException("non-identified state cannot carry fingerprint");
            }
        }
    }
}
