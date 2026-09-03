package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Identifies non-dominated age-band support-threshold tradeoffs without selecting a production cutoff. */
public final class AgingModelAgeBandThresholdFrontierAnalyzer {
    private final AgingModelAgeBandStabilityAnalyzer stability;

    public AgingModelAgeBandThresholdFrontierAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.stability = new AgingModelAgeBandStabilityAnalyzer(database);
    }

    public ThresholdFrontierReport analyze() throws SQLException {
        var report = stability.analyze();
        List<AgeBandFrontier> frontiers = new ArrayList<>();

        for (String position : List.of("QB", "RB", "WR", "TE")) {
            for (AgingModelAgeBandStabilityAnalyzer.AgeBand ageBand
                : AgingModelAgeBandStabilityAnalyzer.AgeBand.values()) {
                List<ThresholdPoint> candidates = report.bands().stream()
                    .filter(band -> band.position().equals(position))
                    .filter(band -> band.ageBand() == ageBand)
                    .filter(band -> band.retainedCells() > 0)
                    .filter(band -> band.p90MaximumShiftToHoldoutMae() != null)
                    .map(ThresholdPoint::from)
                    .toList();

                List<ThresholdPoint> nonDominated = candidates.stream()
                    .filter(candidate -> candidates.stream().noneMatch(other -> dominates(other, candidate)))
                    .toList();
                List<ThresholdPoint> frontier = collapseEquivalentObjectives(nonDominated);

                frontiers.add(new AgeBandFrontier(position, ageBand, candidates, frontier));
            }
        }

        return new ThresholdFrontierReport(report.cellsAnalyzed(), report.normalizedCells(), frontiers);
    }

    private static List<ThresholdPoint> collapseEquivalentObjectives(List<ThresholdPoint> points) {
        Map<ObjectiveKey, ThresholdPoint> representatives = new LinkedHashMap<>();
        for (ThresholdPoint point : points) {
            ObjectiveKey key = new ObjectiveKey(point.retainedFraction(), point.p90MaximumShiftToHoldoutMae());
            representatives.putIfAbsent(key, point);
        }
        return List.copyOf(representatives.values());
    }

    private static boolean dominates(ThresholdPoint left, ThresholdPoint right) {
        if (left == right) return false;
        boolean noWorseCoverage = left.retainedFraction() >= right.retainedFraction();
        boolean noWorseInstability = left.p90MaximumShiftToHoldoutMae() <= right.p90MaximumShiftToHoldoutMae();
        boolean strictlyBetter = left.retainedFraction() > right.retainedFraction()
            || left.p90MaximumShiftToHoldoutMae() < right.p90MaximumShiftToHoldoutMae();
        return noWorseCoverage && noWorseInstability && strictlyBetter;
    }

    private record ObjectiveKey(double retainedFraction, double p90MaximumShiftToHoldoutMae) {}

    public record ThresholdPoint(int minimumDistinctSeasonTransitions,
                                 int baselineCells,
                                 int retainedCells,
                                 double retainedFraction,
                                 double p90MaximumShiftToHoldoutMae,
                                 Double maximumShiftToHoldoutMae) {
        static ThresholdPoint from(AgingModelAgeBandStabilityAnalyzer.AgeBandDiagnostic diagnostic) {
            return new ThresholdPoint(
                diagnostic.minimumDistinctSeasonTransitions(),
                diagnostic.baselineCells(),
                diagnostic.retainedCells(),
                diagnostic.retainedFraction(),
                diagnostic.p90MaximumShiftToHoldoutMae(),
                diagnostic.maximumShiftToHoldoutMae());
        }
    }

    public record AgeBandFrontier(String position,
                                  AgingModelAgeBandStabilityAnalyzer.AgeBand ageBand,
                                  List<ThresholdPoint> candidates,
                                  List<ThresholdPoint> frontier) {
        public AgeBandFrontier {
            Objects.requireNonNull(position, "position must not be null");
            Objects.requireNonNull(ageBand, "ageBand must not be null");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
            frontier = List.copyOf(Objects.requireNonNull(frontier, "frontier must not be null"));
        }
    }

    public record ThresholdFrontierReport(int cellsAnalyzed,
                                          int normalizedCells,
                                          List<AgeBandFrontier> bands) {
        public ThresholdFrontierReport {
            bands = List.copyOf(Objects.requireNonNull(bands, "bands must not be null"));
        }
    }
}
