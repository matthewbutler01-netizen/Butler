package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Bundles governed published aging-model evidence for one position and age without interpretation. */
public final class AgingModelPositionAgeEvidenceAnalyzer {
    private final AgingModelLocalSmootherAnalyzer smoother;

    public AgingModelPositionAgeEvidenceAnalyzer(Database database) {
        this.smoother = new AgingModelLocalSmootherAnalyzer(
            Objects.requireNonNull(database, "database must not be null"));
    }

    public PositionAgeEvidenceReport analyze(String position, int age) throws SQLException {
        return resolve(smoother.analyze(), position, age);
    }

    static PositionAgeEvidenceReport resolve(AgingModelLocalSmootherAnalyzer.LocalSmootherReport report,
                                             String position,
                                             int age) {
        Objects.requireNonNull(report, "report must not be null");
        String normalizedPosition = normalizePosition(position);
        if (age < 0) throw new IllegalArgumentException("age must not be negative");

        List<MetricEvidence> metrics = metricsFor(normalizedPosition).stream()
            .map(metric -> new MetricEvidence(metric,
                AgingModelPublishedCellLookup.resolve(report, normalizedPosition, metric, age)))
            .toList();

        return new PositionAgeEvidenceReport(
            normalizedPosition,
            age,
            AgingModelSupportPolicy.POLICY_ID,
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
            report.profileSource(),
            report.productionSource(),
            metrics);
    }

    private static List<AgingModelSampleAuditAnalyzer.Metric> metricsFor(String position) {
        return switch (position) {
            case "QB" -> List.of(
                AgingModelSampleAuditAnalyzer.Metric.PASSING_YARDS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.PASSING_TOUCHDOWNS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.INTERCEPTIONS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RUSHING_TOUCHDOWNS_PER_GAME);
            case "RB" -> List.of(
                AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RUSHING_TOUCHDOWNS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RECEPTIONS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RECEIVING_TOUCHDOWNS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.FUMBLES_LOST_PER_GAME);
            case "WR" -> List.of(
                AgingModelSampleAuditAnalyzer.Metric.RECEPTIONS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RECEIVING_TOUCHDOWNS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RUSHING_YARDS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RUSHING_TOUCHDOWNS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.FUMBLES_LOST_PER_GAME);
            case "TE" -> List.of(
                AgingModelSampleAuditAnalyzer.Metric.RECEPTIONS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RECEIVING_YARDS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.RECEIVING_TOUCHDOWNS_PER_GAME,
                AgingModelSampleAuditAnalyzer.Metric.FUMBLES_LOST_PER_GAME);
            default -> throw new IllegalArgumentException("position must be one of QB, RB, WR, TE");
        };
    }

    private static String normalizePosition(String position) {
        if (position == null || position.isBlank()) throw new IllegalArgumentException("position must not be blank");
        String normalized = position.trim().toUpperCase(Locale.ROOT);
        if (!List.of("QB", "RB", "WR", "TE").contains(normalized)) {
            throw new IllegalArgumentException("position must be one of QB, RB, WR, TE");
        }
        return normalized;
    }

    public record MetricEvidence(AgingModelSampleAuditAnalyzer.Metric metric,
                                 AgingModelPublishedCellLookup.LookupResult lookup) {
        public MetricEvidence {
            Objects.requireNonNull(metric, "metric must not be null");
            Objects.requireNonNull(lookup, "lookup must not be null");
        }

        public AgingModelPublishedCellLookup.Status status() { return lookup.status(); }
        public boolean available() { return lookup.available(); }
        public AgingModelLocalSmootherAnalyzer.SmoothedCell cell() { return lookup.cell(); }
    }

    public record PositionAgeEvidenceReport(String position,
                                            int age,
                                            String supportPolicyId,
                                            int minimumDistinctSeasonTransitions,
                                            String profileSource,
                                            String productionSource,
                                            List<MetricEvidence> metrics) {
        public PositionAgeEvidenceReport {
            Objects.requireNonNull(position, "position must not be null");
            Objects.requireNonNull(supportPolicyId, "supportPolicyId must not be null");
            Objects.requireNonNull(profileSource, "profileSource must not be null");
            Objects.requireNonNull(productionSource, "productionSource must not be null");
            metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics must not be null"));
        }

        public int publishedMetrics() {
            return (int) metrics.stream().filter(MetricEvidence::available).count();
        }

        public int belowSupportMetrics() {
            return (int) metrics.stream()
                .filter(metric -> metric.status() == AgingModelPublishedCellLookup.Status.BELOW_SUPPORT)
                .count();
        }

        public int notObservedMetrics() {
            return (int) metrics.stream()
                .filter(metric -> metric.status() == AgingModelPublishedCellLookup.Status.NOT_OBSERVED)
                .count();
        }
    }
}
