package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Enriches publication-eligible aging-model cells with validation, stability, uncertainty, and
 * training-span provenance. This analyzer adds no validation cutoff, score, or strategic interpretation.
 */
public final class AgingModelPublicationValidationAnalyzer {
    private final AgingModelPublishedSmootherAnalyzer published;
    private final AgingModelSampleAuditAnalyzer audit;
    private final AgingModelTemporalHoldoutAnalyzer holdout;
    private final AgingModelNormalizedStabilityAnalyzer stability;

    public AgingModelPublicationValidationAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.published = new AgingModelPublishedSmootherAnalyzer(database);
        this.audit = new AgingModelSampleAuditAnalyzer(database);
        this.holdout = new AgingModelTemporalHoldoutAnalyzer(database);
        this.stability = new AgingModelNormalizedStabilityAnalyzer(database);
    }

    public ValidationReport analyze() throws SQLException {
        var publishedReport = published.analyze();
        var auditReport = audit.analyze();
        var holdoutReport = holdout.analyze();
        var stabilityReport = stability.analyze();
        return enrich(publishedReport, auditReport.observations(), holdoutReport.dimensions(), stabilityReport.cells());
    }

    static ValidationReport enrich(
        AgingModelPublishedSmootherAnalyzer.PublishedSmootherReport publishedReport,
        List<AgingModelSampleAuditAnalyzer.AgingObservation> observations,
        List<AgingModelTemporalHoldoutAnalyzer.DimensionDiagnostic> holdoutDimensions,
        List<AgingModelNormalizedStabilityAnalyzer.NormalizedCell> stabilityCells) {
        Objects.requireNonNull(publishedReport, "publishedReport must not be null");
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(holdoutDimensions, "holdoutDimensions must not be null");
        Objects.requireNonNull(stabilityCells, "stabilityCells must not be null");

        Map<DimensionKey, AgingModelTemporalHoldoutAnalyzer.DimensionDiagnostic> holdoutByDimension = new HashMap<>();
        for (var diagnostic : holdoutDimensions) {
            holdoutByDimension.put(new DimensionKey(diagnostic.position(), diagnostic.metric()), diagnostic);
        }
        Map<CellKey, AgingModelNormalizedStabilityAnalyzer.NormalizedCell> stabilityByCell = new HashMap<>();
        for (var cell : stabilityCells) {
            stabilityByCell.put(new CellKey(cell.position(), cell.metric(), cell.age()), cell);
        }

        List<ValidatedCell> cells = new ArrayList<>();
        for (var cell : publishedReport.cells()) {
            var holdout = holdoutByDimension.get(new DimensionKey(cell.position(), cell.metric()));
            var stability = stabilityByCell.get(new CellKey(cell.position(), cell.metric(), cell.age()));
            var span = trainingSpan(observations, cell);
            boolean complete = holdout != null && stability != null
                && stability.holdoutMeanAbsoluteError() != null
                && stability.maximumShiftToHoldoutMae() != null
                && span != null;
            cells.add(new ValidatedCell(cell, span, holdout, stability, complete));
        }
        cells.sort(Comparator.comparing((ValidatedCell value) -> value.cell().position())
            .thenComparing(value -> value.cell().metric().name())
            .thenComparingInt(value -> value.cell().age()));

        return new ValidationReport(
            publishedReport.profileSource(), publishedReport.productionSource(),
            publishedReport.supportPolicyId(), publishedReport.minimumDistinctSeasonTransitions(),
            cells.size(), (int) cells.stream().filter(ValidatedCell::validationComplete).count(),
            List.copyOf(cells));
    }

    private static TrainingSpan trainingSpan(
        List<AgingModelSampleAuditAnalyzer.AgingObservation> observations,
        AgingModelLocalSmootherAnalyzer.SmoothedCell cell) {
        List<AgingModelSampleAuditAnalyzer.AgingObservation> matching = observations.stream()
            .filter(value -> value.position().equals(cell.position()))
            .filter(value -> value.metric() == cell.metric())
            .filter(value -> Math.abs(value.age() - cell.age()) <= 1)
            .toList();
        if (matching.isEmpty()) return null;
        int minimumStartSeason = matching.stream().mapToInt(AgingModelSampleAuditAnalyzer.AgingObservation::startSeason).min().orElseThrow();
        int maximumEndSeason = matching.stream().mapToInt(AgingModelSampleAuditAnalyzer.AgingObservation::endSeason).max().orElseThrow();
        return new TrainingSpan(minimumStartSeason, maximumEndSeason, matching.size());
    }

    private record DimensionKey(String position, AgingModelSampleAuditAnalyzer.Metric metric) {}
    private record CellKey(String position, AgingModelSampleAuditAnalyzer.Metric metric, int age) {}

    public record TrainingSpan(int minimumStartSeason, int maximumEndSeason, int observations) {
        public TrainingSpan {
            if (minimumStartSeason > maximumEndSeason) throw new IllegalArgumentException("training span is inverted");
            if (observations <= 0) throw new IllegalArgumentException("training span observations must be positive");
        }
    }

    public record ValidatedCell(AgingModelLocalSmootherAnalyzer.SmoothedCell cell,
                                TrainingSpan trainingSpan,
                                AgingModelTemporalHoldoutAnalyzer.DimensionDiagnostic holdout,
                                AgingModelNormalizedStabilityAnalyzer.NormalizedCell stability,
                                boolean validationComplete) {
        public ValidatedCell {
            Objects.requireNonNull(cell, "cell must not be null");
            if (validationComplete && (trainingSpan == null || holdout == null || stability == null)) {
                throw new IllegalArgumentException("complete validation requires all diagnostics");
            }
        }

        public double deltaP25() { return cell.deltaP25(); }
        public double medianDelta() { return cell.medianDelta(); }
        public double deltaP75() { return cell.deltaP75(); }
    }

    public record ValidationReport(String profileSource,
                                   String productionSource,
                                   String supportPolicyId,
                                   int minimumDistinctSeasonTransitions,
                                   int publishedCells,
                                   int validationCompleteCells,
                                   List<ValidatedCell> cells) {
        public ValidationReport {
            Objects.requireNonNull(profileSource, "profileSource must not be null");
            Objects.requireNonNull(productionSource, "productionSource must not be null");
            Objects.requireNonNull(supportPolicyId, "supportPolicyId must not be null");
            cells = List.copyOf(Objects.requireNonNull(cells, "cells must not be null"));
            if (validationCompleteCells < 0 || validationCompleteCells > publishedCells) {
                throw new IllegalArgumentException("validation-complete count must be within published count");
            }
        }

        public int validationIncompleteCells() { return publishedCells - validationCompleteCells; }
        public boolean allPublishedCellsValidationComplete() { return publishedCells > 0 && validationIncompleteCells() == 0; }
    }
}
