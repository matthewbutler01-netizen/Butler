package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.AgingModelSampleAuditAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for the broad, model-free aging sample audit. */
public final class ButlerAgingModelSampleAuditCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgingModelSampleAuditCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            print(new AgingModelSampleAuditAnalyzer(initializedDatabase()).analyze());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building aging-model sample audit: " + e.getMessage());
            System.exit(1);
        }
    }

    static void parse(String[] args) {
        if (!isCommand(args)) {
            throw new IllegalArgumentException("expected aging-model sample-audit");
        }
        if (args.length != 2) {
            throw new IllegalArgumentException("aging-model sample-audit does not accept additional arguments");
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("aging-model")
            && args[1].equalsIgnoreCase("sample-audit");
    }

    static void print(AgingModelSampleAuditAnalyzer.SampleAuditReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("Aging-model sample audit");
        System.out.println("Profile source: " + report.profileSource());
        System.out.println("Production source: " + report.productionSource());
        System.out.println("Model profile players: " + report.modelProfilePlayers());
        System.out.println("Exact birth-date players: " + report.exactBirthDatePlayers());
        System.out.println("Production player-seasons: " + report.productionPlayerSeasons());
        System.out.println("Zero-game player-seasons: " + report.zeroGamePlayerSeasons());
        System.out.println("Production players without profile: " + report.productionPlayersWithoutProfile());
        System.out.println("Profile players without production: " + report.profilePlayersWithoutProduction());
        System.out.println("Consecutive season pairs: " + report.consecutivePairs());
        System.out.println("Exact-DOB rate pairs: " + report.exactDobRatePairs());
        System.out.println("Metric observations: " + report.metricObservations());
        System.out.println("Sample cells: " + report.sampleCells());
        System.out.println("Excluded pairs:");
        System.out.println("  zero-game=" + report.zeroGameExcludedPairs());
        System.out.println("  missing-birth-date=" + report.missingBirthDatePairs());
        System.out.println("  position-change=" + report.positionChangeExcludedPairs());
        System.out.println("  unsupported-position=" + report.unsupportedPositionPairs());
        System.out.println("Sample cells (descriptive only; no sufficiency threshold or fitted curve):");
        for (var cell : report.cells()) {
            System.out.printf("  %s %s age=%d n=%d players=%d transitions=%d seasons=%d-%d start-median=%.4f delta-p25=%.4f delta-median=%.4f delta-p75=%.4f%n",
                cell.position(), cell.metric(), cell.age(), cell.observations(), cell.uniquePlayers(),
                cell.distinctSeasonTransitions(), cell.minimumStartSeason(), cell.maximumStartSeason(),
                cell.medianStartRate(), cell.deltaP25(), cell.medianDelta(), cell.deltaP75());
        }
    }

    static void printUsage() {
        System.out.println("  butler aging-model sample-audit");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }
}
