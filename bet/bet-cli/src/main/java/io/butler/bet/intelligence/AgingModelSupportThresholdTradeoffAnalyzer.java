package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Compares fixed transition-support candidate thresholds without selecting a governance cutoff. */
public final class AgingModelSupportThresholdTradeoffAnalyzer {
    private static final List<Integer> CANDIDATE_THRESHOLDS = List.of(1, 3, 5, 10, 15, 20, 25);
    private final AgingModelNormalizedStabilityAnalyzer normalized;

    public AgingModelSupportThresholdTradeoffAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.normalized = new AgingModelNormalizedStabilityAnalyzer(database);
    }

    public ThresholdTradeoffReport analyze() throws SQLException {
        var report = normalized.analyze();
        List<ThresholdDiagnostic> diagnostics = new ArrayList<>();
        for (int threshold : CANDIDATE_THRESHOLDS) {
            var retained = report.cells().stream()
                .filter(cell -> cell.distinctSeasonTransitions() >= threshold)
                .filter(cell -> cell.maximumShiftToHoldoutMae() != null)
                .toList();
            double[] ratios = retained.stream().mapToDouble(cell -> cell.maximumShiftToHoldoutMae()).sorted().toArray();
            diagnostics.add(new ThresholdDiagnostic(
                threshold,
                retained.size(),
                report.cellsAnalyzed() - retained.size(),
                report.cellsAnalyzed() == 0 ? 0.0 : retained.size() / (double) report.cellsAnalyzed(),
                percentile(ratios, 0.50),
                percentile(ratios, 0.75),
                percentile(ratios, 0.90),
                ratios.length == 0 ? null : ratios[ratios.length - 1]
            ));
        }
        return new ThresholdTradeoffReport(report.cellsAnalyzed(), report.normalizedCells(), List.copyOf(diagnostics));
    }

    private static Double percentile(double[] sorted, double p) {
        if (sorted.length == 0) return null;
        double index = (sorted.length - 1) * p;
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) return sorted[lower];
        double weight = index - lower;
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight;
    }

    public record ThresholdDiagnostic(int minimumDistinctSeasonTransitions,
                                      int retainedCells,
                                      int excludedCells,
                                      double retainedFraction,
                                      Double medianMaximumShiftToHoldoutMae,
                                      Double p75MaximumShiftToHoldoutMae,
                                      Double p90MaximumShiftToHoldoutMae,
                                      Double maximumShiftToHoldoutMae) {}

    public record ThresholdTradeoffReport(int cellsAnalyzed,
                                          int normalizedCells,
                                          List<ThresholdDiagnostic> thresholds) {
        public ThresholdTradeoffReport {
            thresholds = List.copyOf(Objects.requireNonNull(thresholds, "thresholds must not be null"));
        }
    }
}
