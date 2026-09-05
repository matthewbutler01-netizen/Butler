package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic leave-one-common-week-out sensitivity for the governed lineup-capture ranking.
 * This artifact measures rank/rate sensitivity only; it is not statistical confidence and does
 * not attribute stability, skill, fault, reliability, or quality to a manager.
 */
public final class LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer {
    public static final String POLICY_ID =
        "league-season-lineup-capture-ranking-stability-v1-leave-one-common-week-out-min-5-common-weeks-deterministic-no-confidence-claim";
    public static final String METRIC_SCOPE =
        "DETERMINISTIC_LEAVE_ONE_COMMON_WEEK_OUT_SENSITIVITY_OF_GOVERNED_LINEUP_CAPTURE_RANKS_NO_MANAGER_ATTRIBUTION_NO_STATISTICAL_CONFIDENCE";
    public static final String SENSITIVITY_POLICY =
        "OMIT_EACH_BASELINE_COMMON_WEEK_EXACTLY_ONCE_RECALCULATE_ALL_TEAMS_APPLY_BASELINE_COMPETITION_RANKING_NO_PARTIAL_SUMMARY";
    public static final int MINIMUM_COMMON_WEEKS_FOR_STABILITY =
        LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.MINIMUM_COMMON_WEEKS + 1;

    private final Database database;

    public LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public LeagueStabilityReport analyze(String leagueId, int season) throws SQLException {
        var baseline = new LeagueSeasonLineupCaptureRankingEvidenceAnalyzer(database).analyze(leagueId, season);
        return fromSource(baseline);
    }

    static LeagueStabilityReport fromSource(
        LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.LeagueRankingReport baseline) {
        Objects.requireNonNull(baseline, "baseline ranking report must not be null");
        Computed computed = compute(baseline);
        return new LeagueStabilityReport(
            POLICY_ID,
            METRIC_SCOPE,
            SENSITIVITY_POLICY,
            MINIMUM_COMMON_WEEKS_FOR_STABILITY,
            baseline,
            computed.state(),
            computed.scenarios(),
            computed.teamSummaries());
    }

    private static Computed compute(
        LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.LeagueRankingReport baseline) {
        if (baseline.rankingState() != LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankingState.AVAILABLE) {
            return new Computed(StabilityState.UNAVAILABLE_BASELINE_RANKING, List.of(), List.of());
        }

        List<Integer> commonWeeks = baseline.sourceCommonUniverse().commonComparableWeeks();
        if (commonWeeks.size() < MINIMUM_COMMON_WEEKS_FOR_STABILITY) {
            return new Computed(
                StabilityState.UNAVAILABLE_INSUFFICIENT_COMMON_WEEKS_FOR_PERTURBATION,
                List.of(),
                List.of());
        }

        List<PerturbationScenario> scenarios = new ArrayList<>();
        for (int omittedWeek : commonWeeks) {
            scenarios.add(computeScenario(baseline, omittedWeek));
        }
        List<PerturbationScenario> immutableScenarios = List.copyOf(scenarios);

        if (immutableScenarios.stream().anyMatch(scenario -> scenario.state() != ScenarioState.AVAILABLE)) {
            return new Computed(
                StabilityState.UNAVAILABLE_PERTURBATION_TEAM_RATE,
                immutableScenarios,
                List.of());
        }

        List<TeamStabilitySummary> summaries = baseline.rankedTeams().stream()
            .map(team -> summarizeTeam(team, immutableScenarios))
            .toList();
        return new Computed(StabilityState.AVAILABLE, immutableScenarios, summaries);
    }

    private static PerturbationScenario computeScenario(
        LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.LeagueRankingReport baseline,
        int omittedWeek) {
        List<Integer> baselineWeeks = baseline.sourceCommonUniverse().commonComparableWeeks();
        if (!baselineWeeks.contains(omittedWeek)) {
            throw new IllegalArgumentException("omitted sensitivity week must belong to baseline common weeks");
        }
        List<Integer> retainedWeeks = baselineWeeks.stream().filter(week -> week != omittedWeek).toList();
        if (retainedWeeks.size() < LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.MINIMUM_COMMON_WEEKS) {
            throw new IllegalStateException("stability perturbation must retain the governed ranking week floor");
        }

        List<ScenarioTeamEvidence> teamsWithoutRanks = baseline.sourceCommonUniverse().teams().stream()
            .map(team -> scenarioTeam(team, retainedWeeks))
            .toList();

        if (teamsWithoutRanks.stream().anyMatch(team -> team.rateState() != ScenarioRateState.AVAILABLE)) {
            return new PerturbationScenario(
                omittedWeek,
                retainedWeeks,
                ScenarioState.UNAVAILABLE_TEAM_RATE,
                teamsWithoutRanks);
        }

        List<ScenarioTeamEvidence> orderedForRanking = new ArrayList<>(teamsWithoutRanks);
        orderedForRanking.sort(Comparator
            .comparing(ScenarioTeamEvidence::lineupCaptureRateOrThrow, Comparator.reverseOrder())
            .thenComparing(ScenarioTeamEvidence::teamName));

        Map<String, Integer> ranksByTeam = new HashMap<>();
        BigDecimal previousRate = null;
        int previousRank = 0;
        for (int i = 0; i < orderedForRanking.size(); i++) {
            ScenarioTeamEvidence team = orderedForRanking.get(i);
            BigDecimal rate = team.lineupCaptureRateOrThrow();
            int rank = previousRate != null && rate.compareTo(previousRate) == 0 ? previousRank : i + 1;
            ranksByTeam.put(team.teamId(), rank);
            previousRate = rate;
            previousRank = rank;
        }

        List<ScenarioTeamEvidence> rankedInSourceOrder = teamsWithoutRanks.stream()
            .map(team -> team.withRank(ranksByTeam.get(team.teamId())))
            .toList();
        return new PerturbationScenario(
            omittedWeek,
            retainedWeeks,
            ScenarioState.AVAILABLE,
            rankedInSourceOrder);
    }

    private static ScenarioTeamEvidence scenarioTeam(
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.TeamCommonEvidence sourceTeam,
        List<Integer> retainedWeeks) {
        BigDecimal started = BigDecimal.ZERO;
        BigDecimal potential = BigDecimal.ZERO;
        BigDecimal gap = BigDecimal.ZERO;
        boolean negative = false;

        for (int week : retainedWeeks) {
            var pointsGap = comparableWeek(sourceTeam.sourceSeasonPointsGap(), week).pointsGap();
            started = started.add(pointsGap.startedPoints());
            potential = potential.add(pointsGap.potentialPoints());
            gap = gap.add(pointsGap.pointsGap());
            if (pointsGap.startedPoints().compareTo(BigDecimal.ZERO) < 0
                || pointsGap.potentialPoints().compareTo(BigDecimal.ZERO) < 0) {
                negative = true;
            }
        }

        ScenarioRateState rateState;
        Optional<BigDecimal> rate;
        if (negative) {
            rateState = ScenarioRateState.UNAVAILABLE_NEGATIVE_RETAINED_POINTS;
            rate = Optional.empty();
        } else if (potential.compareTo(BigDecimal.ZERO) <= 0) {
            rateState = ScenarioRateState.UNAVAILABLE_ZERO_TOTAL_POTENTIAL;
            rate = Optional.empty();
        } else {
            BigDecimal calculated = started.divide(
                potential,
                LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_SCALE,
                LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_ROUNDING);
            if (calculated.compareTo(BigDecimal.ZERO) < 0 || calculated.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalStateException(
                    "ranking stability invariant failed: perturbation rate must be between 0 and 1");
            }
            rateState = ScenarioRateState.AVAILABLE;
            rate = Optional.of(calculated);
        }

        return new ScenarioTeamEvidence(
            sourceTeam.teamId(),
            sourceTeam.teamName(),
            started,
            potential,
            gap,
            rateState,
            rate,
            Optional.empty());
    }

    private static LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekEvidence comparableWeek(
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport source,
        int weekNumber) {
        return source.weeks().stream()
            .filter(week -> week.week() == weekNumber
                && week.state() == LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.COMPARABLE_COMPLETE)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "ranking stability invariant failed: baseline common week missing from nested team evidence"));
    }

    private static TeamStabilitySummary summarizeTeam(
        LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.RankedTeamEvidence baselineTeam,
        List<PerturbationScenario> scenarios) {
        List<ScenarioTeamEvidence> observations = scenarios.stream()
            .map(scenario -> scenario.teams().stream()
                .filter(team -> team.teamId().equals(baselineTeam.teamId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "ranking stability invariant failed: perturbation team universe changed")))
            .toList();

        List<Integer> ranks = observations.stream().map(ScenarioTeamEvidence::rankOrThrow).toList();
        List<Integer> distinctRanks = new ArrayList<>(new LinkedHashSet<>(ranks));
        distinctRanks.sort(Integer::compareTo);
        int bestRank = ranks.stream().min(Integer::compareTo).orElseThrow();
        int worstRank = ranks.stream().max(Integer::compareTo).orElseThrow();
        int maxAbsoluteRankMovement = ranks.stream()
            .mapToInt(rank -> Math.abs(rank - baselineTeam.rank()))
            .max()
            .orElseThrow();
        int unchanged = (int) ranks.stream().filter(rank -> rank == baselineTeam.rank()).count();

        List<BigDecimal> rates = observations.stream().map(ScenarioTeamEvidence::lineupCaptureRateOrThrow).toList();
        BigDecimal minimumRate = rates.stream().min(BigDecimal::compareTo).orElseThrow();
        BigDecimal maximumRate = rates.stream().max(BigDecimal::compareTo).orElseThrow();
        BigDecimal maxAbsoluteRateMovement = rates.stream()
            .map(rate -> rate.subtract(baselineTeam.lineupCaptureRate()).abs())
            .max(BigDecimal::compareTo)
            .orElseThrow();

        return new TeamStabilitySummary(
            baselineTeam.teamId(),
            baselineTeam.teamName(),
            baselineTeam.rank(),
            baselineTeam.lineupCaptureRate(),
            scenarios.size(),
            List.copyOf(distinctRanks),
            bestRank,
            worstRank,
            worstRank - bestRank,
            maxAbsoluteRankMovement,
            unchanged,
            scenarios.size() - unchanged,
            minimumRate,
            maximumRate,
            maxAbsoluteRateMovement,
            unchanged == scenarios.size());
    }

    public enum StabilityState {
        AVAILABLE,
        UNAVAILABLE_BASELINE_RANKING,
        UNAVAILABLE_INSUFFICIENT_COMMON_WEEKS_FOR_PERTURBATION,
        UNAVAILABLE_PERTURBATION_TEAM_RATE
    }

    public enum ScenarioState {
        AVAILABLE,
        UNAVAILABLE_TEAM_RATE
    }

    public enum ScenarioRateState {
        AVAILABLE,
        UNAVAILABLE_ZERO_TOTAL_POTENTIAL,
        UNAVAILABLE_NEGATIVE_RETAINED_POINTS
    }

    public record ScenarioTeamEvidence(
        String teamId,
        String teamName,
        BigDecimal totalStartedPoints,
        BigDecimal totalPotentialPoints,
        BigDecimal totalPointsGap,
        ScenarioRateState rateState,
        Optional<BigDecimal> lineupCaptureRate,
        Optional<Integer> lineupCaptureRank) {

        public ScenarioTeamEvidence {
            teamId = requireText(teamId, "teamId");
            teamName = requireText(teamName, "teamName");
            Objects.requireNonNull(totalStartedPoints, "totalStartedPoints must not be null");
            Objects.requireNonNull(totalPotentialPoints, "totalPotentialPoints must not be null");
            Objects.requireNonNull(totalPointsGap, "totalPointsGap must not be null");
            Objects.requireNonNull(rateState, "rateState must not be null");
            lineupCaptureRate = Objects.requireNonNull(lineupCaptureRate, "lineupCaptureRate must not be null");
            lineupCaptureRank = Objects.requireNonNull(lineupCaptureRank, "lineupCaptureRank must not be null");
            if (totalPointsGap.compareTo(BigDecimal.ZERO) < 0
                || totalPotentialPoints.subtract(totalStartedPoints).compareTo(totalPointsGap) != 0) {
                throw new IllegalArgumentException(
                    "perturbation raw totals must preserve non-negative potential-minus-started gap");
            }
            if (rateState == ScenarioRateState.AVAILABLE) {
                BigDecimal rate = lineupCaptureRate.orElseThrow(
                    () -> new IllegalArgumentException("available perturbation rate requires lineupCaptureRate"));
                if (rate.scale() != LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_SCALE
                    || rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                    throw new IllegalArgumentException("available perturbation rate must be governed v1 precision within [0,1]");
                }
            } else if (lineupCaptureRate.isPresent() || lineupCaptureRank.isPresent()) {
                throw new IllegalArgumentException("unavailable perturbation team cannot expose rate or rank");
            }
            lineupCaptureRank.ifPresent(rank -> {
                if (rank <= 0) throw new IllegalArgumentException("perturbation rank must be positive");
                if (rateState != ScenarioRateState.AVAILABLE) {
                    throw new IllegalArgumentException("perturbation rank requires available rate");
                }
            });
        }

        private BigDecimal lineupCaptureRateOrThrow() {
            return lineupCaptureRate.orElseThrow();
        }

        private int rankOrThrow() {
            return lineupCaptureRank.orElseThrow();
        }

        private ScenarioTeamEvidence withRank(int rank) {
            return new ScenarioTeamEvidence(
                teamId,
                teamName,
                totalStartedPoints,
                totalPotentialPoints,
                totalPointsGap,
                rateState,
                lineupCaptureRate,
                Optional.of(rank));
        }
    }

    public record PerturbationScenario(
        int omittedCommonWeek,
        List<Integer> retainedCommonWeeks,
        ScenarioState state,
        List<ScenarioTeamEvidence> teams) {

        public PerturbationScenario {
            if (omittedCommonWeek <= 0) throw new IllegalArgumentException("omittedCommonWeek must be positive");
            retainedCommonWeeks = List.copyOf(Objects.requireNonNull(
                retainedCommonWeeks, "retainedCommonWeeks must not be null"));
            if (retainedCommonWeeks.size() < LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.MINIMUM_COMMON_WEEKS
                || retainedCommonWeeks.contains(omittedCommonWeek)) {
                throw new IllegalArgumentException("retained common weeks must exclude omitted week and preserve rank floor");
            }
            Objects.requireNonNull(state, "state must not be null");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
            if (teams.isEmpty()) throw new IllegalArgumentException("perturbation scenario must include teams");
            boolean anyUnavailable = teams.stream().anyMatch(team -> team.rateState() != ScenarioRateState.AVAILABLE);
            boolean anyRank = teams.stream().anyMatch(team -> team.lineupCaptureRank().isPresent());
            boolean allRanked = teams.stream().allMatch(team -> team.lineupCaptureRank().isPresent());
            if (state == ScenarioState.AVAILABLE && (anyUnavailable || !allRanked)) {
                throw new IllegalArgumentException("available perturbation scenario requires all team rates and ranks");
            }
            if (state == ScenarioState.UNAVAILABLE_TEAM_RATE && (!anyUnavailable || anyRank)) {
                throw new IllegalArgumentException("unavailable perturbation scenario cannot expose partial ranks");
            }
        }
    }

    public record TeamStabilitySummary(
        String teamId,
        String teamName,
        int baselineRank,
        BigDecimal baselineLineupCaptureRate,
        int perturbationScenarioCount,
        List<Integer> distinctPerturbationRanks,
        int bestPerturbationRank,
        int worstPerturbationRank,
        int rankSensitivityRangeWidth,
        int maximumAbsoluteRankMovement,
        int baselineRankUnchangedScenarios,
        int baselineRankChangedScenarios,
        BigDecimal minimumPerturbationRate,
        BigDecimal maximumPerturbationRate,
        BigDecimal maximumAbsoluteRateMovement,
        boolean rankUnchangedInAllScenarios) {

        public TeamStabilitySummary {
            teamId = requireText(teamId, "teamId");
            teamName = requireText(teamName, "teamName");
            if (baselineRank <= 0 || perturbationScenarioCount <= 0) {
                throw new IllegalArgumentException("summary baseline rank and scenario count must be positive");
            }
            Objects.requireNonNull(baselineLineupCaptureRate, "baselineLineupCaptureRate must not be null");
            distinctPerturbationRanks = List.copyOf(Objects.requireNonNull(
                distinctPerturbationRanks, "distinctPerturbationRanks must not be null"));
            if (distinctPerturbationRanks.isEmpty()
                || bestPerturbationRank <= 0
                || worstPerturbationRank < bestPerturbationRank
                || rankSensitivityRangeWidth != worstPerturbationRank - bestPerturbationRank
                || maximumAbsoluteRankMovement < 0
                || baselineRankUnchangedScenarios < 0
                || baselineRankChangedScenarios < 0
                || baselineRankUnchangedScenarios + baselineRankChangedScenarios != perturbationScenarioCount) {
                throw new IllegalArgumentException("team stability rank summary is inconsistent");
            }
            Objects.requireNonNull(minimumPerturbationRate, "minimumPerturbationRate must not be null");
            Objects.requireNonNull(maximumPerturbationRate, "maximumPerturbationRate must not be null");
            Objects.requireNonNull(maximumAbsoluteRateMovement, "maximumAbsoluteRateMovement must not be null");
            if (minimumPerturbationRate.compareTo(maximumPerturbationRate) > 0
                || maximumAbsoluteRateMovement.compareTo(BigDecimal.ZERO) < 0
                || rankUnchangedInAllScenarios != (baselineRankUnchangedScenarios == perturbationScenarioCount)) {
                throw new IllegalArgumentException("team stability rate or invariance summary is inconsistent");
            }
        }
    }

    public record LeagueStabilityReport(
        String policyId,
        String metricScope,
        String sensitivityPolicy,
        int minimumCommonWeeksForStability,
        LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.LeagueRankingReport sourceBaselineRanking,
        StabilityState stabilityState,
        List<PerturbationScenario> scenarios,
        List<TeamStabilitySummary> teamSummaries) {

        public LeagueStabilityReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            if (!SENSITIVITY_POLICY.equals(sensitivityPolicy)) {
                throw new IllegalArgumentException("unexpected sensitivityPolicy");
            }
            if (minimumCommonWeeksForStability != MINIMUM_COMMON_WEEKS_FOR_STABILITY) {
                throw new IllegalArgumentException("unexpected minimumCommonWeeksForStability");
            }
            Objects.requireNonNull(sourceBaselineRanking, "sourceBaselineRanking must not be null");
            Objects.requireNonNull(stabilityState, "stabilityState must not be null");
            scenarios = List.copyOf(Objects.requireNonNull(scenarios, "scenarios must not be null"));
            teamSummaries = List.copyOf(Objects.requireNonNull(teamSummaries, "teamSummaries must not be null"));

            Computed expected = compute(sourceBaselineRanking);
            if (stabilityState != expected.state()
                || !scenarios.equals(expected.scenarios())
                || !teamSummaries.equals(expected.teamSummaries())) {
                throw new IllegalArgumentException(
                    "ranking stability fields must match governed baseline ranking source evidence");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record Computed(
        StabilityState state,
        List<PerturbationScenario> scenarios,
        List<TeamStabilitySummary> teamSummaries) {}
}
