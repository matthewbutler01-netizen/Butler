package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Leave-one-season-transition-out stability diagnostics for the governed local aging smoother. */
public final class AgingModelTransitionStabilityAnalyzer {
    private final AgingModelSampleAuditAnalyzer sampleAudit;

    public AgingModelTransitionStabilityAnalyzer(Database database) {
        this.sampleAudit = new AgingModelSampleAuditAnalyzer(Objects.requireNonNull(database, "database must not be null"));
    }

    public StabilityReport analyze() throws SQLException {
        var audit = sampleAudit.analyze();
        List<CellStability> cells = new ArrayList<>();
        List<LeaveOutDiagnostic> leaveOuts = new ArrayList<>();

        for (var target : audit.cells()) {
            List<AgingModelSampleAuditAnalyzer.AgingObservation> pooled = audit.observations().stream()
                .filter(o -> o.position().equals(target.position()))
                .filter(o -> o.metric() == target.metric())
                .filter(o -> Math.abs(o.age() - target.age()) <= 1)
                .toList();
            if (pooled.isEmpty()) continue;

            double baseline = medianDelta(pooled);
            Map<TransitionKey, List<AgingModelSampleAuditAnalyzer.AgingObservation>> byTransition = new LinkedHashMap<>();
            for (var observation : pooled) {
                byTransition.computeIfAbsent(new TransitionKey(observation.startSeason(), observation.endSeason()),
                    ignored -> new ArrayList<>()).add(observation);
            }

            List<Double> absoluteShifts = new ArrayList<>();
            int noRemainingSupport = 0;
            LeaveOutDiagnostic mostInfluential = null;
            for (var transition : byTransition.keySet()) {
                List<AgingModelSampleAuditAnalyzer.AgingObservation> remaining = pooled.stream()
                    .filter(o -> o.startSeason() != transition.startSeason() || o.endSeason() != transition.endSeason())
                    .toList();
                if (remaining.isEmpty()) {
                    noRemainingSupport++;
                    continue;
                }
                double leaveOutMedian = medianDelta(remaining);
                double shift = leaveOutMedian - baseline;
                double absoluteShift = Math.abs(shift);
                absoluteShifts.add(absoluteShift);
                var diagnostic = new LeaveOutDiagnostic(target.position(), target.metric(), target.age(),
                    transition.startSeason(), transition.endSeason(), pooled.size(), remaining.size(),
                    baseline, leaveOutMedian, shift, absoluteShift);
                leaveOuts.add(diagnostic);
                if (mostInfluential == null || diagnostic.absoluteShift() > mostInfluential.absoluteShift()
                    || (diagnostic.absoluteShift() == mostInfluential.absoluteShift()
                        && diagnostic.endSeason() < mostInfluential.endSeason())) {
                    mostInfluential = diagnostic;
                }
            }

            double medianAbs = absoluteShifts.isEmpty() ? Double.NaN
                : AgingModelSampleAuditAnalyzer.percentile(absoluteShifts, .5);
            double p75Abs = absoluteShifts.isEmpty() ? Double.NaN
                : AgingModelSampleAuditAnalyzer.percentile(absoluteShifts, .75);
            double maxAbs = absoluteShifts.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
            cells.add(new CellStability(target.position(), target.metric(), target.age(), pooled.size(),
                byTransition.size(), absoluteShifts.size(), noRemainingSupport, baseline,
                medianAbs, p75Abs, maxAbs,
                mostInfluential == null ? null : mostInfluential.startSeason(),
                mostInfluential == null ? null : mostInfluential.endSeason()));
        }

        cells.sort(Comparator.comparing(CellStability::position)
            .thenComparing(cell -> cell.metric().name())
            .thenComparingInt(CellStability::age));
        leaveOuts.sort(Comparator.comparing(LeaveOutDiagnostic::position)
            .thenComparing(value -> value.metric().name())
            .thenComparingInt(LeaveOutDiagnostic::age)
            .thenComparingInt(LeaveOutDiagnostic::endSeason));
        return new StabilityReport(audit.metricObservations(), List.copyOf(cells), List.copyOf(leaveOuts));
    }

    private static double medianDelta(List<AgingModelSampleAuditAnalyzer.AgingObservation> observations) {
        return AgingModelSampleAuditAnalyzer.percentile(
            observations.stream().map(AgingModelSampleAuditAnalyzer.AgingObservation::delta).toList(), .5);
    }

    private record TransitionKey(int startSeason, int endSeason) {}

    public record LeaveOutDiagnostic(String position, AgingModelSampleAuditAnalyzer.Metric metric, int age,
                                     int startSeason, int endSeason, int baselineObservations,
                                     int remainingObservations, double baselineMedianDelta,
                                     double leaveOutMedianDelta, double shift, double absoluteShift) {}

    public record CellStability(String position, AgingModelSampleAuditAnalyzer.Metric metric, int age,
                                int pooledObservations, int distinctSeasonTransitions,
                                int evaluatedTransitionRemovals, int removalsWithoutRemainingSupport,
                                double baselineMedianDelta, double medianAbsoluteShift,
                                double absoluteShiftP75, double maximumAbsoluteShift,
                                Integer mostInfluentialStartSeason, Integer mostInfluentialEndSeason) {}

    public record StabilityReport(int metricObservations, List<CellStability> cells,
                                  List<LeaveOutDiagnostic> leaveOuts) {
        public StabilityReport {
            cells = List.copyOf(Objects.requireNonNull(cells, "cells must not be null"));
            leaveOuts = List.copyOf(Objects.requireNonNull(leaveOuts, "leaveOuts must not be null"));
        }
        public int cellsAnalyzed() { return cells.size(); }
        public int evaluatedTransitionRemovals() {
            return cells.stream().mapToInt(CellStability::evaluatedTransitionRemovals).sum();
        }
        public int removalsWithoutRemainingSupport() {
            return cells.stream().mapToInt(CellStability::removalsWithoutRemainingSupport).sum();
        }
    }
}
