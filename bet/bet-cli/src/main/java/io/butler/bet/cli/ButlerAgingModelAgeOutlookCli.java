package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.AgingModelAgeOutlookAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** Argument-free CLI for validation-complete per-metric age outlook diagnostics. */
public final class ButlerAgingModelAgeOutlookCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerAgingModelAgeOutlookCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            print(new AgingModelAgeOutlookAnalyzer(initializedDatabase()).analyze());
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building aging-model age outlook: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("aging-model")
            && args[1].equalsIgnoreCase("age-outlook");
    }

    static void parse(String[] args) {
        if (!isCommand(args)) throw new IllegalArgumentException("expected aging-model age-outlook");
        if (args.length != 2) throw new IllegalArgumentException("aging-model age-outlook does not accept additional arguments");
    }

    static void print(AgingModelAgeOutlookAnalyzer.AgeOutlookReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("Aging-model per-metric age outlook");
        System.out.println("Support policy: " + report.supportPolicyId());
        System.out.println("Outlook policy: " + report.outlookPolicyId());
        System.out.println("Profile source: " + report.profileSource());
        System.out.println("Production source: " + report.productionSource());
        System.out.printf("Published cells: %d outlook-available=%d unavailable=%d favorable=%d neutral-or-mixed=%d unfavorable=%d%n",
            report.publishedCells(), report.outlookAvailableCells(), report.outlookUnavailableCells(),
            report.favorableCells(), report.neutralOrMixedCells(), report.unfavorableCells());
        System.out.println("Descriptive only: no cross-metric weighting, player grade, dynasty adjustment, or recommendation is applied.");

        for (var value : report.cells()) {
            var validated = value.validation();
            var cell = validated.cell();
            System.out.printf("%s %s age=%d validation-complete=%s direction=%s outlook=%s delta[p25=%.4f median=%.4f p75=%.4f] transitions=%d",
                cell.position(), cell.metric(), cell.age(), validated.validationComplete(),
                value.direction() == null ? "-" : value.direction(), value.outlook() == null ? "-" : value.outlook(),
                validated.deltaP25(), validated.medianDelta(), validated.deltaP75(), cell.distinctSeasonTransitions());
            if (validated.holdout() != null) {
                System.out.printf(" holdout-mae=%.4f", validated.holdout().meanAbsoluteError());
            } else {
                System.out.print(" holdout-mae=-");
            }
            if (validated.stability() != null) {
                System.out.printf(" max-shift/mae=%s", format(validated.stability().maximumShiftToHoldoutMae()));
            } else {
                System.out.print(" max-shift/mae=-");
            }
            System.out.println();
        }
    }

    static void printUsage() {
        System.out.println("  butler aging-model age-outlook");
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
