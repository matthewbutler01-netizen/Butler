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

/** Describes model-sample breadth and sparsity without declaring any cell sufficient or insufficient. */
public final class AgingModelSampleBreadthAnalyzer {
    private final AgingModelSampleAuditAnalyzer sampleAudit;

    public AgingModelSampleBreadthAnalyzer(Database database) {
        this(new AgingModelSampleAuditAnalyzer(Objects.requireNonNull(database, "database must not be null")));
    }

    AgingModelSampleBreadthAnalyzer(AgingModelSampleAuditAnalyzer sampleAudit) {
        this.sampleAudit = Objects.requireNonNull(sampleAudit, "sampleAudit must not be null");
    }

    public BreadthReport analyze() throws SQLException {
        var audit = sampleAudit.analyze();
        Map<DimensionKey, List<AgingModelSampleAuditAnalyzer.SampleCell>> cellsByDimension = new LinkedHashMap<>();
        for (var cell : audit.cells()) {
            cellsByDimension.computeIfAbsent(new DimensionKey(cell.position(), cell.metric()), ignored -> new ArrayList<>())
                .add(cell);
        }

        List<DimensionBreadth> dimensions = new ArrayList<>();
        for (var entry : cellsByDimension.entrySet()) {
            List<AgingModelSampleAuditAnalyzer.SampleCell> cells = entry.getValue().stream()
                .sorted(Comparator.comparingInt(AgingModelSampleAuditAnalyzer.SampleCell::age)).toList();
            int minimumAge = cells.getFirst().age();
            int maximumAge = cells.getLast().age();
            Set<Integer> observedAges = new HashSet<>();
            List<Integer> observationsPerCell = new ArrayList<>();
            int totalObservations = 0;
            int singleObservationCells = 0;
            int singlePlayerCells = 0;
            int singleTransitionCells = 0;
            int maximumTransitions = 0;
            for (var cell : cells) {
                observedAges.add(cell.age());
                observationsPerCell.add(cell.observations());
                totalObservations += cell.observations();
                if (cell.observations() == 1) singleObservationCells++;
                if (cell.uniquePlayers() == 1) singlePlayerCells++;
                if (cell.distinctSeasonTransitions() == 1) singleTransitionCells++;
                maximumTransitions = Math.max(maximumTransitions, cell.distinctSeasonTransitions());
            }
            List<Integer> missingAges = new ArrayList<>();
            for (int age = minimumAge; age <= maximumAge; age++) {
                if (!observedAges.contains(age)) missingAges.add(age);
            }
            observationsPerCell.sort(Integer::compareTo);
            dimensions.add(new DimensionBreadth(entry.getKey().position(), entry.getKey().metric(),
                cells.size(), minimumAge, maximumAge, List.copyOf(missingAges), totalObservations,
                observationsPerCell.getFirst(), percentile(observationsPerCell, .5), observationsPerCell.getLast(),
                singleObservationCells, singlePlayerCells, singleTransitionCells, maximumTransitions));
        }
        dimensions.sort(Comparator.comparing(DimensionBreadth::position)
            .thenComparing(d -> d.metric().name()));

        int dimensionsWithAgeGaps = (int) dimensions.stream().filter(d -> !d.missingAges().isEmpty()).count();
        int dimensionsWithSingleObservationCells = (int) dimensions.stream().filter(d -> d.singleObservationCells() > 0).count();
        int dimensionsWithSingleTransitionCells = (int) dimensions.stream().filter(d -> d.singleTransitionCells() > 0).count();
        return new BreadthReport(audit.sampleCells(), audit.metricObservations(), dimensions.size(),
            dimensionsWithAgeGaps, dimensionsWithSingleObservationCells, dimensionsWithSingleTransitionCells,
            List.copyOf(dimensions));
    }

    static double percentile(List<Integer> sortedValues, double percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) throw new IllegalArgumentException("values must not be empty");
        if (percentile < 0 || percentile > 1) throw new IllegalArgumentException("percentile must be between 0 and 1");
        if (sortedValues.size() == 1) return sortedValues.getFirst();
        double index = (sortedValues.size() - 1) * percentile;
        int low = (int) Math.floor(index);
        int high = (int) Math.ceil(index);
        if (low == high) return sortedValues.get(low);
        double fraction = index - low;
        return sortedValues.get(low) + (sortedValues.get(high) - sortedValues.get(low)) * fraction;
    }

    private record DimensionKey(String position, AgingModelSampleAuditAnalyzer.Metric metric) {}

    public record DimensionBreadth(String position, AgingModelSampleAuditAnalyzer.Metric metric,
                                   int ageCells, int minimumAge, int maximumAge, List<Integer> missingAges,
                                   int totalObservations, int minimumCellObservations,
                                   double medianCellObservations, int maximumCellObservations,
                                   int singleObservationCells, int singlePlayerCells,
                                   int singleTransitionCells, int maximumDistinctTransitions) {
        public DimensionBreadth {
            position = Objects.requireNonNull(position, "position must not be null");
            metric = Objects.requireNonNull(metric, "metric must not be null");
            missingAges = List.copyOf(Objects.requireNonNull(missingAges, "missingAges must not be null"));
        }
        public int observedAgeSpan() { return maximumAge - minimumAge + 1; }
        public double ageCellCoveragePercent() {
            return observedAgeSpan() == 0 ? 0.0 : ageCells * 100.0 / observedAgeSpan();
        }
    }

    public record BreadthReport(int sampleCells, int metricObservations, int dimensions,
                                int dimensionsWithAgeGaps, int dimensionsWithSingleObservationCells,
                                int dimensionsWithSingleTransitionCells,
                                List<DimensionBreadth> dimensionBreadth) {
        public BreadthReport {
            dimensionBreadth = List.copyOf(Objects.requireNonNull(dimensionBreadth, "dimensionBreadth must not be null"));
        }
    }
}
