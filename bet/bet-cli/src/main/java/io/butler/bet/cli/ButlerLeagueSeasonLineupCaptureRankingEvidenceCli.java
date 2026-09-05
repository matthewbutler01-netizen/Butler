package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueSeasonLineupCaptureRankingEvidenceAnalyzer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.sql.SQLException;

/** Exposes governed common-universe lineup-capture rank without manager-quality attribution. */
public final class ButlerLeagueSeasonLineupCaptureRankingEvidenceCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String COMMAND = "season-lineup-capture-ranking-evidence";

    private ButlerLeagueSeasonLineupCaptureRankingEvidenceCli() {}

    public static void main(String[] args) {
        try {
            Options options = parse(args);
            print(new LeagueSeasonLineupCaptureRankingEvidenceAnalyzer(initializedDatabase())
                .analyze(options.leagueId(), options.season()));
        } catch (SQLException e) {
            System.err.println("Database error while building league lineup-capture ranking evidence: " + e.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Options parse(String[] args) {
        if (!isCommand(args) || args.length != 4) {
            throw new IllegalArgumentException(
                "Usage: butler league season-lineup-capture-ranking-evidence <league-id> <season>");
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

    static void print(LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.LeagueRankingReport report) {
        var source = report.sourceCommonUniverse();
        System.out.println("League season lineup-capture ranking evidence");
        System.out.println("League: " + source.leagueName() + " [" + source.leagueId() + "]");
        System.out.println("Season: " + source.season());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Policy: " + report.policyId());
        System.out.println("Source common-universe policy: " + source.policyId());
        System.out.println("Minimum common-week governance floor: " + report.minimumCommonWeeks());
        System.out.println("Ranking policy: " + report.rankingPolicy());
        System.out.println("Repository teams: " + source.teams().size());
        System.out.println("Common comparable weeks: " + source.commonComparableWeeks());
        System.out.println("Common comparable week count: " + source.commonComparableWeeks().size());
        System.out.println("Ranking state: " + report.rankingState());
        System.out.println();

        if (report.rankingState() == LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankingState.AVAILABLE) {
            System.out.println("Governed lineup-capture ranks:");
            for (var team : report.rankedTeams()) {
                System.out.println("  lineup-capture rank " + team.rank() + " | " + team.teamName()
                    + " [" + team.teamId() + "]");
                System.out.println("    common lineup-capture rate: " + team.lineupCaptureRate().toPlainString());
                System.out.println("    common lineup-capture percentage: " + percentage(team.lineupCaptureRate()));
                System.out.println("    common total started points: " + points(team.commonTotalStartedPoints()));
                System.out.println("    common total potential points: " + points(team.commonTotalPotentialPoints()));
                System.out.println("    common total potential-minus-started gap: " + points(team.commonTotalPointsGap()));
                System.out.println("    common comparable weeks: " + team.commonComparableWeeks());
                System.out.println("    observed weeks: " + team.observedWeeks());
                System.out.println("    individually comparable weeks: " + team.individuallyComparableWeeks());
                System.out.println("    individually comparable but excluded from common: " + team.excludedComparableWeeks());
            }
        } else {
            System.out.println("Governed lineup-capture ranks: unavailable ("
                + unavailableReason(report.rankingState(), report.minimumCommonWeeks()) + ")");
            System.out.println("No partial ranking is published. Common-universe source evidence remains visible:");
            for (var team : source.teams()) {
                System.out.println("  " + team.teamName() + " [" + team.teamId() + "]"
                    + " | observed=" + team.observedWeeks()
                    + " | individually-comparable=" + team.individuallyComparableWeeks()
                    + " | common=" + team.commonComparableWeeks()
                    + " | rate-state=" + team.rateState()
                    + " | rate=" + team.lineupCaptureRate().map(BigDecimal::toPlainString).orElse("unavailable"));
            }
        }
        System.out.println();
        System.out.println("Boundary: this is an ordinal rank of the governed common-universe lineup-capture metric, "
            + "not a manager rank, manager-efficiency score, manager grade, skill estimate, fault assignment, or "
            + "decision-quality judgment. The four-week floor is a governance threshold, not statistical confidence. "
            + "Ties at governed six-decimal precision share competition rank; raw points gap and coverage never break "
            + "ties. Butler computes no tier, percentile, league average/median benchmark, winner/loser label, "
            + "recommendation, causal claim, confidence claim, or cross-league ranking. Historical startability remains "
            + "limited to the governed evidence actually persisted.");
    }

    private static String unavailableReason(
        LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankingState state,
        int minimumCommonWeeks) {
        return switch (state) {
            case UNAVAILABLE_INSUFFICIENT_TEAMS -> "fewer than two repository teams";
            case UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS -> "no all-team common comparable weeks";
            case UNAVAILABLE_BELOW_MINIMUM_COMMON_WEEKS ->
                "common comparable week count is below the v1 minimum of " + minimumCommonWeeks;
            case UNAVAILABLE_TEAM_COMMON_RATE ->
                "at least one repository team lacks an available normalized common-universe rate";
            case AVAILABLE -> throw new IllegalArgumentException("available ranking state requires ranked rows");
        };
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
