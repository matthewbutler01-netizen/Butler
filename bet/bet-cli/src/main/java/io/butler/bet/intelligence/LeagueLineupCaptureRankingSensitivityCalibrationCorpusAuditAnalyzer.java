package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.LeagueRepository;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Audits the historical corpus available for future lineup-capture rank-sensitivity calibration.
 * The audit uses temporally disjoint baseline and future-only holdout windows. It does not fit
 * thresholds, estimate probabilities, adjust ranks, or evaluate managers.
 */
public final class LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer {
    public static final String POLICY_ID =
        "league-season-lineup-capture-ranking-sensitivity-calibration-audit-v1-temporal-disjoint-baseline-min5-holdout-min4-no-thresholds-no-confidence-no-manager-attribution";
    public static final String METRIC_SCOPE =
        "HISTORICAL_TEMPORAL_HOLDOUT_AUDIT_OF_GOVERNED_LINEUP_CAPTURE_RANK_SENSITIVITY_CORPUS_NO_THRESHOLD_FITTING_NO_CONFIDENCE_NO_MANAGER_ATTRIBUTION";
    public static final String AUDIT_POLICY =
        "ENUMERATE_PERSISTED_LEAGUES_AUDIT_ALL_COMMON_WEEK_BOUNDARIES_BASELINE_5PLUS_FUTURE_ONLY_HOLDOUT_4PLUS_PRESERVE_EXCLUSIONS_NO_CALIBRATION_MODEL";
    public static final int MINIMUM_BASELINE_COMMON_WEEKS =
        LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.MINIMUM_COMMON_WEEKS_FOR_STABILITY;
    public static final int MINIMUM_HOLDOUT_COMMON_WEEKS =
        LeagueSeasonLineupCaptureRankingEvidenceAnalyzer.MINIMUM_COMMON_WEEKS;

    private final Database database;

    public LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public CorpusAuditReport analyze(int startSeason, int endSeason) throws SQLException {
        validateSeasonRange(startSeason, endSeason);

        List<LeagueSeasonAudit> leagueSeasons = new ArrayList<>();
        List<LeagueSeasonSourceFailure> sourceFailures = new ArrayList<>();
        int leaguesWithoutSeason = 0;
        var commonAnalyzer = new LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer(database);
        var configurationRepository = new LeagueConfigurationObservationRepository(database);

        for (var league : new LeagueRepository(database).findAll()) {
            var candidateSeasons = new TreeSet<Integer>();
            Integer currentSeason = league.getSeason();
            if (currentSeason == null) {
                leaguesWithoutSeason++;
            } else {
                candidateSeasons.add(currentSeason);
            }
            candidateSeasons.addAll(configurationRepository.findObservedProviderSeasons(league.getId()));

            for (int season : candidateSeasons) {
                if (season < startSeason || season > endSeason) continue;
                try {
                    var source = commonAnalyzer.analyze(league.getId(), season);
                    leagueSeasons.add(LeagueSeasonAudit.fromSource(source));
                } catch (IllegalStateException sourceUnavailable) {
                    sourceFailures.add(new LeagueSeasonSourceFailure(
                        league.getId(), league.getName(), season, SourceFailureState.SOURCE_EVIDENCE_UNAVAILABLE));
                }
            }
        }

        return new CorpusAuditReport(
            POLICY_ID,
            METRIC_SCOPE,
            AUDIT_POLICY,
            MINIMUM_BASELINE_COMMON_WEEKS,
            MINIMUM_HOLDOUT_COMMON_WEEKS,
            startSeason,
            endSeason,
            leaguesWithoutSeason,
            List.copyOf(leagueSeasons),
            List.copyOf(sourceFailures),
            summarize(leagueSeasons, sourceFailures));
    }

    private static List<CutoffAudit> computeCutoffs(
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.LeagueCommonUniverseReport source) {
        if (source.commonUniverseState()
            != LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState.AVAILABLE) {
            return List.of();
        }

        List<Integer> commonWeeks = source.commonComparableWeeks();
        List<CutoffAudit> cutoffs = new ArrayList<>();
        for (int boundary = 1; boundary < commonWeeks.size(); boundary++) {
            List<Integer> baselineWeeks = List.copyOf(commonWeeks.subList(0, boundary));
            List<Integer> holdoutWeeks = List.copyOf(commonWeeks.subList(boundary, commonWeeks.size()));
            int cutoffAfterWeek = baselineWeeks.get(baselineWeeks.size() - 1);

            if (baselineWeeks.size() < MINIMUM_BASELINE_COMMON_WEEKS) {
                cutoffs.add(CutoffAudit.excluded(
                    cutoffAfterWeek, baselineWeeks, holdoutWeeks,
                    CutoffState.EXCLUDED_BASELINE_BELOW_STABILITY_FLOOR));
                continue;
            }
            if (holdoutWeeks.size() < MINIMUM_HOLDOUT_COMMON_WEEKS) {
                cutoffs.add(CutoffAudit.excluded(
                    cutoffAfterWeek, baselineWeeks, holdoutWeeks,
                    CutoffState.EXCLUDED_HOLDOUT_BELOW_RANKING_FLOOR));
                continue;
            }
            if (!configurationCompatible(source, baselineWeeks, holdoutWeeks)) {
                cutoffs.add(CutoffAudit.excluded(
                    cutoffAfterWeek, baselineWeeks, holdoutWeeks,
                    CutoffState.EXCLUDED_CONFIGURATION_INCOMPATIBLE));
                continue;
            }

            WindowRanking baseline = rankWindow(source, baselineWeeks);
            if (baseline.state() != WindowRankingState.AVAILABLE) {
                cutoffs.add(CutoffAudit.excluded(
                    cutoffAfterWeek, baselineWeeks, holdoutWeeks,
                    CutoffState.EXCLUDED_BASELINE_RANKING_UNAVAILABLE));
                continue;
            }

            StabilityComputation stability = stability(source, baselineWeeks, baseline);
            if (!stability.available()) {
                cutoffs.add(CutoffAudit.excluded(
                    cutoffAfterWeek, baselineWeeks, holdoutWeeks,
                    CutoffState.EXCLUDED_BASELINE_STABILITY_UNAVAILABLE));
                continue;
            }

            WindowRanking holdout = rankWindow(source, holdoutWeeks);
            if (holdout.state() != WindowRankingState.AVAILABLE) {
                cutoffs.add(CutoffAudit.excluded(
                    cutoffAfterWeek, baselineWeeks, holdoutWeeks,
                    CutoffState.EXCLUDED_HOLDOUT_RANKING_UNAVAILABLE));
                continue;
            }

            Map<String, WindowTeam> holdoutByTeam = indexByTeam(holdout.teams());
            Map<String, StabilityTeam> stabilityByTeam = indexStabilityByTeam(stability.teams());
            List<CalibrationTeamRow> rows = new ArrayList<>();
            for (WindowTeam baselineTeam : baseline.teams()) {
                WindowTeam futureTeam = requireTeam(holdoutByTeam, baselineTeam.teamId(), "future holdout");
                StabilityTeam sensitivity = requireStabilityTeam(stabilityByTeam, baselineTeam.teamId());
                int signedDisplacement = futureTeam.rank() - baselineTeam.rank();
                rows.add(new CalibrationTeamRow(
                    baselineTeam.teamId(),
                    baselineTeam.teamName(),
                    baselineTeam.rank(),
                    baselineTeam.lineupCaptureRate(),
                    baselineWeeks.size(),
                    sensitivity.maximumAbsoluteRankMovement(),
                    sensitivity.unchangedScenarios(),
                    sensitivity.changedScenarios(),
                    frequency(sensitivity.changedScenarios(), baselineWeeks.size()),
                    classify(sensitivity.maximumAbsoluteRankMovement()),
                    futureTeam.rank(),
                    futureTeam.lineupCaptureRate(),
                    holdoutWeeks.size(),
                    signedDisplacement,
                    Math.abs(signedDisplacement),
                    signedDisplacement == 0));
            }

            cutoffs.add(new CutoffAudit(
                cutoffAfterWeek,
                baselineWeeks,
                holdoutWeeks,
                CutoffState.AVAILABLE,
                List.copyOf(rows)));
        }
        return List.copyOf(cutoffs);
    }

    private static boolean configurationCompatible(
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.LeagueCommonUniverseReport source,
        List<Integer> baselineWeeks,
        List<Integer> holdoutWeeks) {
        if (source.teams().isEmpty()) return false;
        var seasonSource = source.teams().get(0).sourceSeasonPointsGap();
        List<Integer> weeks = new ArrayList<>(baselineWeeks);
        weeks.addAll(holdoutWeeks);
        if (weeks.isEmpty()) return false;

        var first = comparableWeek(seasonSource, weeks.get(0)).pointsGap();
        LocalDate configurationAsOf = first.leagueConfigurationAsOf();
        String scoringPolicy = first.scoringPolicyId();
        String solverPolicy = first.solverPolicyId();
        String eligibilityPolicy = first.eligibilityPolicyId();
        int startingSlots = first.startingSlots();

        for (int week : weeks) {
            var gap = comparableWeek(seasonSource, week).pointsGap();
            if (!configurationAsOf.equals(gap.leagueConfigurationAsOf())
                || !scoringPolicy.equals(gap.scoringPolicyId())
                || !solverPolicy.equals(gap.solverPolicyId())
                || !eligibilityPolicy.equals(gap.eligibilityPolicyId())
                || startingSlots != gap.startingSlots()) {
                return false;
            }
        }
        return true;
    }

    private static WindowRanking rankWindow(
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.LeagueCommonUniverseReport source,
        List<Integer> weeks) {
        List<WindowTeam> teams = new ArrayList<>();
        for (var sourceTeam : source.teams()) {
            BigDecimal started = BigDecimal.ZERO;
            BigDecimal potential = BigDecimal.ZERO;
            BigDecimal gap = BigDecimal.ZERO;
            boolean negative = false;
            for (int week : weeks) {
                var weekGap = comparableWeek(sourceTeam.sourceSeasonPointsGap(), week).pointsGap();
                started = started.add(weekGap.startedPoints());
                potential = potential.add(weekGap.potentialPoints());
                gap = gap.add(weekGap.pointsGap());
                if (weekGap.startedPoints().compareTo(BigDecimal.ZERO) < 0
                    || weekGap.potentialPoints().compareTo(BigDecimal.ZERO) < 0) {
                    negative = true;
                }
            }
            if (negative || potential.compareTo(BigDecimal.ZERO) <= 0) {
                return new WindowRanking(WindowRankingState.UNAVAILABLE_TEAM_RATE, List.of());
            }
            BigDecimal rate = started.divide(
                potential,
                LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_SCALE,
                LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_ROUNDING);
            if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                return new WindowRanking(WindowRankingState.UNAVAILABLE_TEAM_RATE, List.of());
            }
            teams.add(new WindowTeam(
                sourceTeam.teamId(), sourceTeam.teamName(), started, potential, gap, rate, 0));
        }

        List<WindowTeam> ordered = new ArrayList<>(teams);
        ordered.sort(Comparator
            .comparing(WindowTeam::lineupCaptureRate, Comparator.reverseOrder())
            .thenComparing(WindowTeam::teamName));
        Map<String, Integer> rankByTeam = new LinkedHashMap<>();
        BigDecimal previousRate = null;
        int previousRank = 0;
        for (int i = 0; i < ordered.size(); i++) {
            WindowTeam team = ordered.get(i);
            int rank = previousRate != null && team.lineupCaptureRate().compareTo(previousRate) == 0
                ? previousRank : i + 1;
            rankByTeam.put(team.teamId(), rank);
            previousRate = team.lineupCaptureRate();
            previousRank = rank;
        }

        List<WindowTeam> sourceOrder = teams.stream()
            .map(team -> team.withRank(rankByTeam.get(team.teamId())))
            .toList();
        return new WindowRanking(WindowRankingState.AVAILABLE, sourceOrder);
    }

    private static StabilityComputation stability(
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.LeagueCommonUniverseReport source,
        List<Integer> baselineWeeks,
        WindowRanking baseline) {
        Map<String, List<Integer>> ranksByTeam = new LinkedHashMap<>();
        for (WindowTeam team : baseline.teams()) ranksByTeam.put(team.teamId(), new ArrayList<>());

        for (int omittedWeek : baselineWeeks) {
            List<Integer> retained = baselineWeeks.stream().filter(week -> week != omittedWeek).toList();
            WindowRanking scenario = rankWindow(source, retained);
            if (scenario.state() != WindowRankingState.AVAILABLE) {
                return new StabilityComputation(false, List.of());
            }
            for (WindowTeam team : scenario.teams()) {
                List<Integer> ranks = ranksByTeam.get(team.teamId());
                if (ranks == null) return new StabilityComputation(false, List.of());
                ranks.add(team.rank());
            }
        }

        List<StabilityTeam> summaries = new ArrayList<>();
        for (WindowTeam baselineTeam : baseline.teams()) {
            List<Integer> ranks = ranksByTeam.get(baselineTeam.teamId());
            if (ranks == null || ranks.size() != baselineWeeks.size()) {
                return new StabilityComputation(false, List.of());
            }
            int maximumMovement = ranks.stream()
                .mapToInt(rank -> Math.abs(rank - baselineTeam.rank()))
                .max().orElseThrow();
            int unchanged = (int) ranks.stream().filter(rank -> rank == baselineTeam.rank()).count();
            summaries.add(new StabilityTeam(
                baselineTeam.teamId(), maximumMovement, unchanged, ranks.size() - unchanged));
        }
        return new StabilityComputation(true, List.copyOf(summaries));
    }

    private static LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekEvidence comparableWeek(
        LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.SeasonEvidenceReport source,
        int weekNumber) {
        return source.weeks().stream()
            .filter(week -> week.week() == weekNumber
                && week.state() == LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.COMPARABLE_COMPLETE)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "calibration audit invariant failed: common week missing from nested team evidence"));
    }

    private static BigDecimal frequency(int changedScenarios, int scenarioCount) {
        if (changedScenarios < 0 || scenarioCount <= 0 || changedScenarios > scenarioCount) {
            throw new IllegalArgumentException("frequency counts are inconsistent");
        }
        return BigDecimal.valueOf(changedScenarios).divide(
            BigDecimal.valueOf(scenarioCount),
            LeagueSeasonLineupCaptureRankingChangeFrequencyEvidenceAnalyzer.FREQUENCY_SCALE,
            LeagueSeasonLineupCaptureRankingChangeFrequencyEvidenceAnalyzer.FREQUENCY_ROUNDING);
    }

    private static LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass classify(
        int maximumAbsoluteRankMovement) {
        if (maximumAbsoluteRankMovement < 0) {
            throw new IllegalArgumentException("maximumAbsoluteRankMovement must not be negative");
        }
        if (maximumAbsoluteRankMovement == 0) {
            return LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass.LOW_SENSITIVITY;
        }
        if (maximumAbsoluteRankMovement == 1) {
            return LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass.MODERATE_SENSITIVITY;
        }
        return LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass.HIGH_SENSITIVITY;
    }

    private static Map<String, WindowTeam> indexByTeam(List<WindowTeam> teams) {
        Map<String, WindowTeam> result = new LinkedHashMap<>();
        for (WindowTeam team : teams) {
            if (result.put(team.teamId(), team) != null) {
                throw new IllegalStateException("calibration audit invariant failed: duplicate window team");
            }
        }
        return result;
    }

    private static Map<String, StabilityTeam> indexStabilityByTeam(List<StabilityTeam> teams) {
        Map<String, StabilityTeam> result = new LinkedHashMap<>();
        for (StabilityTeam team : teams) {
            if (result.put(team.teamId(), team) != null) {
                throw new IllegalStateException("calibration audit invariant failed: duplicate stability team");
            }
        }
        return result;
    }

    private static WindowTeam requireTeam(Map<String, WindowTeam> teams, String teamId, String label) {
        WindowTeam team = teams.get(teamId);
        if (team == null) {
            throw new IllegalStateException("calibration audit invariant failed: " + label + " team universe changed");
        }
        return team;
    }

    private static StabilityTeam requireStabilityTeam(Map<String, StabilityTeam> teams, String teamId) {
        StabilityTeam team = teams.get(teamId);
        if (team == null) {
            throw new IllegalStateException("calibration audit invariant failed: stability team universe changed");
        }
        return team;
    }

    private static LeagueSeasonAuditState deriveLeagueSeasonState(
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.LeagueCommonUniverseReport source,
        List<CutoffAudit> cutoffs) {
        if (source.commonUniverseState()
            != LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState.AVAILABLE) {
            return LeagueSeasonAuditState.EXCLUDED_COMMON_UNIVERSE_UNAVAILABLE;
        }
        return cutoffs.stream().anyMatch(cutoff -> cutoff.state() == CutoffState.AVAILABLE)
            ? LeagueSeasonAuditState.AVAILABLE_CALIBRATION_CUTOFFS
            : LeagueSeasonAuditState.AUDITED_NO_AVAILABLE_CUTOFFS;
    }

    private static CorpusSummary summarize(
        List<LeagueSeasonAudit> leagueSeasons,
        List<LeagueSeasonSourceFailure> sourceFailures) {
        int availableCutoffs = 0;
        int excludedCutoffs = 0;
        int teamCutoffRows = 0;
        Map<Integer, Integer> teamCountDistribution = new LinkedHashMap<>();
        Map<Integer, Integer> baselineWeekDistribution = new LinkedHashMap<>();
        Map<Integer, Integer> holdoutWeekDistribution = new LinkedHashMap<>();
        Map<Integer, Integer> perturbationDenominatorDistribution = new LinkedHashMap<>();
        Map<LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass, Integer>
            sensitivityClassCounts = new EnumMap<>(
                LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass.class);
        Map<CutoffState, Integer> cutoffStateCounts = new EnumMap<>(CutoffState.class);

        for (LeagueSeasonAudit leagueSeason : leagueSeasons) {
            teamCountDistribution.merge(leagueSeason.sourceCommonUniverse().teams().size(), 1, Integer::sum);
            for (CutoffAudit cutoff : leagueSeason.cutoffs()) {
                cutoffStateCounts.merge(cutoff.state(), 1, Integer::sum);
                baselineWeekDistribution.merge(cutoff.baselineCommonWeeks().size(), 1, Integer::sum);
                holdoutWeekDistribution.merge(cutoff.futureHoldoutCommonWeeks().size(), 1, Integer::sum);
                if (cutoff.state() == CutoffState.AVAILABLE) {
                    availableCutoffs++;
                    perturbationDenominatorDistribution.merge(
                        cutoff.baselineCommonWeeks().size(), 1, Integer::sum);
                    teamCutoffRows += cutoff.teams().size();
                    for (CalibrationTeamRow team : cutoff.teams()) {
                        sensitivityClassCounts.merge(team.baselineSensitivityClass(), 1, Integer::sum);
                    }
                } else {
                    excludedCutoffs++;
                }
            }
        }

        return new CorpusSummary(
            leagueSeasons.size() + sourceFailures.size(),
            leagueSeasons.size(),
            sourceFailures.size(),
            availableCutoffs,
            excludedCutoffs,
            teamCutoffRows,
            teamCountDistribution,
            baselineWeekDistribution,
            holdoutWeekDistribution,
            perturbationDenominatorDistribution,
            sensitivityClassCounts,
            cutoffStateCounts);
    }

    private static void validateSeasonRange(int startSeason, int endSeason) {
        if (startSeason < 1999 || endSeason > 2100 || startSeason > endSeason) {
            throw new IllegalArgumentException("season range must be within 1999..2100 and startSeason <= endSeason");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum SourceFailureState {
        SOURCE_EVIDENCE_UNAVAILABLE
    }

    public enum LeagueSeasonAuditState {
        AVAILABLE_CALIBRATION_CUTOFFS,
        AUDITED_NO_AVAILABLE_CUTOFFS,
        EXCLUDED_COMMON_UNIVERSE_UNAVAILABLE
    }

    public enum CutoffState {
        AVAILABLE,
        EXCLUDED_BASELINE_BELOW_STABILITY_FLOOR,
        EXCLUDED_HOLDOUT_BELOW_RANKING_FLOOR,
        EXCLUDED_CONFIGURATION_INCOMPATIBLE,
        EXCLUDED_BASELINE_RANKING_UNAVAILABLE,
        EXCLUDED_BASELINE_STABILITY_UNAVAILABLE,
        EXCLUDED_HOLDOUT_RANKING_UNAVAILABLE
    }

    private enum WindowRankingState {
        AVAILABLE,
        UNAVAILABLE_TEAM_RATE
    }

    public record CalibrationTeamRow(
        String teamId,
        String teamName,
        int baselineRank,
        BigDecimal baselineLineupCaptureRate,
        int baselineCommonWeekCount,
        int baselineMaximumAbsoluteRankMovement,
        int baselineRankUnchangedScenarios,
        int baselineRankChangedScenarios,
        BigDecimal baselineRankChangeFrequency,
        LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass baselineSensitivityClass,
        int futureHoldoutRank,
        BigDecimal futureHoldoutLineupCaptureRate,
        int futureHoldoutCommonWeekCount,
        int signedTemporalRankDisplacement,
        int absoluteTemporalRankDisplacement,
        boolean exactNumericRankRetained) {

        public CalibrationTeamRow {
            teamId = requireText(teamId, "teamId");
            teamName = requireText(teamName, "teamName");
            if (baselineRank <= 0 || futureHoldoutRank <= 0) {
                throw new IllegalArgumentException("calibration ranks must be positive");
            }
            Objects.requireNonNull(baselineLineupCaptureRate, "baselineLineupCaptureRate must not be null");
            Objects.requireNonNull(futureHoldoutLineupCaptureRate, "futureHoldoutLineupCaptureRate must not be null");
            Objects.requireNonNull(baselineRankChangeFrequency, "baselineRankChangeFrequency must not be null");
            Objects.requireNonNull(baselineSensitivityClass, "baselineSensitivityClass must not be null");
            if (baselineCommonWeekCount < MINIMUM_BASELINE_COMMON_WEEKS
                || futureHoldoutCommonWeekCount < MINIMUM_HOLDOUT_COMMON_WEEKS) {
                throw new IllegalArgumentException("calibration row must preserve baseline and holdout governance floors");
            }
            if (baselineMaximumAbsoluteRankMovement < 0
                || baselineRankUnchangedScenarios < 0
                || baselineRankChangedScenarios < 0
                || baselineRankUnchangedScenarios + baselineRankChangedScenarios != baselineCommonWeekCount) {
                throw new IllegalArgumentException("calibration baseline sensitivity counts are inconsistent");
            }
            BigDecimal expectedFrequency = frequency(baselineRankChangedScenarios, baselineCommonWeekCount);
            if (!baselineRankChangeFrequency.equals(expectedFrequency)) {
                throw new IllegalArgumentException("calibration frequency must match complete baseline perturbation counts");
            }
            if (baselineSensitivityClass != classify(baselineMaximumAbsoluteRankMovement)) {
                throw new IllegalArgumentException("calibration sensitivity class must match governed BF-508 movement rule");
            }
            int expectedSigned = futureHoldoutRank - baselineRank;
            if (signedTemporalRankDisplacement != expectedSigned
                || absoluteTemporalRankDisplacement != Math.abs(expectedSigned)
                || exactNumericRankRetained != (expectedSigned == 0)) {
                throw new IllegalArgumentException("calibration temporal displacement fields are inconsistent");
            }
        }
    }

    public record CutoffAudit(
        int cutoffAfterWeek,
        List<Integer> baselineCommonWeeks,
        List<Integer> futureHoldoutCommonWeeks,
        CutoffState state,
        List<CalibrationTeamRow> teams) {

        public CutoffAudit {
            if (cutoffAfterWeek <= 0) throw new IllegalArgumentException("cutoffAfterWeek must be positive");
            baselineCommonWeeks = List.copyOf(Objects.requireNonNull(
                baselineCommonWeeks, "baselineCommonWeeks must not be null"));
            futureHoldoutCommonWeeks = List.copyOf(Objects.requireNonNull(
                futureHoldoutCommonWeeks, "futureHoldoutCommonWeeks must not be null"));
            Objects.requireNonNull(state, "state must not be null");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
            if (baselineCommonWeeks.isEmpty() || baselineCommonWeeks.get(baselineCommonWeeks.size() - 1) != cutoffAfterWeek) {
                throw new IllegalArgumentException("cutoff must equal the final baseline common week");
            }
            if (baselineCommonWeeks.stream().anyMatch(futureHoldoutCommonWeeks::contains)) {
                throw new IllegalArgumentException("baseline and future holdout common weeks must be disjoint");
            }
            if (state == CutoffState.AVAILABLE) {
                if (baselineCommonWeeks.size() < MINIMUM_BASELINE_COMMON_WEEKS
                    || futureHoldoutCommonWeeks.size() < MINIMUM_HOLDOUT_COMMON_WEEKS
                    || teams.isEmpty()) {
                    throw new IllegalArgumentException("available calibration cutoff requires governed windows and team rows");
                }
            } else if (!teams.isEmpty()) {
                throw new IllegalArgumentException("excluded calibration cutoff cannot publish partial team rows");
            }
        }

        static CutoffAudit excluded(
            int cutoffAfterWeek,
            List<Integer> baselineWeeks,
            List<Integer> holdoutWeeks,
            CutoffState state) {
            if (state == CutoffState.AVAILABLE) {
                throw new IllegalArgumentException("excluded cutoff requires an exclusion state");
            }
            return new CutoffAudit(cutoffAfterWeek, baselineWeeks, holdoutWeeks, state, List.of());
        }
    }

    public record LeagueSeasonAudit(
        String leagueId,
        String leagueName,
        int season,
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.LeagueCommonUniverseReport sourceCommonUniverse,
        LeagueSeasonAuditState state,
        List<CutoffAudit> cutoffs) {

        public LeagueSeasonAudit {
            leagueId = requireText(leagueId, "leagueId");
            leagueName = requireText(leagueName, "leagueName");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be within 1999..2100");
            Objects.requireNonNull(sourceCommonUniverse, "sourceCommonUniverse must not be null");
            Objects.requireNonNull(state, "state must not be null");
            cutoffs = List.copyOf(Objects.requireNonNull(cutoffs, "cutoffs must not be null"));
            if (!leagueId.equals(sourceCommonUniverse.leagueId())
                || !leagueName.equals(sourceCommonUniverse.leagueName())
                || season != sourceCommonUniverse.season()) {
                throw new IllegalArgumentException("league-season audit identity must match nested common-universe source");
            }
            List<CutoffAudit> expectedCutoffs = computeCutoffs(sourceCommonUniverse);
            LeagueSeasonAuditState expectedState = deriveLeagueSeasonState(sourceCommonUniverse, expectedCutoffs);
            if (state != expectedState || !cutoffs.equals(expectedCutoffs)) {
                throw new IllegalArgumentException("league-season calibration audit must match governed source evidence");
            }
        }

        static LeagueSeasonAudit fromSource(
            LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.LeagueCommonUniverseReport source) {
            List<CutoffAudit> cutoffs = computeCutoffs(source);
            return new LeagueSeasonAudit(
                source.leagueId(), source.leagueName(), source.season(), source,
                deriveLeagueSeasonState(source, cutoffs), cutoffs);
        }
    }

    public record LeagueSeasonSourceFailure(
        String leagueId,
        String leagueName,
        int season,
        SourceFailureState state) {

        public LeagueSeasonSourceFailure {
            leagueId = requireText(leagueId, "leagueId");
            leagueName = requireText(leagueName, "leagueName");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be within 1999..2100");
            Objects.requireNonNull(state, "state must not be null");
        }
    }

    public record CorpusSummary(
        int requestedLeagueSeasons,
        int auditedLeagueSeasons,
        int sourceFailureLeagueSeasons,
        int availableCutoffs,
        int excludedCutoffs,
        int availableTeamCutoffRows,
        Map<Integer, Integer> teamCountDistribution,
        Map<Integer, Integer> baselineCommonWeekCountDistribution,
        Map<Integer, Integer> futureHoldoutCommonWeekCountDistribution,
        Map<Integer, Integer> perturbationDenominatorDistribution,
        Map<LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass, Integer>
            baselineSensitivityClassCounts,
        Map<CutoffState, Integer> cutoffStateCounts) {

        public CorpusSummary {
            if (requestedLeagueSeasons < 0 || auditedLeagueSeasons < 0 || sourceFailureLeagueSeasons < 0
                || availableCutoffs < 0 || excludedCutoffs < 0 || availableTeamCutoffRows < 0
                || auditedLeagueSeasons + sourceFailureLeagueSeasons != requestedLeagueSeasons) {
                throw new IllegalArgumentException("corpus summary counts are inconsistent");
            }
            teamCountDistribution = Map.copyOf(Objects.requireNonNull(
                teamCountDistribution, "teamCountDistribution must not be null"));
            baselineCommonWeekCountDistribution = Map.copyOf(Objects.requireNonNull(
                baselineCommonWeekCountDistribution, "baselineCommonWeekCountDistribution must not be null"));
            futureHoldoutCommonWeekCountDistribution = Map.copyOf(Objects.requireNonNull(
                futureHoldoutCommonWeekCountDistribution, "futureHoldoutCommonWeekCountDistribution must not be null"));
            perturbationDenominatorDistribution = Map.copyOf(Objects.requireNonNull(
                perturbationDenominatorDistribution, "perturbationDenominatorDistribution must not be null"));
            baselineSensitivityClassCounts = Map.copyOf(Objects.requireNonNull(
                baselineSensitivityClassCounts, "baselineSensitivityClassCounts must not be null"));
            cutoffStateCounts = Map.copyOf(Objects.requireNonNull(
                cutoffStateCounts, "cutoffStateCounts must not be null"));
        }
    }

    public record CorpusAuditReport(
        String policyId,
        String metricScope,
        String auditPolicy,
        int minimumBaselineCommonWeeks,
        int minimumFutureHoldoutCommonWeeks,
        int requestedStartSeason,
        int requestedEndSeason,
        int leaguesWithoutSeason,
        List<LeagueSeasonAudit> leagueSeasons,
        List<LeagueSeasonSourceFailure> sourceFailures,
        CorpusSummary summary) {

        public CorpusAuditReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            if (!AUDIT_POLICY.equals(auditPolicy)) throw new IllegalArgumentException("unexpected auditPolicy");
            if (minimumBaselineCommonWeeks != MINIMUM_BASELINE_COMMON_WEEKS
                || minimumFutureHoldoutCommonWeeks != MINIMUM_HOLDOUT_COMMON_WEEKS) {
                throw new IllegalArgumentException("unexpected calibration audit week floors");
            }
            validateSeasonRange(requestedStartSeason, requestedEndSeason);
            if (leaguesWithoutSeason < 0) throw new IllegalArgumentException("leaguesWithoutSeason must not be negative");
            leagueSeasons = List.copyOf(Objects.requireNonNull(leagueSeasons, "leagueSeasons must not be null"));
            sourceFailures = List.copyOf(Objects.requireNonNull(sourceFailures, "sourceFailures must not be null"));
            Objects.requireNonNull(summary, "summary must not be null");
            for (LeagueSeasonAudit leagueSeason : leagueSeasons) {
                if (leagueSeason.season() < requestedStartSeason || leagueSeason.season() > requestedEndSeason) {
                    throw new IllegalArgumentException("audited league season falls outside requested range");
                }
            }
            for (LeagueSeasonSourceFailure failure : sourceFailures) {
                if (failure.season() < requestedStartSeason || failure.season() > requestedEndSeason) {
                    throw new IllegalArgumentException("source failure falls outside requested range");
                }
            }
            CorpusSummary expected = summarize(leagueSeasons, sourceFailures);
            if (!summary.equals(expected)) {
                throw new IllegalArgumentException("corpus summary must match nested league-season audit evidence");
            }
        }
    }

    private record WindowTeam(
        String teamId,
        String teamName,
        BigDecimal startedPoints,
        BigDecimal potentialPoints,
        BigDecimal pointsGap,
        BigDecimal lineupCaptureRate,
        int rank) {
        private WindowTeam {
            requireText(teamId, "teamId");
            requireText(teamName, "teamName");
            Objects.requireNonNull(startedPoints, "startedPoints must not be null");
            Objects.requireNonNull(potentialPoints, "potentialPoints must not be null");
            Objects.requireNonNull(pointsGap, "pointsGap must not be null");
            Objects.requireNonNull(lineupCaptureRate, "lineupCaptureRate must not be null");
            if (pointsGap.compareTo(BigDecimal.ZERO) < 0
                || potentialPoints.subtract(startedPoints).compareTo(pointsGap) != 0) {
                throw new IllegalArgumentException("window totals must preserve potential-minus-started gap");
            }
            if (lineupCaptureRate.scale() != LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_SCALE
                || lineupCaptureRate.compareTo(BigDecimal.ZERO) < 0
                || lineupCaptureRate.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("window rate must use governed v1 precision within [0,1]");
            }
            if (rank < 0) throw new IllegalArgumentException("window rank must not be negative");
        }

        WindowTeam withRank(int assignedRank) {
            if (assignedRank <= 0) throw new IllegalArgumentException("assignedRank must be positive");
            return new WindowTeam(
                teamId, teamName, startedPoints, potentialPoints, pointsGap, lineupCaptureRate, assignedRank);
        }
    }

    private record WindowRanking(WindowRankingState state, List<WindowTeam> teams) {
        private WindowRanking {
            Objects.requireNonNull(state, "state must not be null");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
            if (state == WindowRankingState.AVAILABLE) {
                if (teams.isEmpty() || teams.stream().anyMatch(team -> team.rank() <= 0)) {
                    throw new IllegalArgumentException("available window ranking requires ranked teams");
                }
            } else if (!teams.isEmpty()) {
                throw new IllegalArgumentException("unavailable window ranking cannot publish partial rows");
            }
        }
    }

    private record StabilityTeam(
        String teamId,
        int maximumAbsoluteRankMovement,
        int unchangedScenarios,
        int changedScenarios) {
        private StabilityTeam {
            requireText(teamId, "teamId");
            if (maximumAbsoluteRankMovement < 0 || unchangedScenarios < 0 || changedScenarios < 0) {
                throw new IllegalArgumentException("stability summary counts must not be negative");
            }
        }
    }

    private record StabilityComputation(boolean available, List<StabilityTeam> teams) {
        private StabilityComputation {
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
            if (available && teams.isEmpty()) {
                throw new IllegalArgumentException("available stability computation requires teams");
            }
            if (!available && !teams.isEmpty()) {
                throw new IllegalArgumentException("unavailable stability computation cannot publish partial rows");
            }
        }
    }
}
