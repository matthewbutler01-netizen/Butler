package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Rolling-origin out-of-time validation for the governed local aging smoother. */
public final class AgingModelTemporalHoldoutAnalyzer {
    private final AgingModelSampleAuditAnalyzer sampleAudit;

    public AgingModelTemporalHoldoutAnalyzer(Database database) {
        this.sampleAudit = new AgingModelSampleAuditAnalyzer(Objects.requireNonNull(database, "database must not be null"));
    }

    public TemporalHoldoutReport analyze() throws SQLException {
        var audit = sampleAudit.analyze();
        List<HoldoutObservation> evaluated = new ArrayList<>();
        int withoutPriorTraining = 0;

        for (var test : audit.observations()) {
            List<AgingModelSampleAuditAnalyzer.AgingObservation> training = audit.observations().stream()
                .filter(candidate -> candidate.position().equals(test.position()))
                .filter(candidate -> candidate.metric() == test.metric())
                .filter(candidate -> Math.abs(candidate.age() - test.age()) <= 1)
                .filter(candidate -> candidate.endSeason() < test.endSeason())
                .toList();
            if (training.isEmpty()) {
                withoutPriorTraining++;
                continue;
            }
            evaluated.add(summarize(test, training));
        }

        evaluated.sort(Comparator.comparing(HoldoutObservation::position)
            .thenComparing(o -> o.metric().name())
            .thenComparingInt(HoldoutObservation::endSeason)
            .thenComparingInt(HoldoutObservation::age)
            .thenComparing(HoldoutObservation::gsisId));

        Map<DimensionKey, List<HoldoutObservation>> byDimension = new LinkedHashMap<>();
        for (var value : evaluated) {
            byDimension.computeIfAbsent(new DimensionKey(value.position(), value.metric()), ignored -> new ArrayList<>())
                .add(value);
        }
        List<DimensionDiagnostic> dimensions = byDimension.entrySet().stream()
            .map(entry -> dimension(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(DimensionDiagnostic::position)
                .thenComparing(d -> d.metric().name()))
            .toList();

        Map<String, List<HoldoutObservation>> byTransition = new LinkedHashMap<>();
        for (var value : evaluated) {
            String key = (value.endSeason() - 1) + "-" + value.endSeason();
            byTransition.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        List<TransitionDiagnostic> transitions = byTransition.entrySet().stream()
            .map(entry -> transition(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparingInt(TransitionDiagnostic::endSeason))
            .toList();

        return new TemporalHoldoutReport(audit.metricObservations(), evaluated.size(), withoutPriorTraining,
            List.copyOf(evaluated), List.copyOf(dimensions), List.copyOf(transitions));
    }

    private static HoldoutObservation summarize(AgingModelSampleAuditAnalyzer.AgingObservation test,
                                                 List<AgingModelSampleAuditAnalyzer.AgingObservation> training) {
        List<Double> deltas = training.stream().map(AgingModelSampleAuditAnalyzer.AgingObservation::delta).toList();
        double predicted = AgingModelSampleAuditAnalyzer.percentile(deltas, .5);
        Set<String> players = new HashSet<>();
        Set<String> transitions = new HashSet<>();
        Set<Integer> ages = new HashSet<>();
        for (var value : training) {
            players.add(value.gsisId());
            transitions.add(value.startSeason() + "-" + value.endSeason());
            ages.add(value.age());
        }
        double error = test.delta() - predicted;
        return new HoldoutObservation(test.gsisId(), test.position(), test.metric(), test.age(),
            test.startSeason(), test.endSeason(), test.delta(), predicted, error, Math.abs(error),
            training.size(), players.size(), transitions.size(), ages.stream().sorted().toList());
    }

    private static DimensionDiagnostic dimension(DimensionKey key, List<HoldoutObservation> values) {
        List<Double> errors = values.stream().map(HoldoutObservation::error).toList();
        List<Double> absoluteErrors = values.stream().map(HoldoutObservation::absoluteError).toList();
        double meanAbsolute = absoluteErrors.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        return new DimensionDiagnostic(key.position(), key.metric(), values.size(),
            AgingModelSampleAuditAnalyzer.percentile(errors, .5), meanAbsolute,
            AgingModelSampleAuditAnalyzer.percentile(absoluteErrors, .5),
            AgingModelSampleAuditAnalyzer.percentile(absoluteErrors, .75));
    }

    private static TransitionDiagnostic transition(String transition, List<HoldoutObservation> values) {
        int separator = transition.indexOf('-');
        int start = Integer.parseInt(transition.substring(0, separator));
        int end = Integer.parseInt(transition.substring(separator + 1));
        List<Double> absoluteErrors = values.stream().map(HoldoutObservation::absoluteError).toList();
        return new TransitionDiagnostic(start, end, values.size(),
            absoluteErrors.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
            AgingModelSampleAuditAnalyzer.percentile(absoluteErrors, .5));
    }

    private record DimensionKey(String position, AgingModelSampleAuditAnalyzer.Metric metric) {}

    public record HoldoutObservation(String gsisId, String position, AgingModelSampleAuditAnalyzer.Metric metric,
                                     int age, int startSeason, int endSeason, double observedDelta,
                                     double predictedMedianDelta, double error, double absoluteError,
                                     int trainingObservations, int trainingUniquePlayers,
                                     int trainingDistinctTransitions, List<Integer> trainingAges) {
        public HoldoutObservation {
            trainingAges = List.copyOf(Objects.requireNonNull(trainingAges, "trainingAges must not be null"));
        }
    }

    public record DimensionDiagnostic(String position, AgingModelSampleAuditAnalyzer.Metric metric,
                                      int evaluatedObservations, double medianError,
                                      double meanAbsoluteError, double medianAbsoluteError,
                                      double absoluteErrorP75) {}

    public record TransitionDiagnostic(int startSeason, int endSeason, int evaluatedObservations,
                                       double meanAbsoluteError, double medianAbsoluteError) {}

    public record TemporalHoldoutReport(int candidateObservations, int evaluatedObservations,
                                        int observationsWithoutPriorTraining,
                                        List<HoldoutObservation> observations,
                                        List<DimensionDiagnostic> dimensions,
                                        List<TransitionDiagnostic> transitions) {
        public TemporalHoldoutReport {
            observations = List.copyOf(Objects.requireNonNull(observations, "observations must not be null"));
            dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimensions must not be null"));
            transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions must not be null"));
        }
    }
}
