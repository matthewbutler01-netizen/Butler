package io.butler.bet.intelligence;

import java.util.Objects;

/**
 * Neutral supporting-evidence boundary for future decision packages.
 *
 * The contract intentionally has no numeric weight, score contribution, or recommendation action.
 * Consumers may present the signal as supporting context, but weighting/decision semantics must be
 * governed separately.
 */
public record DecisionSupportingEvidenceFlag(
    String subjectId,
    String category,
    String dimension,
    Signal signal,
    String summary,
    String policyId,
    String evidenceSource) {

    public DecisionSupportingEvidenceFlag {
        subjectId = requireText(subjectId, "subjectId");
        category = requireText(category, "category");
        dimension = requireText(dimension, "dimension");
        Objects.requireNonNull(signal, "signal must not be null");
        summary = requireText(summary, "summary");
        policyId = requireText(policyId, "policyId");
        evidenceSource = requireText(evidenceSource, "evidenceSource");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public enum Signal {
        FAVORABLE,
        UNFAVORABLE,
        INCONCLUSIVE
    }
}
