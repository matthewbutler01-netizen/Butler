package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.AgingModelSampleBreadthAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for threshold-free aging-model sample breadth and sparsity diagnostics. */
public final class ButlerAgingModelSampleBreadthCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgingModelSampleBreadthCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            print(new AgingModelSampleBreadthAnalyzer(initializedDatabase()).analyze());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building aging-model sample breadth: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("aging-model")
            && args[1].equalsIgnoreCase("sample-breadth");
    }

    static void parse(String[] args) {
        if (!isCommand(args)) throw new IllegalArgumentException("expected aging-model sample-breadth");
        if (args.length != 2) {
            throw new IllegalArgumentException("aging-model sample-breadth does not accept additional arguments");
        }
    }

    static void print(AgingModelSampleBreadthAnalyzer.BreadthReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("Aging-model sample breadth");
        System.out.println("Sample cells: " + report.sampleCells());
        System.out.println("Metric observations: " + report.metricObservations());
        System.out.println("Position/metric dimensions: " + report.dimensions());
        System.out.println("Dimensions with age gaps: " + report.dimensionsWithAgeGaps());
        System.out.println("Dimensions with single-observation cells: " + report.dimensionsWithSingleObservationCells());
        System.out.println("Dimensions with single-transition cells: " + report.dimensionsWithSingleTransitionCells());
        System.out.println("No sufficiency threshold or smoothing rule is applied.");
        for (var dimension : report.dimensionBreadth()) {
            System.out.printf("%s %s ages=%d-%d cells=%d/%d (%.1f%%) observations=%d cell-n[min=%.0f median=%.1f max=%.0f] single-n=%d single-player=%d single-transition=%d max-transitions=%d gaps=%s%n",
                dimension.position(), dimension.metric(), dimension.minimumAge(), dimension.maximumAge(),
                dimension.ageCells(), dimension.observedAgeSpan(), dimension.ageCellCoveragePercent(),
                dimension.totalObservations(), (double) dimension.minimumCellObservations(),
                dimension.medianCellObservations(), (double) dimension.maximumCellObservations(),
                dimension.singleObservationCells(), dimension.singlePlayerCells(), dimension.singleTransitionCells(),
                dimension.maximumDistinctTransitions(), dimension.missingAges());
        }
    }

    static void printUsage() {
        System.out.println("  butler aging-model sample-breadth");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }
}
