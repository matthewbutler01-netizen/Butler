package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Normalizes governed team-season lineup points-gap totals over comparable complete observed weeks
 * without hiding coverage or attributing the result to manager skill, fault, or intent.
 */
public final class LeagueTeamSeasonLineupCaptureEvidenceAnalyzer {
    public static final String POLICY_ID =
        "team-season-lineup-capture-evidence-v1-comparable-complete-total-ratio-no-attribution";
    public static final String METRIC_SCOPE =
        "RETROSPECTIVE_TEAM_SEASON_LINEUP_CAPTURE_RATE_OVER_COMPARABLE_COMPLETE_OBSERVED_ROSTER_WEEKS_NO_MANAGER_ATTRIBUTION";

    private final Database database;

    public LeagueTeamSeasonLineupCaptureEvidenceAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public SeasonLineupCaptureReport analyze(String leagueId, String teamId, int season) throws SQLException {
        var source = new LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer(database)
            .analyze(leagueId, teamId, season);
        return fromSource(source);
    }

    static SeasonLineupCaptureReport fromSource(
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport source) {
        Objects.requireNonNull(source, "source must not be null");
        CaptureRateState state = expectedState(source);
        Optional<BigDecimal> rate = state == CaptureRateState.AVAILABLE
            ? Optional.of(calculateRate(source))
            : Optional.empty();
        return new SeasonLineupCaptureReport(POLICY_ID, METRIC_SCOPE, source, state, rate);
    }

    private static CaptureRateState expectedState(
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport source) {
        var aggregate = source.aggregate();
        if (aggregate.comparableCompleteWeeks() == 0) {
            return CaptureRateState.UNAVAILABLE_NO_COMPARABLE_WEEKS;
        }
        for (var week : source.weeks()) {
            if (week.state() != LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.COMPARABLE_COMPLETE) {
                continue;
            }
            if (week.pointsGap().startedPoints().compareTo(BigDecimal.ZERO) < 0
                || week.pointsGap().potentialPoints().compareTo(BigDecimal.ZERO) < 0) {
                return CaptureRateState.UNAVAILABLE_NEGATIVE_COMPARABLE_POINTS;
            }
        }
        BigDecimal potential = aggregate.comparableTotalPotentialPoints().orElseThrow();
        if (potential.compareTo(BigDecimal.ZERO) == 0) {
            return CaptureRateState.UNAVAILABLE_ZERO_TOTAL_POTENTIAL;
        }
        return CaptureRateState.AVAILABLE;
    }

    private static BigDecimal calculateRate(
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport source) {
        var aggregate = source.aggregate();
        BigDecimal started = aggregate.comparableTotalStartedPoints().orElseThrow();
        BigDecimal potential = aggregate.comparableTotalPotentialPoints().orElseThrow();
        BigDecimal rate = started.divide(
            potential,
            LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_SCALE,
            LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_ROUNDING);
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException(
                "Season lineup capture invariant failed: normalized rate must be between 0 and 1");
        }
        return rate;
    }

    public enum CaptureRateState {
        AVAILABLE,
        UNAVAILABLE_NO_COMPARABLE_WEEKS,
        UNAVAILABLE_ZERO_TOTAL_POTENTIAL,
        UNAVAILABLE_NEGATIVE_COMPARABLE_POINTS
    }

    public record SeasonLineupCaptureReport(
        String policyId,
        String metricScope,
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport sourceSeasonPointsGap,
        CaptureRateState rateState,
        Optional<BigDecimal> lineupCaptureRate) {

        public SeasonLineupCaptureReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            Objects.requireNonNull(sourceSeasonPointsGap, "sourceSeasonPointsGap must not be null");
            Objects.requireNonNull(rateState, "rateState must not be null");
            lineupCaptureRate = Objects.requireNonNull(lineupCaptureRate, "lineupCaptureRate must not be null");

            CaptureRateState expectedState = expectedState(sourceSeasonPointsGap);
            if (rateState != expectedState) {
                throw new IllegalArgumentException("rateState must match governed season source evidence");
            }
            if (rateState == CaptureRateState.AVAILABLE) {
                BigDecimal actual = lineupCaptureRate.orElseThrow(
                    () -> new IllegalArgumentException("available season capture evidence requires lineupCaptureRate"));
                BigDecimal expected = calculateRate(sourceSeasonPointsGap);
                if (actual.scale() != LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_SCALE
                    || !actual.equals(expected)) {
                    throw new IllegalArgumentException(
                        "lineupCaptureRate must equal comparable governed started total divided by potential total at v1 precision");
                }
            } else if (lineupCaptureRate.isPresent()) {
                throw new IllegalArgumentException("unavailable season capture evidence cannot include lineupCaptureRate");
            }
        }
    }
}
