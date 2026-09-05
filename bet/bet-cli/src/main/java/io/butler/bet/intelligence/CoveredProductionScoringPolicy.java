package io.butler.bet.intelligence;

import io.butler.bet.domain.PlayerSeasonProduction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Deterministically scores one raw player-season production record using only scoring rules that
 * Butler can represent exactly. Unsupported nonzero rules fail closed instead of being omitted.
 */
public final class CoveredProductionScoringPolicy {
    public static final String POLICY_ID =
        "covered-production-scoring-v1-exact-supported-rules-only";

    public ScoreResult score(PlayerSeasonProduction production, Map<String, Double> scoringSettings) {
        Objects.requireNonNull(production, "production must not be null");
        Objects.requireNonNull(scoringSettings, "scoringSettings must not be null");
        if (scoringSettings.isEmpty()) {
            throw new IllegalStateException("Exact scoring requires persisted league scoring settings");
        }

        List<ScoreComponent> components = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : new TreeMap<>(scoringSettings).entrySet()) {
            String statKey = requireText(entry.getKey(), "statKey");
            Double boxedPoints = Objects.requireNonNull(entry.getValue(), "pointsPerUnit must not be null");
            double points = boxedPoints;
            if (!Double.isFinite(points)) {
                throw new IllegalArgumentException("pointsPerUnit must be finite for " + statKey);
            }
            if (Double.compare(points, 0.0d) == 0) continue;

            SupportedScoringStat supported = SupportedScoringStat.find(statKey);
            if (supported == null) {
                throw new IllegalStateException(
                    "Exact scoring blocked by unsupported nonzero rule: " + statKey);
            }

            int rawValue = supported.value(production);
            BigDecimal pointsPerUnit = BigDecimal.valueOf(points);
            BigDecimal contribution = pointsPerUnit.multiply(BigDecimal.valueOf(rawValue));
            total = total.add(contribution);
            components.add(new ScoreComponent(
                statKey,
                supported.productionField(),
                rawValue,
                pointsPerUnit,
                contribution));
        }

        return new ScoreResult(
            POLICY_ID,
            production.id(),
            production.playerId(),
            production.season(),
            total,
            List.copyOf(components));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record ScoreComponent(
        String statKey,
        String productionField,
        int rawValue,
        BigDecimal pointsPerUnit,
        BigDecimal contribution) {
        public ScoreComponent {
            requireText(statKey, "statKey");
            requireText(productionField, "productionField");
            Objects.requireNonNull(pointsPerUnit, "pointsPerUnit must not be null");
            Objects.requireNonNull(contribution, "contribution must not be null");
        }
    }

    public record ScoreResult(
        String policyId,
        String productionId,
        String playerId,
        int season,
        BigDecimal totalPoints,
        List<ScoreComponent> components) {
        public ScoreResult {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            requireText(productionId, "productionId");
            requireText(playerId, "playerId");
            if (season <= 0) throw new IllegalArgumentException("season must be positive");
            Objects.requireNonNull(totalPoints, "totalPoints must not be null");
            components = List.copyOf(Objects.requireNonNull(components, "components must not be null"));
        }
    }
}
