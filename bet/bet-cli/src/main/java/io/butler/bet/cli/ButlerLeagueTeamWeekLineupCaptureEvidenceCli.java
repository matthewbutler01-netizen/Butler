package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueTeamWeekLineupCaptureEvidenceAnalyzer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes descriptive governed team-week lineup capture evidence without manager attribution. */
public final class ButlerLeagueTeamWeekLineupCaptureEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "team-week-lineup-capture-evidence";

    private ButlerLeagueTeamWeekLineupCaptureEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueTeamWeekLineupCaptureEvidenceAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.teamId(), options.season(), options.week()));
        } catch (SQLException e) {
            System.err.println("Database error while building team-week lineup capture evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 6) {
            throw new IllegalArgumentException(
                "Usage: butler league team-week-lineup-capture-evidence <league-id> <team-id> <season> <week>");
        }
        String leagueId = requireText(args[2], "league-id");
        String teamId = requireText(args[3], "team-id");
        int season = parseInt(args[4], "season");
        int week = parseInt(args[5], "week");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        if (week <= 0) throw new IllegalArgumentException("week must be positive");
        return new Options(leagueId, teamId, season, week);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && COMMAND.equalsIgnoreCase(args[1]);
    }

    static void print(LeagueTeamWeekLineupCaptureEvidenceAnalyzer.LineupCaptureReport report) {
        var source = report.sourcePointsGap();
        System.out.println("Team-week lineup capture evidence");
        System.out.println("League: " + source.leagueId());
        System.out.println("Team: " + source.teamId());
        System.out.println("Season/week: " + source.season() + "/" + source.week());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Source points-gap policy: " + source.policyId());
        System.out.println();
        System.out.println("Evidence provenance:");
        System.out.println("  league configuration as-of: " + source.leagueConfigurationAsOf());
        System.out.println("  roster evidence as-of: " + source.rosterEvidenceAsOf());
        System.out.println("  production coverage as-of: " + source.productionCoverageAsOf());
        System.out.println("  production source: " + source.productionSourceUri());
        System.out.println();
        System.out.println("Complete starting slots: " + source.startingSlots());
        System.out.println("Recalculated started points: " + points(source.startedPoints()));
        System.out.println("Retrospective potential points: " + points(source.potentialPoints()));
        System.out.println("Potential-minus-started points gap: " + points(source.pointsGap()));
        System.out.println("Lineup capture rate state: " + report.rateState());
        if (report.lineupCaptureRate().isPresent()) {
            BigDecimal rate = report.lineupCaptureRate().orElseThrow();
            System.out.println("Lineup capture rate: " + rate.toPlainString());
            System.out.println("Lineup capture percentage: " + percentage(rate));
        } else {
            System.out.println("Lineup capture rate: unavailable (" + unavailableReason(report.rateState()) + ")");
        }
        System.out.println();
        System.out.println("Boundary: descriptive lineup capture evidence only. The normalized rate is derived from "
            + "the complete governed points-gap source and does not reconstruct historical startability. It is not "
            + "manager efficiency, a manager grade, rank, tier, recommendation, intent, fault, or skill attribution.");
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static String points(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String percentage(BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(100))
            .setScale(2, RoundingMode.HALF_UP)
            .toPlainString() + "%";
    }

    private static String unavailableReason(LeagueTeamWeekLineupCaptureEvidenceAnalyzer.CaptureRateState state) {
        return switch (state) {
            case UNAVAILABLE_ZERO_POTENTIAL -> "retrospective potential points are zero";
            case UNAVAILABLE_NEGATIVE_POINTS -> "started or potential point totals are negative";
            case AVAILABLE -> throw new IllegalArgumentException("available capture state requires a rate");
        };
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static int parseInt(String value, String field) {
        try {
            return Integer.parseInt(requireText(value, field));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be an integer: " + value, e);
        }
    }

    record Options(String leagueId, String teamId, int season, int week) {}
}
