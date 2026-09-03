package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Compares normalized instability by position and age band across candidate support thresholds. */
public final class AgingModelAgeBandStabilityAnalyzer {
    private static final List<Integer> CANDIDATE_THRESHOLDS = List.of(1, 3, 5, 10, 15, 20, 25);
    private static final List<String> POSITIONS = List.of("QB", "RB", "WR", "TE");
    private final AgingModelNormalizedStabilityAnalyzer normalized;

    public AgingModelAgeBandStabilityAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.normalized = new AgingModelNormalizedStabilityAnalyzer(database);
    }

    public AgeBandStabilityReport analyze() throws SQLException {
        var report = normalized.analyze();
        List<AgeBandDiagnostic> diagnostics = new ArrayList<>();
        for (int threshold : CANDIDATE_THRESHOLDS) {
            for (String position : POSITIONS) {
                for (AgeBand ageBand : AgeBand.values()) {
                    int baselineCells = (int) report.cells().stream()
                        .filter(cell -> cell.maximumShiftToHoldoutMae() != null)
                        .filter(cell -> cell.position().equals(position))
                        .filter(cell -> AgeBand.fromAge(cell.age()) == ageBand)
                        .count();
                    double[] ratios = report.cells().stream()
                        .filter(cell -> cell.maximumShiftToHoldoutMae() != null)
                        .filter(cell -> cell.distinctSeasonTransitions() >= threshold)
                        .filter(cell -> cell.position().equals(position))
                        .filter(cell -> AgeBand.fromAge(cell.age()) == ageBand)
                        .mapToDouble(cell -> cell.maximumShiftToHoldoutMae())
                        .sorted()
                        .toArray();
                    diagnostics.add(new AgeBandDiagnostic(threshold, position, ageBand, baselineCells, ratios.length,
                        baselineCells == 0 ? 0.0 : ratios.length / (double) baselineCells,
                        percentile(ratios, 0.50), percentile(ratios, 0.90),
                        ratios.length == 0 ? null : ratios[ratios.length - 1]));
                }
            }
        }
        return new AgeBandStabilityReport(report.cellsAnalyzed(), report.normalizedCells(),
            List.copyOf(diagnostics));
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

    public enum AgeBand {
        UNDER_25("under-25"),
        AGE_25_TO_29("25-29"),
        AGE_30_TO_34("30-34"),
        AGE_35_PLUS("35-plus");

        private final String label;

        AgeBand(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        static AgeBand fromAge(int age) {
            if (age < 25) return UNDER_25;
            if (age < 30) return AGE_25_TO_29;
            if (age < 35) return AGE_30_TO_34;
            return AGE_35_PLUS;
        }
    }

    public record AgeBandDiagnostic(int minimumDistinctSeasonTransitions, String position,
                                    AgeBand ageBand, int baselineCells, int retainedCells,
                                    double retainedFraction,
                                    Double medianMaximumShiftToHoldoutMae,
                                    Double p90MaximumShiftToHoldoutMae,
                                    Double maximumShiftToHoldoutMae) {}

    public record AgeBandStabilityReport(int cellsAnalyzed, int normalizedCells,
                                         List<AgeBandDiagnostic> bands) {
        public AgeBandStabilityReport {
            bands = List.copyOf(Objects.requireNonNull(bands, "bands must not be null"));
        }
    }
}
