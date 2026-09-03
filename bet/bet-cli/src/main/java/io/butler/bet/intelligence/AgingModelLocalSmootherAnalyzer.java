package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Descriptive local smoother governed by BF-174. For every observed position/metric/age cell,
 * pools raw observations from ages A-1, A, and A+1 and reports robust delta summaries plus support.
 */
public final class AgingModelLocalSmootherAnalyzer {
    private final AgingModelSampleAuditAnalyzer sampleAudit;

    public AgingModelLocalSmootherAnalyzer(Database database) {
        this.sampleAudit = new AgingModelSampleAuditAnalyzer(Objects.requireNonNull(database, "database must not be null"));
    }

    public LocalSmootherReport analyze() throws SQLException {
        var audit = sampleAudit.analyze();
        List<SmoothedCell> cells = new ArrayList<>();
        for (var target : audit.cells()) {
            List<AgingModelSampleAuditAnalyzer.AgingObservation> pooled = audit.observations().stream()
                .filter(o -> o.position().equals(target.position()))
                .filter(o -> o.metric() == target.metric())
                .filter(o -> Math.abs(o.age() - target.age()) <= 1)
                .toList();
            if (pooled.isEmpty()) continue;
            cells.add(summarize(target, pooled));
        }
        cells.sort(Comparator.comparing(SmoothedCell::position)
            .thenComparing(cell -> cell.metric().name())
            .thenComparingInt(SmoothedCell::age));
        return new LocalSmootherReport(audit.profileSource(), audit.productionSource(), List.copyOf(cells));
    }

    private static SmoothedCell summarize(AgingModelSampleAuditAnalyzer.SampleCell target,
                                          List<AgingModelSampleAuditAnalyzer.AgingObservation> pooled) {
        List<Double> deltas = pooled.stream().map(AgingModelSampleAuditAnalyzer.AgingObservation::delta).toList();
        Set<String> players = new HashSet<>();
        Set<String> transitions = new HashSet<>();
        Set<Integer> contributingAges = new HashSet<>();
        for (var observation : pooled) {
            players.add(observation.gsisId());
            transitions.add(observation.startSeason() + "-" + observation.endSeason());
            contributingAges.add(observation.age());
        }
        List<Integer> ages = contributingAges.stream().sorted().toList();
        return new SmoothedCell(target.position(), target.metric(), target.age(),
            target.observations(), pooled.size(), players.size(), transitions.size(), ages,
            AgingModelSampleAuditAnalyzer.percentile(deltas, .25),
            AgingModelSampleAuditAnalyzer.percentile(deltas, .5),
            AgingModelSampleAuditAnalyzer.percentile(deltas, .75));
    }

    public record SmoothedCell(String position, AgingModelSampleAuditAnalyzer.Metric metric, int age,
                               int targetAgeObservations, int pooledObservations, int uniquePlayers,
                               int distinctSeasonTransitions, List<Integer> contributingAges,
                               double deltaP25, double medianDelta, double deltaP75) {
        public SmoothedCell {
            contributingAges = List.copyOf(Objects.requireNonNull(contributingAges, "contributingAges must not be null"));
        }
    }

    public record LocalSmootherReport(String profileSource, String productionSource, List<SmoothedCell> cells) {
        public LocalSmootherReport {
            cells = List.copyOf(Objects.requireNonNull(cells, "cells must not be null"));
        }
        public int smoothedCells() { return cells.size(); }
        public int edgeCells() { return (int) cells.stream().filter(c -> c.contributingAges().size() < 3).count(); }
    }
}
