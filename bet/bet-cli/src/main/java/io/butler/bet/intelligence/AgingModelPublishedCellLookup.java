package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

/** Resolves one aging-model cell while failing closed when governed publication support is absent. */
public final class AgingModelPublishedCellLookup {
    private final AgingModelLocalSmootherAnalyzer smoother;

    public AgingModelPublishedCellLookup(Database database) {
        this.smoother = new AgingModelLocalSmootherAnalyzer(
            Objects.requireNonNull(database, "database must not be null"));
    }

    public LookupResult lookup(String position, AgingModelSampleAuditAnalyzer.Metric metric, int age)
        throws SQLException {
        return resolve(smoother.analyze(), position, metric, age);
    }

    static LookupResult resolve(AgingModelLocalSmootherAnalyzer.LocalSmootherReport report,
                                String position,
                                AgingModelSampleAuditAnalyzer.Metric metric,
                                int age) {
        Objects.requireNonNull(report, "report must not be null");
        Objects.requireNonNull(metric, "metric must not be null");
        String normalizedPosition = normalizePosition(position);
        if (age < 0) throw new IllegalArgumentException("age must not be negative");

        var cell = report.cells().stream()
            .filter(candidate -> candidate.position().equals(normalizedPosition))
            .filter(candidate -> candidate.metric() == metric)
            .filter(candidate -> candidate.age() == age)
            .findFirst()
            .orElse(null);

        if (cell == null) {
            return new LookupResult(Status.NOT_OBSERVED, AgingModelSupportPolicy.POLICY_ID,
                AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS, null);
        }
        if (!AgingModelSupportPolicy.isPublicationEligible(cell.distinctSeasonTransitions())) {
            return new LookupResult(Status.BELOW_SUPPORT, AgingModelSupportPolicy.POLICY_ID,
                AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS, null);
        }
        return new LookupResult(Status.PUBLISHED, AgingModelSupportPolicy.POLICY_ID,
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS, cell);
    }

    private static String normalizePosition(String position) {
        if (position == null || position.isBlank()) {
            throw new IllegalArgumentException("position must not be blank");
        }
        return position.trim().toUpperCase(Locale.ROOT);
    }

    public enum Status { PUBLISHED, BELOW_SUPPORT, NOT_OBSERVED }

    public record LookupResult(Status status,
                               String supportPolicyId,
                               int minimumDistinctSeasonTransitions,
                               AgingModelLocalSmootherAnalyzer.SmoothedCell cell) {
        public LookupResult {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(supportPolicyId, "supportPolicyId must not be null");
            if (status != Status.PUBLISHED && cell != null) {
                throw new IllegalArgumentException("unpublished lookup result must not expose a cell");
            }
            if (status == Status.PUBLISHED && cell == null) {
                throw new IllegalArgumentException("published lookup result requires a cell");
            }
        }

        public boolean available() {
            return status == Status.PUBLISHED;
        }
    }
}
