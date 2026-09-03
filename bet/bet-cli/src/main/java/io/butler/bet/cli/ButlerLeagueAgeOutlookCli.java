package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueAgeOutlookEvidenceAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;

/** CLI leaf for governed per-metric league age outlook evidence. */
public final class ButlerLeagueAgeOutlookCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerLeagueAgeOutlookCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueAgeOutlookEvidenceAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.season()));
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (SQLException e) {
            System.err.println("Database error while building league age outlook: " + e.getMessage());
            System.exit(1);
        }
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && args[0].equalsIgnoreCase("league")
            && args[1].equalsIgnoreCase("age-outlook");
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 4) {
            throw new IllegalArgumentException("expected league age-outlook <league-id> <season>");
        }
        String leagueId = args[2] == null ? "" : args[2].trim();
        if (leagueId.isBlank()) throw new IllegalArgumentException("league-id must not be blank");
        int season;
        try {
            season = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("season must be a whole year");
        }
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        return new Options(leagueId, season);
    }

    static void print(LeagueAgeOutlookEvidenceAnalyzer.LeagueAgeOutlookReport report) {
        if (report == null) throw new IllegalArgumentException("report must not be null");
        System.out.println("League age outlook evidence");
        System.out.println("League ID: " + report.leagueId());
        System.out.println("Season: " + report.season());
        System.out.println("Model age as-of: " + report.modelAgeAsOf());
        System.out.println("League profile source: " + report.leagueProfileSource());
        System.out.println("Support policy: " + report.supportPolicyId());
        System.out.println("Outlook policy: " + report.outlookPolicyId());
        System.out.println("Minimum distinct season transitions: " + report.minimumDistinctSeasonTransitions());
        System.out.println("Model profile source: " + report.modelProfileSource());
        System.out.println("Model production source: " + report.modelProductionSource());
        System.out.printf("Published model cells: %d outlook-available=%d all-outlook-available=%s%n",
            report.publishedModelCells(), report.outlookAvailableModelCells(), report.allPublishedModelCellsHaveOutlook());
        System.out.println("Per-metric descriptive outlook only: no player-level age grade, cross-metric weighting, dynasty adjustment, or recommendation is applied.");

        for (var team : report.teams()) {
            System.out.printf("%s [%s]%n", team.teamName(), team.teamId());
            for (var player : team.players()) {
                var base = player.player().player();
                System.out.printf("  %s %s model-age=%s status=%s outlook-metrics=%d favorable=%d neutral-or-mixed=%d unfavorable=%d [%s]%n",
                    base.playerName(), base.position(), base.modelAge() == null ? "-" : base.modelAge(), base.status(),
                    player.outlookAvailableMetrics(), player.favorableMetrics(), player.neutralOrMixedMetrics(),
                    player.unfavorableMetrics(), base.playerId());
                for (var metric : player.metrics()) {
                    var validated = metric.metric();
                    var baseMetric = validated.metric();
                    if (!metric.available()) {
                        System.out.printf("    %s status=%s outlook=unavailable%n",
                            baseMetric.metric(), baseMetric.status());
                        continue;
                    }
                    var outlook = metric.outlook();
                    var validation = validated.validation();
                    System.out.printf("    %s outlook=%s direction=%s delta[p25=%.4f median=%.4f p75=%.4f] holdout-mae=%.4f stability-max-shift/mae=%s%n",
                        baseMetric.metric(), metric.label(), metric.direction(), outlook.deltaP25(), outlook.medianDelta(),
                        outlook.deltaP75(), validation.holdout().meanAbsoluteError(),
                        validation.stability().maximumShiftToHoldoutMae() == null ? "-" : String.format("%.4f", validation.stability().maximumShiftToHoldoutMae()));
                }
            }
        }
    }

    static void printUsage() {
        System.out.println("  butler league age-outlook <league-id> <season>");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    record Options(String leagueId, int season) {}
}
