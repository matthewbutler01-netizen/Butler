package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.AgingModelPublicationValidationAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** Argument-free CLI for publication-eligible aging-model validation diagnostics. */
public final class ButlerAgingModelPublicationValidationCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgingModelPublicationValidationCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            print(new AgingModelPublicationValidationAnalyzer(initializedDatabase()).analyze());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building aging-model publication validation: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("aging-model")
            && args[1].equalsIgnoreCase("publication-validation");
    }

    static void parse(String[] args) {
        if (!isCommand(args)) throw new IllegalArgumentException("expected aging-model publication-validation");
        if (args.length != 2) {
            throw new IllegalArgumentException("aging-model publication-validation does not accept additional arguments");
        }
    }

    static void print(AgingModelPublicationValidationAnalyzer.ValidationReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("Aging-model publication validation");
        System.out.println("Support policy: " + report.supportPolicyId());
        System.out.println("Minimum distinct season transitions: " + report.minimumDistinctSeasonTransitions());
        System.out.println("Profile source: " + report.profileSource());
        System.out.println("Production source: " + report.productionSource());
        System.out.printf("Published cells: %d validation-complete=%d validation-incomplete=%d all-complete=%s%n",
            report.publishedCells(), report.validationCompleteCells(), report.validationIncompleteCells(),
            report.allPublishedCellsValidationComplete());
        System.out.println("Diagnostic only: no validation cutoff, score, label, dynasty adjustment, or recommendation is applied.");

        for (var value : report.cells()) {
            var cell = value.cell();
            System.out.printf("%s %s age=%d validation-complete=%s transitions=%d delta[p25=%.4f median=%.4f p75=%.4f]",
                cell.position(), cell.metric(), cell.age(), value.validationComplete(), cell.distinctSeasonTransitions(),
                value.deltaP25(), value.medianDelta(), value.deltaP75());
            if (value.trainingSpan() != null) {
                System.out.printf(" training=%d-%d training-n=%d",
                    value.trainingSpan().minimumStartSeason(), value.trainingSpan().maximumEndSeason(),
                    value.trainingSpan().observations());
            } else {
                System.out.print(" training=unavailable");
            }
            if (value.holdout() != null) {
                System.out.printf(" holdout[n=%d mae=%.4f median-abs=%.4f p75-abs=%.4f]",
                    value.holdout().evaluatedObservations(), value.holdout().meanAbsoluteError(),
                    value.holdout().medianAbsoluteError(), value.holdout().absoluteErrorP75());
            } else {
                System.out.print(" holdout=unavailable");
            }
            if (value.stability() != null) {
                System.out.printf(" stability[max-shift=%.4f max-shift/mae=%s]",
                    value.stability().maximumAbsoluteShift(), format(value.stability().maximumShiftToHoldoutMae()));
            } else {
                System.out.print(" stability=unavailable");
            }
            System.out.println();
        }
    }

    static void printUsage() {
        System.out.println("  butler aging-model publication-validation");
    }

    private static String format(Double value) {
        return value == null ? "-" : String.format("%.4f", value);
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }
}
