package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Audits governed publication coverage across each position's observed age span without choosing an age cutoff. */
public final class AgingModelPositionAgeCoverageAnalyzer {
    private static final List<String> POSITIONS = List.of("QB", "RB", "WR", "TE");
    private final AgingModelLocalSmootherAnalyzer smoother;

    public AgingModelPositionAgeCoverageAnalyzer(Database database) {
        this.smoother = new AgingModelLocalSmootherAnalyzer(
            Objects.requireNonNull(database, "database must not be null"));
    }

    public CoverageReport analyze() throws SQLException {
        return summarize(smoother.analyze());
    }

    static CoverageReport summarize(AgingModelLocalSmootherAnalyzer.LocalSmootherReport report) {
        Objects.requireNonNull(report, "report must not be null");
        List<PositionCoverage> positions = new ArrayList<>();

        for (String position : POSITIONS) {
            List<Integer> observedAges = report.cells().stream()
                .filter(cell -> cell.position().equals(position))
                .map(AgingModelLocalSmootherAnalyzer.SmoothedCell::age)
                .distinct()
                .sorted()
                .toList();
            if (observedAges.isEmpty()) {
                positions.add(new PositionCoverage(position, null, null, List.of()));
                continue;
            }

            int minimumAge = observedAges.getFirst();
            int maximumAge = observedAges.getLast();
            List<AgeCoverage> ages = new ArrayList<>();
            for (int age = minimumAge; age <= maximumAge; age++) {
                var evidence = AgingModelPositionAgeEvidenceAnalyzer.resolve(report, position, age);
                ages.add(new AgeCoverage(age, classify(evidence), evidence.publishedMetrics(),
                    evidence.belowSupportMetrics(), evidence.notObservedMetrics(), evidence.metrics().size()));
            }
            positions.add(new PositionCoverage(position, minimumAge, maximumAge, ages));
        }

        return new CoverageReport(
            AgingModelSupportPolicy.POLICY_ID,
            AgingModelSupportPolicy.MINIMUM_DISTINCT_SEASON_TRANSITIONS,
            report.profileSource(),
            report.productionSource(),
            positions);
    }

    private static Status classify(AgingModelPositionAgeEvidenceAnalyzer.PositionAgeEvidenceReport evidence) {
        int total = evidence.metrics().size();
        if (evidence.publishedMetrics() == total) return Status.FULL;
        if (evidence.publishedMetrics() > 0) return Status.PARTIAL;
        if (evidence.belowSupportMetrics() > 0) return Status.BELOW_SUPPORT;
        return Status.NOT_OBSERVED;
    }

    public enum Status { FULL, PARTIAL, BELOW_SUPPORT, NOT_OBSERVED }

    public record AgeCoverage(int age,
                              Status status,
                              int publishedMetrics,
                              int belowSupportMetrics,
                              int notObservedMetrics,
                              int totalMetrics) {
        public AgeCoverage {
            Objects.requireNonNull(status, "status must not be null");
            if (age < 0) throw new IllegalArgumentException("age must not be negative");
            if (publishedMetrics < 0 || belowSupportMetrics < 0 || notObservedMetrics < 0 || totalMetrics < 0) {
                throw new IllegalArgumentException("metric counts must not be negative");
            }
            if (publishedMetrics + belowSupportMetrics + notObservedMetrics != totalMetrics) {
                throw new IllegalArgumentException("metric status counts must equal total metrics");
            }
        }
    }

    public record PositionCoverage(String position,
                                   Integer minimumObservedAge,
                                   Integer maximumObservedAge,
                                   List<AgeCoverage> ages) {
        public PositionCoverage {
            Objects.requireNonNull(position, "position must not be null");
            ages = List.copyOf(Objects.requireNonNull(ages, "ages must not be null"));
        }

        public int fullAges() { return count(Status.FULL); }
        public int partialAges() { return count(Status.PARTIAL); }
        public int belowSupportAges() { return count(Status.BELOW_SUPPORT); }
        public int notObservedAges() { return count(Status.NOT_OBSERVED); }

        private int count(Status status) {
            return (int) ages.stream().filter(age -> age.status() == status).count();
        }
    }

    public record CoverageReport(String supportPolicyId,
                                 int minimumDistinctSeasonTransitions,
                                 String profileSource,
                                 String productionSource,
                                 List<PositionCoverage> positions) {
        public CoverageReport {
            Objects.requireNonNull(supportPolicyId, "supportPolicyId must not be null");
            Objects.requireNonNull(profileSource, "profileSource must not be null");
            Objects.requireNonNull(productionSource, "productionSource must not be null");
            positions = List.copyOf(Objects.requireNonNull(positions, "positions must not be null"));
        }

        public List<AgeCoverage> allAges() {
            return positions.stream()
                .flatMap(position -> position.ages().stream())
                .sorted(Comparator.comparingInt(AgeCoverage::age))
                .toList();
        }
    }
}
