package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Paired rolling-origin comparison of local age +/-1 smoothing versus exact-age-only medians. */
public final class AgingModelSmoothingSensitivityAnalyzer {
    private final AgingModelSampleAuditAnalyzer sampleAudit;

    public AgingModelSmoothingSensitivityAnalyzer(Database database) {
        this.sampleAudit = new AgingModelSampleAuditAnalyzer(Objects.requireNonNull(database, "database must not be null"));
    }

    public SensitivityReport analyze() throws SQLException {
        var audit = sampleAudit.analyze();
        List<PairedObservation> paired = new ArrayList<>();
        int localAvailableCenterUnavailable = 0;
        int neitherAvailable = 0;

        for (var test : audit.observations()) {
            List<AgingModelSampleAuditAnalyzer.AgingObservation> priorDimension = audit.observations().stream()
                .filter(candidate -> candidate.position().equals(test.position()))
                .filter(candidate -> candidate.metric() == test.metric())
                .filter(candidate -> candidate.endSeason() < test.endSeason())
                .toList();
            List<AgingModelSampleAuditAnalyzer.AgingObservation> local = priorDimension.stream()
                .filter(candidate -> Math.abs(candidate.age() - test.age()) <= 1)
                .toList();
            List<AgingModelSampleAuditAnalyzer.AgingObservation> center = priorDimension.stream()
                .filter(candidate -> candidate.age() == test.age())
                .toList();

            if (local.isEmpty()) {
                neitherAvailable++;
                continue;
            }
            if (center.isEmpty()) {
                localAvailableCenterUnavailable++;
                continue;
            }
            double localPrediction = medianDelta(local);
            double centerPrediction = medianDelta(center);
            double localAbs = Math.abs(test.delta() - localPrediction);
            double centerAbs = Math.abs(test.delta() - centerPrediction);
            paired.add(new PairedObservation(test.gsisId(), test.position(), test.metric(), test.age(),
                test.startSeason(), test.endSeason(), test.delta(), localPrediction, centerPrediction,
                localAbs, centerAbs, localAbs - centerAbs, local.size(), center.size()));
        }

        paired.sort(Comparator.comparing(PairedObservation::position)
            .thenComparing(o -> o.metric().name())
            .thenComparingInt(PairedObservation::endSeason)
            .thenComparingInt(PairedObservation::age)
            .thenComparing(PairedObservation::gsisId));

        Map<DimensionKey, List<PairedObservation>> grouped = new LinkedHashMap<>();
        for (var value : paired) {
            grouped.computeIfAbsent(new DimensionKey(value.position(), value.metric()), ignored -> new ArrayList<>())
                .add(value);
        }
        List<DimensionSensitivity> dimensions = grouped.entrySet().stream()
            .map(entry -> summarize(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(DimensionSensitivity::position)
                .thenComparing(d -> d.metric().name()))
            .toList();

        return new SensitivityReport(audit.metricObservations(), paired.size(),
            localAvailableCenterUnavailable, neitherAvailable, List.copyOf(paired), List.copyOf(dimensions));
    }

    private static double medianDelta(List<AgingModelSampleAuditAnalyzer.AgingObservation> observations) {
        return AgingModelSampleAuditAnalyzer.percentile(
            observations.stream().map(AgingModelSampleAuditAnalyzer.AgingObservation::delta).toList(), .5);
    }

    private static DimensionSensitivity summarize(DimensionKey key, List<PairedObservation> values) {
        List<Double> localAbs = values.stream().map(PairedObservation::localAbsoluteError).toList();
        List<Double> centerAbs = values.stream().map(PairedObservation::centerAbsoluteError).toList();
        List<Double> differences = values.stream().map(PairedObservation::absoluteErrorDifference).toList();
        long localWins = values.stream().filter(v -> v.localAbsoluteError() < v.centerAbsoluteError()).count();
        long centerWins = values.stream().filter(v -> v.localAbsoluteError() > v.centerAbsoluteError()).count();
        long ties = values.size() - localWins - centerWins;
        return new DimensionSensitivity(key.position(), key.metric(), values.size(),
            mean(localAbs), percentile(localAbs), mean(centerAbs), percentile(centerAbs),
            AgingModelSampleAuditAnalyzer.percentile(differences, .5),
            localWins, centerWins, ties, 100.0 * localWins / values.size());
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static double percentile(List<Double> values) {
        return AgingModelSampleAuditAnalyzer.percentile(values, .5);
    }

    private record DimensionKey(String position, AgingModelSampleAuditAnalyzer.Metric metric) {}

    public record PairedObservation(String gsisId, String position, AgingModelSampleAuditAnalyzer.Metric metric,
                                    int age, int startSeason, int endSeason, double observedDelta,
                                    double localPrediction, double centerPrediction,
                                    double localAbsoluteError, double centerAbsoluteError,
                                    double absoluteErrorDifference, int localTrainingObservations,
                                    int centerTrainingObservations) {}

    public record DimensionSensitivity(String position, AgingModelSampleAuditAnalyzer.Metric metric,
                                       int pairedObservations, double localMeanAbsoluteError,
                                       double localMedianAbsoluteError, double centerMeanAbsoluteError,
                                       double centerMedianAbsoluteError, double medianAbsoluteErrorDifference,
                                       long localWins, long centerWins, long ties,
                                       double localWinPercent) {}

    public record SensitivityReport(int candidateObservations, int pairedObservations,
                                    int localAvailableCenterUnavailable, int neitherAvailable,
                                    List<PairedObservation> observations,
                                    List<DimensionSensitivity> dimensions) {
        public SensitivityReport {
            observations = List.copyOf(Objects.requireNonNull(observations, "observations must not be null"));
            dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions must not be null"));
        }
    }
}
