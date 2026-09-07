package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.HistoricalScoringLaneSelector;
import io.butler.bet.intelligence.LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer;
import io.butler.bet.intelligence.LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

/** Exposes the neutral all-team common-universe lineup-capture table without ranking. */
public final class ButlerLeagueSeasonLineupCaptureCommonUniverseEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "season-lineup-capture-common-universe-evidence";

    private ButlerLeagueSeasonLineupCaptureCommonUniverseEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer(initializedDatabase()).analyze(
                options.leagueId(), options.season()));
        } catch (SQLException e) {
            System.err.println("Database error while building league common-universe lineup capture evidence: "
                + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 4) {
            throw new IllegalArgumentException(
                "Usage: butler league season-lineup-capture-common-universe-evidence <league-id> <season>");
        }
        String leagueId = requireText(args[2], "league-id");
        int season = parseInt(args[3], "season");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        return new Options(leagueId, season);
    }

    static boolean isCommand(String[] args) {
        return args != null && args.length >= 2
            && "league".equalsIgnoreCase(args[0])
            && COMMAND.equalsIgnoreCase(args[1]);
    }

    static void print(
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.LeagueCommonUniverseReport report) {
        System.out.println("League common-universe lineup capture evidence");
        System.out.println("League: " + report.leagueName() + " [" + report.leagueId() + "]");
        System.out.println("Season: " + report.season());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Week universe: " + report.weekUniverse());
        System.out.println("Presentation scope: " + report.presentationScope());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Scoring lane selector: " + report.scoringLaneSelectionPolicyId());
        System.out.println("Scoring lane: " + report.scoringLane());
        System.out.println("Scoring policy: " + report.scoringPolicyId());
        System.out.println("Team-season points-gap policy: " + report.teamSeasonPointsGapPolicyId());
        System.out.println("Repository team count: " + report.teams().size());
        System.out.println("Common-universe state: " + report.commonUniverseState());
        System.out.println("Common comparable weeks: " + weeks(report.commonComparableWeeks()));
        System.out.println("Common denominator: " + report.commonComparableWeeks().size()
            + " common comparable observed week(s) across all " + report.teams().size() + " repository team(s)");
        System.out.println("Team order: repository team-name order; never capture-rate-ranked.");
        if (report.commonUniverseState()
            == LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState.UNAVAILABLE_INSUFFICIENT_TEAMS) {
            System.out.println("Common-universe availability: unavailable (fewer than two repository teams)");
        } else if (report.commonUniverseState()
            == LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState.UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS) {
            System.out.println("Common-universe availability: unavailable (no comparable complete week is shared by every repository team)");
        } else {
            System.out.println("Common-universe availability: available");
        }
        System.out.println();

        System.out.println("Neutral common-universe table:");
        System.out.println("Team | observed | individually comparable | excluded comparable | common | started | potential | gap | capture");
        if (report.teams().isEmpty()) {
            System.out.println("none");
        }
        for (var team : report.teams()) {
            System.out.println(team.teamName() + " [" + team.teamId() + "]"
                + " | " + team.observedWeeks()
                + " | " + team.individuallyComparableWeeks()
                + " | " + weeks(team.excludedComparableWeeks())
                + " | " + team.commonComparableWeeks()
                + " | " + optionalPoints(team.commonTotalStartedPoints())
                + " | " + optionalPoints(team.commonTotalPotentialPoints())
                + " | " + optionalPoints(team.commonTotalPointsGap())
                + " | " + capture(team));
            printCommonWeekProvenance(report, team);
        }
        System.out.println();

        System.out.println("Boundary: every normalized row uses the same all-repository-team common comparable week set "
            + "under one governed league-season historical scoring lane; Butler does not drop a low-coverage team to widen "
            + "that universe and does not fall back to independently scoped season rates. Rows stay in repository team-name "
            + "order. This table computes no rank, tier, percentile, winner, league average or median, distance from a league "
            + "benchmark, pairwise matrix, or manager score. Potential uses observed provider configuration and is not "
            + "reconstructed historical startability. Lineup capture remains descriptive retrospective evidence only, not "
            + "manager efficiency, manager quality, skill, fault, intent, decision quality, or a recommendation.");
    }

    private static void printCommonWeekProvenance(
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.LeagueCommonUniverseReport report,
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.TeamCommonEvidence team) {
        for (int commonWeek : report.commonComparableWeeks()) {
            var week = team.sourceSeasonPointsGap().weeks().stream()
                .filter(candidate -> candidate.week() == commonWeek
                    && candidate.state() == LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.COMPARABLE_COMPLETE)
                .findFirst()
                .orElseThrow();
            var gap = week.pointsGap();
            if (gap.scoringLane() == HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT) {
                System.out.println("  common week " + commonWeek + " production coverage as-of: "
                    + gap.productionCoverageAsOf());
                System.out.println("  common week " + commonWeek + " production source: " + gap.productionSourceUri());
            } else {
                System.out.println("  common week " + commonWeek + " provider points as-of: " + gap.providerPointsAsOf());
                System.out.println("  common week " + commonWeek + " provider points source surface: "
                    + gap.providerPointsSourceSurface());
                System.out.println("  common week " + commonWeek + " provider league id: " + gap.providerLeagueId());
            }
        }
    }

    private static String capture(
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.TeamCommonEvidence team) {
        if (team.lineupCaptureRate().isPresent()) {
            BigDecimal rate = team.lineupCaptureRate().orElseThrow();
            return rate.toPlainString() + " (" + percentage(rate) + ")";
        }
        return "unavailable (" + unavailableReason(team.rateState()) + ")";
    }

    private static String unavailableReason(
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonRateState state) {
        return switch (state) {
            case UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS -> "no all-team common comparable weeks";
            case UNAVAILABLE_ZERO_TOTAL_POTENTIAL -> "common total retrospective potential points are zero";
            case UNAVAILABLE_NEGATIVE_COMMON_POINTS -> "a common week has negative started or potential points";
            case AVAILABLE -> throw new IllegalArgumentException("available common rate state requires a rate");
        };
    }

    private static String optionalPoints(java.util.Optional<BigDecimal> value) {
        return value.map(ButlerLeagueSeasonLineupCaptureCommonUniverseEvidenceCli::points)
            .orElse("unavailable");
    }

    private static String weeks(List<Integer> weekNumbers) {
        return weekNumbers.isEmpty() ? "none" : weekNumbers.toString();
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

    record Options(String leagueId, int season) {}
}
