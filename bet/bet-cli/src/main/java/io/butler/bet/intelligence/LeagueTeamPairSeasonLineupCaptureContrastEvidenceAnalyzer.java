package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Compares two teams' governed retrospective lineup-capture evidence only over the exact same
 * shared comparable observed weeks. The signed contrast is descriptive and does not establish
 * manager quality, rank, skill, fault, intent, or recommendation.
 */
public final class LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer {
    public static final String POLICY_ID =
        "team-pair-season-lineup-capture-contrast-evidence-v1-shared-comparable-weeks-no-attribution";
    public static final String METRIC_SCOPE =
        "RETROSPECTIVE_PAIRWISE_LINEUP_CAPTURE_CONTRAST_OVER_SHARED_COMPARABLE_COMPLETE_OBSERVED_WEEKS_NO_MANAGER_ATTRIBUTION";
    public static final String WEEK_UNIVERSE =
        "INTERSECTION_OF_BOTH_TEAMS_COMPARABLE_COMPLETE_OBSERVED_ROSTER_WEEKS";

    private final Database database;

    public LeagueTeamPairSeasonLineupCaptureContrastEvidenceAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public PairwiseContrastReport analyze(
        String leagueId,
        String teamAId,
        String teamBId,
        int season) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedTeamAId = requireText(teamAId, "teamAId");
        String normalizedTeamBId = requireText(teamBId, "teamBId");
        if (normalizedTeamAId.equals(normalizedTeamBId)) {
            throw new IllegalArgumentException("teamAId and teamBId must identify distinct teams");
        }
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }

        var analyzer = new LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer(database);
        var teamA = analyzer.analyze(normalizedLeagueId, normalizedTeamAId, season);
        var teamB = analyzer.analyze(normalizedLeagueId, normalizedTeamBId, season);
        return fromSources(teamA, teamB);
    }

    static PairwiseContrastReport fromSources(
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport teamA,
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport teamB) {
        Objects.requireNonNull(teamA, "teamA source must not be null");
        Objects.requireNonNull(teamB, "teamB source must not be null");
        if (!teamA.leagueId().equals(teamB.leagueId()) || teamA.season() != teamB.season()) {
            throw new IllegalArgumentException("pairwise sources must target the same league and season");
        }
        if (teamA.teamId().equals(teamB.teamId())) {
            throw new IllegalArgumentException("pairwise sources must target distinct teams");
        }

        Computed computed = compute(teamA, teamB);
        return new PairwiseContrastReport(
            POLICY_ID,
            METRIC_SCOPE,
            WEEK_UNIVERSE,
            teamA,
            teamB,
            computed.sharedWeeks(),
            computed.teamAOnlyWeeks(),
            computed.teamBOnlyWeeks(),
            computed.teamA(),
            computed.teamB(),
            computed.contrastState(),
            computed.captureRateContrast());
    }

    private static Computed compute(
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport teamA,
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport teamB) {
        List<Integer> aComparable = comparableWeeks(teamA);
        List<Integer> bComparable = comparableWeeks(teamB);
        List<Integer> shared = aComparable.stream().filter(bComparable::contains).toList();
        List<Integer> aOnly = aComparable.stream().filter(week -> !bComparable.contains(week)).toList();
        List<Integer> bOnly = bComparable.stream().filter(week -> !aComparable.contains(week)).toList();

        for (int week : shared) {
            requireCompatibleSharedWeek(
                comparableWeek(teamA, week).pointsGap(),
                comparableWeek(teamB, week).pointsGap());
        }

        TeamSharedEvidence aEvidence = sharedEvidence(teamA, shared);
        TeamSharedEvidence bEvidence = sharedEvidence(teamB, shared);
        ContrastState contrastState;
        Optional<BigDecimal> contrast;
        if (shared.isEmpty()) {
            contrastState = ContrastState.UNAVAILABLE_NO_SHARED_COMPARABLE_WEEKS;
            contrast = Optional.empty();
        } else if (aEvidence.rateState() != SharedRateState.AVAILABLE
            || bEvidence.rateState() != SharedRateState.AVAILABLE) {
            contrastState = ContrastState.UNAVAILABLE_TEAM_SHARED_RATE;
            contrast = Optional.empty();
        } else {
            contrastState = ContrastState.AVAILABLE;
            contrast = Optional.of(aEvidence.lineupCaptureRate().orElseThrow()
                .subtract(bEvidence.lineupCaptureRate().orElseThrow())
                .setScale(
                    LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_SCALE,
                    LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_ROUNDING));
        }
        return new Computed(shared, aOnly, bOnly, aEvidence, bEvidence, contrastState, contrast);
    }

    private static List<Integer> comparableWeeks(
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport source) {
        return source.weeks().stream()
            .filter(week -> week.state()
                == LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.COMPARABLE_COMPLETE)
            .map(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekEvidence::week)
            .toList();
    }

    private static LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekEvidence comparableWeek(
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport source,
        int weekNumber) {
        return source.weeks().stream()
            .filter(week -> week.week() == weekNumber
                && week.state() == LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.COMPARABLE_COMPLETE)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("shared comparable week disappeared from source evidence"));
    }

    private static void requireCompatibleSharedWeek(
        LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.LineupPointsGapReport teamA,
        LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.LineupPointsGapReport teamB) {
        if (!teamA.leagueId().equals(teamB.leagueId())
            || teamA.season() != teamB.season()
            || teamA.week() != teamB.week()) {
            throw new IllegalStateException("Pairwise lineup contrast unavailable: shared week identity differs");
        }
        if (!teamA.leagueConfigurationAsOf().equals(teamB.leagueConfigurationAsOf())
            || !teamA.rosterEvidenceAsOf().equals(teamB.rosterEvidenceAsOf())
            || !teamA.productionCoverageAsOf().equals(teamB.productionCoverageAsOf())
            || !teamA.productionSourceUri().equals(teamB.productionSourceUri())
            || !teamA.scoringPolicyId().equals(teamB.scoringPolicyId())
            || !teamA.solverPolicyId().equals(teamB.solverPolicyId())
            || !teamA.eligibilityPolicyId().equals(teamB.eligibilityPolicyId())
            || teamA.startingSlots() != teamB.startingSlots()) {
            throw new IllegalStateException(
                "Pairwise lineup contrast unavailable: shared week governed evidence boundary differs");
        }
    }

    private static TeamSharedEvidence sharedEvidence(
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport source,
        List<Integer> sharedWeeks) {
        if (sharedWeeks.isEmpty()) {
            return new TeamSharedEvidence(
                source.teamId(),
                source.aggregate().observedWeeks(),
                source.aggregate().comparableCompleteWeeks(),
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                SharedRateState.UNAVAILABLE_NO_SHARED_COMPARABLE_WEEKS,
                Optional.empty());
        }

        BigDecimal started = BigDecimal.ZERO;
        BigDecimal potential = BigDecimal.ZERO;
        BigDecimal gap = BigDecimal.ZERO;
        boolean negative = false;
        for (int week : sharedWeeks) {
            var pointsGap = comparableWeek(source, week).pointsGap();
            started = started.add(pointsGap.startedPoints());
            potential = potential.add(pointsGap.potentialPoints());
            gap = gap.add(pointsGap.pointsGap());
            if (pointsGap.startedPoints().compareTo(BigDecimal.ZERO) < 0
                || pointsGap.potentialPoints().compareTo(BigDecimal.ZERO) < 0) {
                negative = true;
            }
        }

        SharedRateState state;
        Optional<BigDecimal> rate;
        if (negative) {
            state = SharedRateState.UNAVAILABLE_NEGATIVE_COMPARABLE_POINTS;
            rate = Optional.empty();
        } else if (potential.compareTo(BigDecimal.ZERO) == 0) {
            state = SharedRateState.UNAVAILABLE_ZERO_TOTAL_POTENTIAL;
            rate = Optional.empty();
        } else {
            state = SharedRateState.AVAILABLE;
            BigDecimal calculated = started.divide(
                potential,
                LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_SCALE,
                LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_ROUNDING);
            if (calculated.compareTo(BigDecimal.ZERO) < 0 || calculated.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalStateException(
                    "Pairwise lineup contrast invariant failed: shared-week capture rate must be between 0 and 1");
            }
            rate = Optional.of(calculated);
        }

        return new TeamSharedEvidence(
            source.teamId(),
            source.aggregate().observedWeeks(),
            source.aggregate().comparableCompleteWeeks(),
            sharedWeeks.size(),
            Optional.of(started),
            Optional.of(potential),
            Optional.of(gap),
            state,
            rate);
    }

    private static boolean optionalDecimalEquals(Optional<BigDecimal> left, Optional<BigDecimal> right) {
        if (left.isEmpty() || right.isEmpty()) return left.isEmpty() && right.isEmpty();
        return left.orElseThrow().compareTo(right.orElseThrow()) == 0;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum SharedRateState {
        AVAILABLE,
        UNAVAILABLE_NO_SHARED_COMPARABLE_WEEKS,
        UNAVAILABLE_ZERO_TOTAL_POTENTIAL,
        UNAVAILABLE_NEGATIVE_COMPARABLE_POINTS
    }

    public enum ContrastState {
        AVAILABLE,
        UNAVAILABLE_NO_SHARED_COMPARABLE_WEEKS,
        UNAVAILABLE_TEAM_SHARED_RATE
    }

    public record TeamSharedEvidence(
        String teamId,
        int observedWeeks,
        int individuallyComparableWeeks,
        int sharedComparableWeeks,
        Optional<BigDecimal> sharedTotalStartedPoints,
        Optional<BigDecimal> sharedTotalPotentialPoints,
        Optional<BigDecimal> sharedTotalPointsGap,
        SharedRateState rateState,
        Optional<BigDecimal> lineupCaptureRate) {

        public TeamSharedEvidence {
            requireText(teamId, "teamId");
            if (observedWeeks < 0 || individuallyComparableWeeks < 0 || sharedComparableWeeks < 0) {
                throw new IllegalArgumentException("week counts must not be negative");
            }
            if (sharedComparableWeeks > individuallyComparableWeeks || individuallyComparableWeeks > observedWeeks) {
                throw new IllegalArgumentException("shared/comparable/observed week counts are inconsistent");
            }
            sharedTotalStartedPoints = Objects.requireNonNull(
                sharedTotalStartedPoints, "sharedTotalStartedPoints must not be null");
            sharedTotalPotentialPoints = Objects.requireNonNull(
                sharedTotalPotentialPoints, "sharedTotalPotentialPoints must not be null");
            sharedTotalPointsGap = Objects.requireNonNull(
                sharedTotalPointsGap, "sharedTotalPointsGap must not be null");
            Objects.requireNonNull(rateState, "rateState must not be null");
            lineupCaptureRate = Objects.requireNonNull(lineupCaptureRate, "lineupCaptureRate must not be null");

            boolean anyTotals = sharedTotalStartedPoints.isPresent()
                || sharedTotalPotentialPoints.isPresent()
                || sharedTotalPointsGap.isPresent();
            boolean allTotals = sharedTotalStartedPoints.isPresent()
                && sharedTotalPotentialPoints.isPresent()
                && sharedTotalPointsGap.isPresent();
            if (sharedComparableWeeks == 0) {
                if (anyTotals || rateState != SharedRateState.UNAVAILABLE_NO_SHARED_COMPARABLE_WEEKS
                    || lineupCaptureRate.isPresent()) {
                    throw new IllegalArgumentException("zero shared weeks cannot expose totals or a capture rate");
                }
            } else {
                if (!allTotals) throw new IllegalArgumentException("shared weeks require all raw totals");
                BigDecimal started = sharedTotalStartedPoints.orElseThrow();
                BigDecimal potential = sharedTotalPotentialPoints.orElseThrow();
                BigDecimal gap = sharedTotalPointsGap.orElseThrow();
                if (gap.compareTo(BigDecimal.ZERO) < 0 || potential.subtract(started).compareTo(gap) != 0) {
                    throw new IllegalArgumentException("shared raw totals must preserve non-negative potential-minus-started gap");
                }
                if (rateState == SharedRateState.AVAILABLE) {
                    BigDecimal rate = lineupCaptureRate.orElseThrow(
                        () -> new IllegalArgumentException("available shared rate requires lineupCaptureRate"));
                    if (rate.scale() != LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_SCALE
                        || rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                        throw new IllegalArgumentException("available shared rate must be v1 precision within [0,1]");
                    }
                } else if (lineupCaptureRate.isPresent()) {
                    throw new IllegalArgumentException("unavailable shared rate cannot expose lineupCaptureRate");
                }
            }
        }
    }

    public record PairwiseContrastReport(
        String policyId,
        String metricScope,
        String weekUniverse,
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport teamASourceSeason,
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport teamBSourceSeason,
        List<Integer> sharedComparableWeeks,
        List<Integer> teamAOnlyComparableWeeks,
        List<Integer> teamBOnlyComparableWeeks,
        TeamSharedEvidence teamA,
        TeamSharedEvidence teamB,
        ContrastState contrastState,
        Optional<BigDecimal> lineupCaptureRateContrast) {

        public PairwiseContrastReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            if (!WEEK_UNIVERSE.equals(weekUniverse)) throw new IllegalArgumentException("unexpected weekUniverse");
            Objects.requireNonNull(teamASourceSeason, "teamASourceSeason must not be null");
            Objects.requireNonNull(teamBSourceSeason, "teamBSourceSeason must not be null");
            sharedComparableWeeks = List.copyOf(Objects.requireNonNull(
                sharedComparableWeeks, "sharedComparableWeeks must not be null"));
            teamAOnlyComparableWeeks = List.copyOf(Objects.requireNonNull(
                teamAOnlyComparableWeeks, "teamAOnlyComparableWeeks must not be null"));
            teamBOnlyComparableWeeks = List.copyOf(Objects.requireNonNull(
                teamBOnlyComparableWeeks, "teamBOnlyComparableWeeks must not be null"));
            Objects.requireNonNull(teamA, "teamA must not be null");
            Objects.requireNonNull(teamB, "teamB must not be null");
            Objects.requireNonNull(contrastState, "contrastState must not be null");
            lineupCaptureRateContrast = Objects.requireNonNull(
                lineupCaptureRateContrast, "lineupCaptureRateContrast must not be null");

            if (!teamASourceSeason.leagueId().equals(teamBSourceSeason.leagueId())
                || teamASourceSeason.season() != teamBSourceSeason.season()) {
                throw new IllegalArgumentException("pairwise source identity must share league and season");
            }
            if (teamASourceSeason.teamId().equals(teamBSourceSeason.teamId())) {
                throw new IllegalArgumentException("pairwise source teams must be distinct");
            }

            Computed expected = compute(teamASourceSeason, teamBSourceSeason);
            if (!sharedComparableWeeks.equals(expected.sharedWeeks())
                || !teamAOnlyComparableWeeks.equals(expected.teamAOnlyWeeks())
                || !teamBOnlyComparableWeeks.equals(expected.teamBOnlyWeeks())
                || !teamA.equals(expected.teamA())
                || !teamB.equals(expected.teamB())
                || contrastState != expected.contrastState()
                || !optionalDecimalEquals(lineupCaptureRateContrast, expected.captureRateContrast())) {
                throw new IllegalArgumentException(
                    "pairwise contrast fields must match governed shared-week source evidence");
            }
            if (lineupCaptureRateContrast.isPresent()
                && lineupCaptureRateContrast.orElseThrow().scale()
                    != LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_SCALE) {
                throw new IllegalArgumentException("lineupCaptureRateContrast must use v1 precision");
            }
        }
    }

    private record Computed(
        List<Integer> sharedWeeks,
        List<Integer> teamAOnlyWeeks,
        List<Integer> teamBOnlyWeeks,
        TeamSharedEvidence teamA,
        TeamSharedEvidence teamB,
        ContrastState contrastState,
        Optional<BigDecimal> captureRateContrast) {}
}
