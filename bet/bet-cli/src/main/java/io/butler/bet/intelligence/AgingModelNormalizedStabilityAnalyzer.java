package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Normalizes local-smoother transition influence to each position/metric's out-of-time error scale. */
public final class AgingModelNormalizedStabilityAnalyzer {
    private final AgingModelTransitionStabilityAnalyzer stability;
    private final AgingModelTemporalHoldoutAnalyzer holdout;

    public AgingModelNormalizedStabilityAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.stability = new AgingModelTransitionStabilityAnalyzer(database);
        this.holdout = new AgingModelTemporalHoldoutAnalyzer(database);
    }

    public NormalizedStabilityReport analyze() throws SQLException {
        var stabilityReport = stability.analyze();
        var holdoutReport = holdout.analyze();

        Map<DimensionKey, AgingModelTemporalHoldoutAnalyzer.DimensionDiagnostic> holdoutByDimension = new HashMap<>();
        for (var diagnostic : holdoutReport.dimensions()) {
            holdoutByDimension.put(new DimensionKey(diagnostic.position(), diagnostic.metric()), diagnostic);
        }

        List<NormalizedCell> cells = new ArrayList<>();
        int withoutUsableHoldoutScale = 0;
        for (var cell : stabilityReport.cells()) {
            var diagnostic = holdoutByDimension.get(new DimensionKey(cell.position(), cell.metric()));
            Double scale = diagnostic == null ? null : diagnostic.meanAbsoluteError();
            Double medianRatio = normalize(cell.medianAbsoluteShift(), scale);
            Double p75Ratio = normalize(cell.absoluteShiftP75(), scale);
            Double maxRatio = normalize(cell.maximumAbsoluteShift(), scale);
            if (maxRatio == null) withoutUsableHoldoutScale++;
            cells.add(new NormalizedCell(cell.position(), cell.metric(), cell.age(),
                cell.pooledObservations(), cell.distinctSeasonTransitions(), cell.evaluatedTransitionRemovals(),
                cell.removalsWithoutRemainingSupport(), cell.baselineMedianDelta(),
                cell.medianAbsoluteShift(), cell.absoluteShiftP75(), cell.maximumAbsoluteShift(),
                scale, medianRatio, p75Ratio, maxRatio,
                cell.mostInfluentialStartSeason(), cell.mostInfluentialEndSeason()));
        }

        cells.sort(Comparator.comparing(NormalizedCell::position)
            .thenComparing(cell -> cell.metric().name())
            .thenComparingInt(NormalizedCell::age));
        return new NormalizedStabilityReport(stabilityReport.metricObservations(), cells.size(),
            withoutUsableHoldoutScale, List.copyOf(cells));
    }

    private static Double normalize(double shift, Double scale) {
        if (scale == null || !Double.isFinite(scale) || scale <= 0.0 || !Double.isFinite(shift)) return null;
        return shift / scale;
    }

    private record DimensionKey(String position, AgingModelSampleAuditAnalyzer.Metric metric) {}

    public record NormalizedCell(String position, AgingModelSampleAuditAnalyzer.Metric metric, int age,
                                 int pooledObservations, int distinctSeasonTransitions,
                                 int evaluatedTransitionRemovals, int removalsWithoutRemainingSupport,
                                 double baselineMedianDelta, double medianAbsoluteShift,
                                 double absoluteShiftP75, double maximumAbsoluteShift,
                                 Double holdoutMeanAbsoluteError, Double medianShiftToHoldoutMae,
                                 Double p75ShiftToHoldoutMae, Double maximumShiftToHoldoutMae,
                                 Integer mostInfluentialStartSeason, Integer mostInfluentialEndSeason) {}

    public record NormalizedStabilityReport(int metricObservations, int cellsAnalyzed,
                                            int cellsWithoutUsableHoldoutScale,
                                            List<NormalizedCell> cells) {
        public NormalizedStabilityReport {
            cells = List.copyOf(Objects.requireNonNull(cells, "cells must not be null"));
        }
        public int normalizedCells() { return cellsAnalyzed - cellsWithoutUsableHoldoutScale; }
    }
}
