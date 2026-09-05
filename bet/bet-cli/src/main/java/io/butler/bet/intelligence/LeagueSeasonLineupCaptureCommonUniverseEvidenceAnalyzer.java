package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Recalculates every repository team's descriptive lineup-capture evidence over the exact same
 * all-team common comparable week universe. Rows remain in repository team-name order and this
 * artifact intentionally contains no rank, tier, league average, winner, or manager judgment.
 */
public final class LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer {
    public static final String POLICY_ID =
        "league-season-lineup-capture-common-universe-evidence-v1-all-repository-teams-common-comparable-weeks-neutral-no-ranking";
    public static final String METRIC_SCOPE =
        "RETROSPECTIVE_LEAGUE_TEAM_LINEUP_CAPTURE_OVER_ALL_REPOSITORY_TEAMS_COMMON_COMPARABLE_COMPLETE_OBSERVED_WEEKS_NO_MANAGER_ATTRIBUTION";
    public static final String WEEK_UNIVERSE =
        "INTERSECTION_OF_ALL_REPOSITORY_TEAMS_COMPARABLE_COMPLETE_OBSERVED_ROSTER_WEEKS";
    public static final String PRESENTATION_SCOPE =
        "ALL_REPOSITORY_TEAMS_REPOSITORY_TEAM_NAME_ORDER_COMMON_UNIVERSE_NO_RANKING_NO_LEAGUE_ARITHMETIC";

    private final Database database;

    public LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public LeagueCommonUniverseReport analyze(String leagueId, int season) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }

        var league = new LeagueRepository(database).findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + normalizedLeagueId));
        var seasonAnalyzer = new LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer(database);
        List<SourceTeam> sources = new ArrayList<>();
        for (var team : new TeamRepository(database).findByLeagueId(normalizedLeagueId)) {
            sources.add(new SourceTeam(
                team.getId(),
                team.getName(),
                seasonAnalyzer.analyze(normalizedLeagueId, team.getId(), season)));
        }

        return fromSources(normalizedLeagueId, league.getName(), season, sources);
    }

    private static LeagueCommonUniverseReport fromSources(
        String leagueId,
        String leagueName,
        int season,
        List<SourceTeam> sources) {
        Computed computed = compute(sources);
        return new LeagueCommonUniverseReport(
            POLICY_ID,
            METRIC_SCOPE,
            WEEK_UNIVERSE,
            PRESENTATION_SCOPE,
            LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.POLICY_ID,
            leagueId,
            leagueName,
            season,
            computed.state(),
            computed.commonWeeks(),
            computed.teams());
    }

    private static Computed compute(List<SourceTeam> inputSources) {
        List<SourceTeam> sources = List.copyOf(Objects.requireNonNull(inputSources, "sources must not be null"));
        requireSourceTeamSet(sources);

        if (sources.size() < 2) {
            return new Computed(
                CommonUniverseState.UNAVAILABLE_INSUFFICIENT_TEAMS,
                List.of(),
                sources.stream().map(source -> teamEvidence(source, List.of())).toList());
        }

        List<Integer> common = new ArrayList<>(comparableWeeks(sources.get(0).sourceSeasonPointsGap()));
        for (int i = 1; i < sources.size(); i++) {
            Set<Integer> comparable = new HashSet<>(comparableWeeks(sources.get(i).sourceSeasonPointsGap()));
            common.removeIf(week -> !comparable.contains(week));
        }
        common.sort(Integer::compareTo);
        List<Integer> immutableCommon = List.copyOf(common);

        if (immutableCommon.isEmpty()) {
            return new Computed(
                CommonUniverseState.UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS,
                immutableCommon,
                sources.stream().map(source -> teamEvidence(source, immutableCommon)).toList());
        }

        for (int week : immutableCommon) {
            var baseline = comparableWeek(sources.get(0).sourceSeasonPointsGap(), week).pointsGap();
            for (int i = 1; i < sources.size(); i++) {
                requireCompatibleCommonWeek(
                    baseline,
                    comparableWeek(sources.get(i).sourceSeasonPointsGap(), week).pointsGap());
            }
        }

        return new Computed(
            CommonUniverseState.AVAILABLE,
            immutableCommon,
            sources.stream().map(source -> teamEvidence(source, immutableCommon)).toList());
    }

    private static void requireSourceTeamSet(List<SourceTeam> sources) {
        Set<String> teamIds = new HashSet<>();
        String leagueId = null;
        Integer season = null;
        String previousTeamName = null;
        for (SourceTeam source : sources) {
            if (!teamIds.add(source.teamId())) {
                throw new IllegalArgumentException("league common-universe sources must contain distinct teams");
            }
            var seasonSource = source.sourceSeasonPointsGap();
            if (leagueId == null) {
                leagueId = seasonSource.leagueId();
                season = seasonSource.season();
            } else if (!leagueId.equals(seasonSource.leagueId()) || season != seasonSource.season()) {
                throw new IllegalArgumentException("league common-universe sources must share league and season");
            }
            if (previousTeamName != null && previousTeamName.compareTo(source.teamName()) > 0) {
                throw new IllegalArgumentException("teams must preserve repository team-name order");
            }
            previousTeamName = source.teamName();
        }
    }

    private static List<Integer> comparableWeeks(
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport source) {
        return source.weeks().stream()
            .filter(week -> week.state()
                == LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.COMPARABLE_COMPLETE)
            .map(LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekEvidence::week)
            .sorted()
            .toList();
    }

    private static LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekEvidence comparableWeek(
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport source,
        int weekNumber) {
        return source.weeks().stream()
            .filter(week -> week.week() == weekNumber
                && week.state() == LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.COMPARABLE_COMPLETE)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "league common-universe week disappeared from governed team source evidence"));
    }

    private static void requireCompatibleCommonWeek(
        LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.LineupPointsGapReport baseline,
        LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.LineupPointsGapReport candidate) {
        if (!baseline.leagueId().equals(candidate.leagueId())
            || baseline.season() != candidate.season()
            || baseline.week() != candidate.week()) {
            throw new IllegalStateException(
                "League common-universe lineup capture unavailable: common week identity differs");
        }
        if (!baseline.leagueConfigurationAsOf().equals(candidate.leagueConfigurationAsOf())
            || !baseline.rosterEvidenceAsOf().equals(candidate.rosterEvidenceAsOf())
            || !baseline.productionCoverageAsOf().equals(candidate.productionCoverageAsOf())
            || !baseline.productionSourceUri().equals(candidate.productionSourceUri())
            || !baseline.scoringPolicyId().equals(candidate.scoringPolicyId())
            || !baseline.solverPolicyId().equals(candidate.solverPolicyId())
            || !baseline.eligibilityPolicyId().equals(candidate.eligibilityPolicyId())
            || baseline.startingSlots() != candidate.startingSlots()) {
            throw new IllegalStateException(
                "League common-universe lineup capture unavailable: common week governed evidence boundary differs");
        }
    }

    private static TeamCommonEvidence teamEvidence(SourceTeam source, List<Integer> commonWeeks) {
        List<Integer> individuallyComparable = comparableWeeks(source.sourceSeasonPointsGap());
        List<Integer> excluded = individuallyComparable.stream()
            .filter(week -> !commonWeeks.contains(week))
            .toList();
        var aggregate = source.sourceSeasonPointsGap().aggregate();

        if (commonWeeks.isEmpty()) {
            return new TeamCommonEvidence(
                source.teamId(),
                source.teamName(),
                source.sourceSeasonPointsGap(),
                aggregate.observedWeeks(),
                aggregate.comparableCompleteWeeks(),
                excluded,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                CommonRateState.UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS,
                Optional.empty());
        }

        BigDecimal started = BigDecimal.ZERO;
        BigDecimal potential = BigDecimal.ZERO;
        BigDecimal gap = BigDecimal.ZERO;
        boolean negative = false;
        for (int week : commonWeeks) {
            var pointsGap = comparableWeek(source.sourceSeasonPointsGap(), week).pointsGap();
            started = started.add(pointsGap.startedPoints());
            potential = potential.add(pointsGap.potentialPoints());
            gap = gap.add(pointsGap.pointsGap());
            if (pointsGap.startedPoints().compareTo(BigDecimal.ZERO) < 0
                || pointsGap.potentialPoints().compareTo(BigDecimal.ZERO) < 0) {
                negative = true;
            }
        }

        CommonRateState state;
        Optional<BigDecimal> rate;
        if (negative) {
            state = CommonRateState.UNAVAILABLE_NEGATIVE_COMMON_POINTS;
            rate = Optional.empty();
        } else if (potential.compareTo(BigDecimal.ZERO) == 0) {
            state = CommonRateState.UNAVAILABLE_ZERO_TOTAL_POTENTIAL;
            rate = Optional.empty();
        } else {
            BigDecimal calculated = started.divide(
                potential,
                LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_SCALE,
                LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_ROUNDING);
            if (calculated.compareTo(BigDecimal.ZERO) < 0 || calculated.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalStateException(
                    "League common-universe invariant failed: team capture rate must be between 0 and 1");
            }
            state = CommonRateState.AVAILABLE;
            rate = Optional.of(calculated);
        }

        return new TeamCommonEvidence(
            source.teamId(),
            source.teamName(),
            source.sourceSeasonPointsGap(),
            aggregate.observedWeeks(),
            aggregate.comparableCompleteWeeks(),
            excluded,
            commonWeeks.size(),
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

    public enum CommonUniverseState {
        AVAILABLE,
        UNAVAILABLE_INSUFFICIENT_TEAMS,
        UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS
    }

    public enum CommonRateState {
        AVAILABLE,
        UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS,
        UNAVAILABLE_ZERO_TOTAL_POTENTIAL,
        UNAVAILABLE_NEGATIVE_COMMON_POINTS
    }

    public record TeamCommonEvidence(
        String teamId,
        String teamName,
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport sourceSeasonPointsGap,
        int observedWeeks,
        int individuallyComparableWeeks,
        List<Integer> excludedComparableWeeks,
        int commonComparableWeeks,
        Optional<BigDecimal> commonTotalStartedPoints,
        Optional<BigDecimal> commonTotalPotentialPoints,
        Optional<BigDecimal> commonTotalPointsGap,
        CommonRateState rateState,
        Optional<BigDecimal> lineupCaptureRate) {

        public TeamCommonEvidence {
            requireText(teamId, "teamId");
            requireText(teamName, "teamName");
            Objects.requireNonNull(sourceSeasonPointsGap, "sourceSeasonPointsGap must not be null");
            if (!teamId.equals(sourceSeasonPointsGap.teamId())) {
                throw new IllegalArgumentException("teamId must match nested season points-gap evidence");
            }
            if (observedWeeks != sourceSeasonPointsGap.aggregate().observedWeeks()
                || individuallyComparableWeeks != sourceSeasonPointsGap.aggregate().comparableCompleteWeeks()) {
                throw new IllegalArgumentException("row coverage counts must match nested governed source evidence");
            }
            excludedComparableWeeks = List.copyOf(Objects.requireNonNull(
                excludedComparableWeeks, "excludedComparableWeeks must not be null"));
            if (commonComparableWeeks < 0
                || commonComparableWeeks > individuallyComparableWeeks
                || excludedComparableWeeks.size() != individuallyComparableWeeks - commonComparableWeeks) {
                throw new IllegalArgumentException("common and excluded comparable week counts are inconsistent");
            }
            commonTotalStartedPoints = Objects.requireNonNull(
                commonTotalStartedPoints, "commonTotalStartedPoints must not be null");
            commonTotalPotentialPoints = Objects.requireNonNull(
                commonTotalPotentialPoints, "commonTotalPotentialPoints must not be null");
            commonTotalPointsGap = Objects.requireNonNull(
                commonTotalPointsGap, "commonTotalPointsGap must not be null");
            Objects.requireNonNull(rateState, "rateState must not be null");
            lineupCaptureRate = Objects.requireNonNull(lineupCaptureRate, "lineupCaptureRate must not be null");

            boolean anyTotals = commonTotalStartedPoints.isPresent()
                || commonTotalPotentialPoints.isPresent()
                || commonTotalPointsGap.isPresent();
            boolean allTotals = commonTotalStartedPoints.isPresent()
                && commonTotalPotentialPoints.isPresent()
                && commonTotalPointsGap.isPresent();
            if (commonComparableWeeks == 0) {
                if (anyTotals || rateState != CommonRateState.UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS
                    || lineupCaptureRate.isPresent()) {
                    throw new IllegalArgumentException("zero common weeks cannot expose totals or a capture rate");
                }
            } else {
                if (!allTotals) throw new IllegalArgumentException("common weeks require all raw totals");
                BigDecimal started = commonTotalStartedPoints.orElseThrow();
                BigDecimal potential = commonTotalPotentialPoints.orElseThrow();
                BigDecimal gap = commonTotalPointsGap.orElseThrow();
                if (gap.compareTo(BigDecimal.ZERO) < 0 || potential.subtract(started).compareTo(gap) != 0) {
                    throw new IllegalArgumentException(
                        "common raw totals must preserve non-negative potential-minus-started gap");
                }
                if (rateState == CommonRateState.AVAILABLE) {
                    BigDecimal rate = lineupCaptureRate.orElseThrow(
                        () -> new IllegalArgumentException("available common rate requires lineupCaptureRate"));
                    if (rate.scale() != LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_SCALE
                        || rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                        throw new IllegalArgumentException("available common rate must be v1 precision within [0,1]");
                    }
                } else if (lineupCaptureRate.isPresent()) {
                    throw new IllegalArgumentException("unavailable common rate cannot expose lineupCaptureRate");
                }
            }
        }
    }

    public record LeagueCommonUniverseReport(
        String policyId,
        String metricScope,
        String weekUniverse,
        String presentationScope,
        String teamSeasonPointsGapPolicyId,
        String leagueId,
        String leagueName,
        int season,
        CommonUniverseState commonUniverseState,
        List<Integer> commonComparableWeeks,
        List<TeamCommonEvidence> teams) {

        public LeagueCommonUniverseReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            if (!WEEK_UNIVERSE.equals(weekUniverse)) throw new IllegalArgumentException("unexpected weekUniverse");
            if (!PRESENTATION_SCOPE.equals(presentationScope)) {
                throw new IllegalArgumentException("unexpected presentationScope");
            }
            if (!LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.POLICY_ID.equals(teamSeasonPointsGapPolicyId)) {
                throw new IllegalArgumentException("unexpected teamSeasonPointsGapPolicyId");
            }
            requireText(leagueId, "leagueId");
            requireText(leagueName, "leagueName");
            if (season < 1999 || season > 2100) {
                throw new IllegalArgumentException("season must be between 1999 and 2100");
            }
            Objects.requireNonNull(commonUniverseState, "commonUniverseState must not be null");
            commonComparableWeeks = List.copyOf(Objects.requireNonNull(
                commonComparableWeeks, "commonComparableWeeks must not be null"));
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));

            List<SourceTeam> sources = new ArrayList<>();
            for (TeamCommonEvidence team : teams) {
                var source = team.sourceSeasonPointsGap();
                if (!leagueId.equals(source.leagueId()) || season != source.season()) {
                    throw new IllegalArgumentException("nested team evidence must match league and season");
                }
                sources.add(new SourceTeam(team.teamId(), team.teamName(), source));
            }
            Computed expected = compute(sources);
            if (commonUniverseState != expected.state()
                || !commonComparableWeeks.equals(expected.commonWeeks())
                || teams.size() != expected.teams().size()) {
                throw new IllegalArgumentException(
                    "league common-universe fields must match governed all-team source evidence");
            }
            for (int i = 0; i < teams.size(); i++) {
                TeamCommonEvidence actual = teams.get(i);
                TeamCommonEvidence expectedTeam = expected.teams().get(i);
                if (!actual.teamId().equals(expectedTeam.teamId())
                    || !actual.teamName().equals(expectedTeam.teamName())
                    || actual.observedWeeks() != expectedTeam.observedWeeks()
                    || actual.individuallyComparableWeeks() != expectedTeam.individuallyComparableWeeks()
                    || !actual.excludedComparableWeeks().equals(expectedTeam.excludedComparableWeeks())
                    || actual.commonComparableWeeks() != expectedTeam.commonComparableWeeks()
                    || !optionalDecimalEquals(actual.commonTotalStartedPoints(), expectedTeam.commonTotalStartedPoints())
                    || !optionalDecimalEquals(actual.commonTotalPotentialPoints(), expectedTeam.commonTotalPotentialPoints())
                    || !optionalDecimalEquals(actual.commonTotalPointsGap(), expectedTeam.commonTotalPointsGap())
                    || actual.rateState() != expectedTeam.rateState()
                    || !optionalDecimalEquals(actual.lineupCaptureRate(), expectedTeam.lineupCaptureRate())) {
                    throw new IllegalArgumentException(
                        "league common-universe team rows must match governed all-team source evidence");
                }
            }
        }
    }

    private record SourceTeam(
        String teamId,
        String teamName,
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport sourceSeasonPointsGap) {
        private SourceTeam {
            requireText(teamId, "teamId");
            requireText(teamName, "teamName");
            Objects.requireNonNull(sourceSeasonPointsGap, "sourceSeasonPointsGap must not be null");
            if (!teamId.equals(sourceSeasonPointsGap.teamId())) {
                throw new IllegalArgumentException("source teamId must match nested season points-gap evidence");
            }
        }
    }

    private record Computed(
        CommonUniverseState state,
        List<Integer> commonWeeks,
        List<TeamCommonEvidence> teams) {}
}
