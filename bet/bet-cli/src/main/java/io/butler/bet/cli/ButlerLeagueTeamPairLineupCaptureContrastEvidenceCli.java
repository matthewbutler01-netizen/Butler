package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

/** Exposes governed pairwise shared-week lineup-capture contrast without manager attribution. */
public final class ButlerLeagueTeamPairLineupCaptureContrastEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "team-pair-lineup-capture-contrast-evidence";

    private ButlerLeagueTeamPairLineupCaptureContrastEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.teamAId(), options.teamBId(), options.season()));
        } catch (SQLException e) {
            System.err.println("Database error while building pairwise lineup-capture contrast evidence: "
                + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 6) {
            throw new IllegalArgumentException(
                "Usage: butler league team-pair-lineup-capture-contrast-evidence "
                    + "<league-id> <team-a-id> <team-b-id> <season>");
        }
        String leagueId = requireText(args[2], "league-id");
        String teamAId = requireText(args[3], "team-a-id");
        String teamBId = requireText(args[4], "team-b-id");
        if (teamAId.equals(teamBId)) {
            throw new IllegalArgumentException("team-a-id and team-b-id must identify distinct teams");
        }
        int season = parseInt(args[5], "season");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        return new Options(leagueId, teamAId, teamBId, season);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && COMMAND.equalsIgnoreCase(args[1]);
    }

    static void print(LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer.PairwiseContrastReport report) {
        System.out.println("Pairwise lineup-capture contrast evidence");
        System.out.println("League: " + report.teamASourceSeason().leagueId());
        System.out.println("Season: " + report.teamASourceSeason().season());
        System.out.println("Team A: " + report.teamASourceSeason().teamId());
        System.out.println("Team B: " + report.teamBSourceSeason().teamId());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Week universe: " + report.weekUniverse());
        System.out.println("Policy: " + report.policyId());
        System.out.println();

        System.out.println("Shared-week evidence:");
        System.out.println("  shared comparable weeks: " + weeks(report.sharedComparableWeeks()));
        System.out.println("  shared comparable week count: " + report.sharedComparableWeeks().size());
        System.out.println("  Team A-only comparable weeks: " + weeks(report.teamAOnlyComparableWeeks()));
        System.out.println("  Team B-only comparable weeks: " + weeks(report.teamBOnlyComparableWeeks()));
        System.out.println("  Rule: both rates are recalculated over the exact same shared comparable weeks; "
            + "independently scoped full-season rates are not subtracted.");
        System.out.println();

        printTeam("Team A", report.teamA());
        System.out.println();
        printTeam("Team B", report.teamB());
        System.out.println();

        System.out.println("Pairwise contrast:");
        System.out.println("  state: " + report.contrastState());
        if (report.lineupCaptureRateContrast().isPresent()) {
            BigDecimal contrast = report.lineupCaptureRateContrast().orElseThrow();
            System.out.println("  Team A minus Team B rate contrast: " + rate(contrast));
            System.out.println("  Team A minus Team B percentage-point contrast: " + signedPercentagePoints(contrast));
        } else {
            System.out.println("  Team A minus Team B rate contrast: unavailable");
            System.out.println("  Team A minus Team B percentage-point contrast: unavailable");
        }
        System.out.println();
        System.out.println("Boundary: descriptive retrospective pairwise lineup-capture contrast only. "
            + "Shared calendar weeks do not reconstruct historical player startability. The contrast is not a "
            + "manager-efficiency difference, winner, manager grade, rank, tier, recommendation, intent, fault, "
            + "skill estimate, or causal judgment. It must not be used to derive a league ranking.");
    }

    private static void printTeam(
        String label,
        LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer.TeamSharedEvidence team) {
        System.out.println(label + " shared evidence [" + team.teamId() + "]:");
        System.out.println("  observed weeks: " + team.observedWeeks());
        System.out.println("  individually comparable weeks: " + team.individuallyComparableWeeks());
        System.out.println("  shared comparable weeks: " + team.sharedComparableWeeks());
        if (team.sharedTotalStartedPoints().isPresent()) {
            System.out.println("  shared total recalculated started points: "
                + points(team.sharedTotalStartedPoints().orElseThrow()));
            System.out.println("  shared total retrospective potential points: "
                + points(team.sharedTotalPotentialPoints().orElseThrow()));
            System.out.println("  shared total potential-minus-started gap: "
                + points(team.sharedTotalPointsGap().orElseThrow()));
        } else {
            System.out.println("  shared total recalculated started points: unavailable");
            System.out.println("  shared total retrospective potential points: unavailable");
            System.out.println("  shared total potential-minus-started gap: unavailable");
        }
        System.out.println("  lineup capture state: " + team.rateState());
        if (team.lineupCaptureRate().isPresent()) {
            BigDecimal capture = team.lineupCaptureRate().orElseThrow();
            System.out.println("  lineup capture rate: " + rate(capture));
            System.out.println("  lineup capture percentage: " + percentage(capture));
        } else {
            System.out.println("  lineup capture rate: unavailable");
            System.out.println("  lineup capture percentage: unavailable");
        }
    }

    private static Database initializedDatabase() throws SQLException {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static String weeks(List<Integer> weeks) {
        return weeks.isEmpty() ? "none" : weeks.toString();
    }

    private static String points(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String rate(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percentage(BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String signedPercentagePoints(BigDecimal contrast) {
        BigDecimal percentagePoints = contrast.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        String sign = percentagePoints.compareTo(BigDecimal.ZERO) > 0 ? "+" : "";
        return sign + percentagePoints.toPlainString() + " percentage points";
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

    record Options(String leagueId, String teamAId, String teamBId, int season) {}
}
