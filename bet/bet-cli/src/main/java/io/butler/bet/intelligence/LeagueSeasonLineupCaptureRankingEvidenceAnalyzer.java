package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Assigns descriptive ordinal lineup-capture ranks only from the governed all-team common-universe
 * report. The artifact ranks the governed metric, not managers, skill, fault, intent, or quality.
 */
public final class LeagueSeasonLineupCaptureRankingEvidenceAnalyzer {
    public static final String POLICY_ID =
        "league-season-lineup-capture-ranking-v1-common-universe-min-4-weeks-competition-ranking-no-manager-attribution";
    public static final String METRIC_SCOPE =
        "RETROSPECTIVE_ORDINAL_COMMON_UNIVERSE_LINEUP_CAPTURE_RATE_RANKING_MIN_4_COMMON_WEEKS_NO_MANAGER_ATTRIBUTION";
    public static final String RANKING_POLICY =
        "DESCENDING_GOVERNED_SIX_DECIMAL_RATE_STANDARD_COMPETITION_RANKING_NO_SECONDARY_TIEBREAKER";
    public static final int MINIMUM_COMMON_WEEKS = 4;

    private final Database database;

    public LeagueSeasonLineupCaptureRankingEvidenceAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public LeagueRankingReport analyze(String leagueId, int season) throws SQLException {
        var source = new LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer(database)
            .analyze(leagueId, season);
        return fromSource(source);
    }

    static LeagueRankingReport fromSource(
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.LeagueCommonUniverseReport source) {
        Objects.requireNonNull(source, "source common-universe report must not be null");
        Computed computed = compute(source);
        return new LeagueRankingReport(
            POLICY_ID,
            METRIC_SCOPE,
            MINIMUM_COMMON_WEEKS,
            RANKING_POLICY,
            source,
            computed.state(),
            computed.rankedTeams());
    }

    private static Computed compute(
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.LeagueCommonUniverseReport source) {
        if (source.teams().size() < 2
            || source.commonUniverseState()
                == LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState.UNAVAILABLE_INSUFFICIENT_TEAMS) {
            return new Computed(RankingState.UNAVAILABLE_INSUFFICIENT_TEAMS, List.of());
        }
        if (source.commonUniverseState()
            == LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState.UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS
            || source.commonComparableWeeks().isEmpty()) {
            return new Computed(RankingState.UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS, List.of());
        }
        if (source.commonComparableWeeks().size() < MINIMUM_COMMON_WEEKS) {
            return new Computed(RankingState.UNAVAILABLE_BELOW_MINIMUM_COMMON_WEEKS, List.of());
        }
        for (var team : source.teams()) {
            if (team.rateState()
                    != LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonRateState.AVAILABLE
                || team.lineupCaptureRate().isEmpty()) {
                return new Computed(RankingState.UNAVAILABLE_TEAM_COMMON_RATE, List.of());
            }
        }

        List<LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.TeamCommonEvidence> ordered =
            new ArrayList<>(source.teams());
        ordered.sort(Comparator
            .comparing(
                (LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.TeamCommonEvidence team) ->
                    team.lineupCaptureRate().orElseThrow(),
                Comparator.reverseOrder())
            .thenComparing(LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.TeamCommonEvidence::teamName));

        List<RankedTeamEvidence> ranked = new ArrayList<>();
        BigDecimal previousRate = null;
        int previousRank = 0;
        for (int i = 0; i < ordered.size(); i++) {
            var team = ordered.get(i);
            BigDecimal rate = team.lineupCaptureRate().orElseThrow();
            int rank;
            if (previousRate != null && rate.compareTo(previousRate) == 0) {
                rank = previousRank;
            } else {
                rank = i + 1;
            }
            ranked.add(RankedTeamEvidence.fromSource(rank, team));
            previousRate = rate;
            previousRank = rank;
        }
        return new Computed(RankingState.AVAILABLE, List.copyOf(ranked));
    }

    public enum RankingState {
        AVAILABLE,
        UNAVAILABLE_INSUFFICIENT_TEAMS,
        UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS,
        UNAVAILABLE_BELOW_MINIMUM_COMMON_WEEKS,
        UNAVAILABLE_TEAM_COMMON_RATE
    }

    public record RankedTeamEvidence(
        int rank,
        String teamId,
        String teamName,
        BigDecimal lineupCaptureRate,
        BigDecimal commonTotalStartedPoints,
        BigDecimal commonTotalPotentialPoints,
        BigDecimal commonTotalPointsGap,
        int commonComparableWeeks,
        int observedWeeks,
        int individuallyComparableWeeks,
        List<Integer> excludedComparableWeeks) {

        public RankedTeamEvidence {
            if (rank <= 0) throw new IllegalArgumentException("rank must be positive");
            teamId = requireText(teamId, "teamId");
            teamName = requireText(teamName, "teamName");
            Objects.requireNonNull(lineupCaptureRate, "lineupCaptureRate must not be null");
            Objects.requireNonNull(commonTotalStartedPoints, "commonTotalStartedPoints must not be null");
            Objects.requireNonNull(commonTotalPotentialPoints, "commonTotalPotentialPoints must not be null");
            Objects.requireNonNull(commonTotalPointsGap, "commonTotalPointsGap must not be null");
            if (lineupCaptureRate.scale() != LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_SCALE
                || lineupCaptureRate.compareTo(BigDecimal.ZERO) < 0
                || lineupCaptureRate.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("ranked lineupCaptureRate must be governed v1 precision within [0,1]");
            }
            if (commonComparableWeeks < MINIMUM_COMMON_WEEKS) {
                throw new IllegalArgumentException("ranked row requires the v1 minimum common-week floor");
            }
            if (observedWeeks < 0 || individuallyComparableWeeks < 0
                || commonComparableWeeks > individuallyComparableWeeks
                || individuallyComparableWeeks > observedWeeks) {
                throw new IllegalArgumentException("ranked row coverage counts are inconsistent");
            }
            excludedComparableWeeks = List.copyOf(Objects.requireNonNull(
                excludedComparableWeeks, "excludedComparableWeeks must not be null"));
            if (excludedComparableWeeks.size() != individuallyComparableWeeks - commonComparableWeeks) {
                throw new IllegalArgumentException("ranked excluded comparable weeks must match coverage counts");
            }
            if (commonTotalPointsGap.compareTo(BigDecimal.ZERO) < 0
                || commonTotalPotentialPoints.subtract(commonTotalStartedPoints)
                    .compareTo(commonTotalPointsGap) != 0) {
                throw new IllegalArgumentException("ranked raw totals must preserve potential-minus-started gap");
            }
        }

        static RankedTeamEvidence fromSource(
            int rank,
            LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.TeamCommonEvidence source) {
            if (source.rateState()
                    != LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonRateState.AVAILABLE) {
                throw new IllegalArgumentException("ranked row requires available common-universe rate");
            }
            return new RankedTeamEvidence(
                rank,
                source.teamId(),
                source.teamName(),
                source.lineupCaptureRate().orElseThrow(),
                source.commonTotalStartedPoints().orElseThrow(),
                source.commonTotalPotentialPoints().orElseThrow(),
                source.commonTotalPointsGap().orElseThrow(),
                source.commonComparableWeeks(),
                source.observedWeeks(),
                source.individuallyComparableWeeks(),
                source.excludedComparableWeeks());
        }
    }

    public record LeagueRankingReport(
        String policyId,
        String metricScope,
        int minimumCommonWeeks,
        String rankingPolicy,
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.LeagueCommonUniverseReport sourceCommonUniverse,
        RankingState rankingState,
        List<RankedTeamEvidence> rankedTeams) {

        public LeagueRankingReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            if (minimumCommonWeeks != MINIMUM_COMMON_WEEKS) {
                throw new IllegalArgumentException("unexpected minimumCommonWeeks");
            }
            if (!RANKING_POLICY.equals(rankingPolicy)) throw new IllegalArgumentException("unexpected rankingPolicy");
            Objects.requireNonNull(sourceCommonUniverse, "sourceCommonUniverse must not be null");
            Objects.requireNonNull(rankingState, "rankingState must not be null");
            rankedTeams = List.copyOf(Objects.requireNonNull(rankedTeams, "rankedTeams must not be null"));

            Computed expected = compute(sourceCommonUniverse);
            if (rankingState != expected.state() || !rankedTeams.equals(expected.rankedTeams())) {
                throw new IllegalArgumentException(
                    "ranking fields must match governed common-universe source evidence");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record Computed(RankingState state, List<RankedTeamEvidence> rankedTeams) {}
}
