package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Normalizes one governed complete team-week lineup points-gap report into descriptive lineup
 * capture evidence without attributing the result to manager skill, fault, or intent.
 */
public final class LeagueTeamWeekLineupCaptureEvidenceAnalyzer {
    public static final String POLICY_ID =
        "team-week-lineup-capture-evidence-v1-complete-gap-source-started-over-potential-no-attribution";
    public static final String METRIC_SCOPE =
        "RETROSPECTIVE_LINEUP_CAPTURE_RATE_FROM_COMPLETE_GOVERNED_TEAM_WEEK_POINTS_GAP_STARTED_OVER_POTENTIAL_NO_MANAGER_ATTRIBUTION";
    public static final int RATE_SCALE = 6;
    public static final RoundingMode RATE_ROUNDING = RoundingMode.HALF_UP;

    private final Database database;

    public LeagueTeamWeekLineupCaptureEvidenceAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public LineupCaptureReport analyze(String leagueId, String teamId, int season, int week)
        throws SQLException {
        var source = new LeagueTeamWeekLineupPointsGapEvidenceAnalyzer(database)
            .analyze(leagueId, teamId, season, week);
        return fromSource(source);
    }

    static LineupCaptureReport fromSource(
        LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.LineupPointsGapReport source) {
        Objects.requireNonNull(source, "source must not be null");
        CaptureRateState state = expectedState(source);
        Optional<BigDecimal> rate = state == CaptureRateState.AVAILABLE
            ? Optional.of(calculateRate(source.startedPoints(), source.potentialPoints()))
            : Optional.empty();
        return new LineupCaptureReport(POLICY_ID, METRIC_SCOPE, source, state, rate);
    }

    private static CaptureRateState expectedState(
        LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.LineupPointsGapReport source) {
        if (source.startedPoints().compareTo(BigDecimal.ZERO) < 0
            || source.potentialPoints().compareTo(BigDecimal.ZERO) < 0) {
            return CaptureRateState.UNAVAILABLE_NEGATIVE_POINTS;
        }
        if (source.potentialPoints().compareTo(BigDecimal.ZERO) == 0) {
            return CaptureRateState.UNAVAILABLE_ZERO_POTENTIAL;
        }
        return CaptureRateState.AVAILABLE;
    }

    private static BigDecimal calculateRate(BigDecimal startedPoints, BigDecimal potentialPoints) {
        BigDecimal rate = startedPoints.divide(potentialPoints, RATE_SCALE, RATE_ROUNDING);
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException("Lineup capture invariant failed: normalized rate must be between 0 and 1");
        }
        return rate;
    }

    public enum CaptureRateState {
        AVAILABLE,
        UNAVAILABLE_ZERO_POTENTIAL,
        UNAVAILABLE_NEGATIVE_POINTS
    }

    public record LineupCaptureReport(
        String policyId,
        String metricScope,
        LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.LineupPointsGapReport sourcePointsGap,
        CaptureRateState rateState,
        Optional<BigDecimal> lineupCaptureRate) {

        public LineupCaptureReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            Objects.requireNonNull(sourcePointsGap, "sourcePointsGap must not be null");
            Objects.requireNonNull(rateState, "rateState must not be null");
            lineupCaptureRate = Objects.requireNonNull(lineupCaptureRate, "lineupCaptureRate must not be null");

            CaptureRateState expectedState = expectedState(sourcePointsGap);
            if (rateState != expectedState) {
                throw new IllegalArgumentException("rateState must match governed source point values");
            }
            if (rateState == CaptureRateState.AVAILABLE) {
                BigDecimal actual = lineupCaptureRate.orElseThrow(
                    () -> new IllegalArgumentException("available capture evidence requires lineupCaptureRate"));
                BigDecimal expected = calculateRate(
                    sourcePointsGap.startedPoints(), sourcePointsGap.potentialPoints());
                if (actual.scale() != RATE_SCALE || !actual.equals(expected)) {
                    throw new IllegalArgumentException(
                        "lineupCaptureRate must equal governed started points divided by potential points at v1 precision");
                }
            } else if (lineupCaptureRate.isPresent()) {
                throw new IllegalArgumentException("unavailable capture evidence cannot include lineupCaptureRate");
            }
        }
    }
}
