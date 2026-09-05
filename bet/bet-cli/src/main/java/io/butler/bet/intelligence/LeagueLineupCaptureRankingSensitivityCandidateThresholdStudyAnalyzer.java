package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Performs the BF-525 leakage-safe, leave-one-league-season-out descriptive candidate study.
 * Candidate values are observed development-cluster breakpoints only. This analyzer does not
 * select a winner, fit a threshold, estimate confidence/probability, adjust ranks, or score managers.
 */
public final class LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer {
    public static final String POLICY_ID =
        "league-lineup-capture-ranking-sensitivity-candidate-threshold-study-v1-leave-one-league-season-out-observed-single-axis-breakpoints-descriptive-no-selection-no-confidence";
    public static final String METRIC_SCOPE =
        "CLUSTER_AWARE_DESCRIPTIVE_STUDY_OF_OBSERVED_SINGLE_AXIS_SENSITIVITY_CANDIDATES_AGAINST_FUTURE_ONLY_ORDINAL_PERSISTENCE_NO_SELECTION_NO_CONFIDENCE_NO_MANAGER_ATTRIBUTION";
    public static final String STUDY_POLICY =
        "LEAVE_ONE_LEAGUE_SEASON_OUT_GENERATE_CANDIDATES_FROM_DEVELOPMENT_BASELINE_FEATURES_ONLY_EVALUATE_HELD_OUT_CLUSTER_NO_WINNER_NO_OPTIMIZATION";

    private final Database database;

    public LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public CandidateThresholdStudyReport analyze(int startSeason, int endSeason) throws SQLException {
        var readiness = new LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer(database)
            .analyze(startSeason, endSeason);
        return fromSource(readiness);
    }

    static CandidateThresholdStudyReport fromSource(
        LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.CorpusReadinessReport readiness) {
        Objects.requireNonNull(readiness, "readiness source must not be null");
        Computed computed = compute(readiness);
        return new CandidateThresholdStudyReport(
            POLICY_ID,
            METRIC_SCOPE,
            STUDY_POLICY,
            readiness,
            computed.state(),
            computed.folds(),
            computed.frequencyCandidates(),
            computed.magnitudeCandidates());
    }

    private static Computed compute(
        LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.CorpusReadinessReport readiness) {
        if (readiness.readinessState()
            != LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.ReadinessState
                .READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN) {
            return new Computed(StudyState.UNAVAILABLE_CORPUS_NOT_STRUCTURALLY_READY, List.of(), List.of(), List.of());
        }

        List<Cluster> clusters = availableClusters(readiness.sourceCorpusAudit());
        if (clusters.size() < 2) {
            return new Computed(StudyState.UNAVAILABLE_INSUFFICIENT_EVALUATION_CLUSTERS, List.of(), List.of(), List.of());
        }

        List<FoldEvaluation> folds = new ArrayList<>();
        Set<FrequencyCandidate> allFrequencyCandidates = new TreeSet<>(FREQUENCY_ORDER);
        Set<MagnitudeCandidate> allMagnitudeCandidates = new TreeSet<>(Comparator.comparingInt(MagnitudeCandidate::maximumMovementCutoff));

        for (Cluster heldOut : clusters) {
            List<Cluster> development = clusters.stream()
                .filter(cluster -> !cluster.identity().equals(heldOut.identity()))
                .toList();

            List<FrequencyCandidate> frequencyCandidates = generateFrequencyCandidates(development);
            List<MagnitudeCandidate> magnitudeCandidates = generateMagnitudeCandidates(development);
            allFrequencyCandidates.addAll(frequencyCandidates);
            allMagnitudeCandidates.addAll(magnitudeCandidates);

            List<FrequencyFoldEvaluation> frequencyEvaluations = frequencyCandidates.stream()
                .map(candidate -> evaluateFrequency(heldOut, candidate))
                .toList();
            List<MagnitudeFoldEvaluation> magnitudeEvaluations = magnitudeCandidates.stream()
                .map(candidate -> evaluateMagnitude(heldOut, candidate))
                .toList();

            folds.add(new FoldEvaluation(
                heldOut.leagueId(),
                heldOut.season(),
                heldOut.identity(),
                development.size(),
                heldOut.repositoryTeamCount(),
                heldOut.availableCutoffs().size(),
                heldOut.rows().size(),
                frequencyEvaluations,
                magnitudeEvaluations));
        }

        List<FrequencyCandidateSummary> frequencySummaries = allFrequencyCandidates.stream()
            .map(candidate -> summarizeFrequency(candidate, folds))
            .toList();
        List<MagnitudeCandidateSummary> magnitudeSummaries = allMagnitudeCandidates.stream()
            .map(candidate -> summarizeMagnitude(candidate, folds))
            .toList();

        return new Computed(StudyState.AVAILABLE, List.copyOf(folds), frequencySummaries, magnitudeSummaries);
    }

    private static List<Cluster> availableClusters(
        LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CorpusAuditReport audit) {
        List<Cluster> clusters = new ArrayList<>();
        for (var leagueSeason : audit.leagueSeasons()) {
            List<LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffAudit> availableCutoffs =
                leagueSeason.cutoffs().stream()
                    .filter(cutoff -> cutoff.state()
                        == LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffState.AVAILABLE)
                    .toList();
            if (availableCutoffs.isEmpty()) continue;

            List<RowContext> rows = new ArrayList<>();
            int teamCount = leagueSeason.sourceCommonUniverse().teams().size();
            for (var cutoff : availableCutoffs) {
                for (var team : cutoff.teams()) {
                    rows.add(new RowContext(cutoff.cutoffAfterWeek(), teamCount, team));
                }
            }
            clusters.add(new Cluster(
                leagueSeason.leagueId(),
                leagueSeason.season(),
                leagueSeason.leagueId() + ":" + leagueSeason.season(),
                teamCount,
                availableCutoffs,
                List.copyOf(rows)));
        }
        clusters.sort(Comparator.comparing(Cluster::identity));
        return List.copyOf(clusters);
    }

    private static List<FrequencyCandidate> generateFrequencyCandidates(List<Cluster> development) {
        Set<FrequencyCandidate> candidates = new TreeSet<>(FREQUENCY_ORDER);
        for (Cluster cluster : development) {
            for (RowContext row : cluster.rows()) {
                var team = row.team();
                candidates.add(FrequencyCandidate.of(
                    team.baselineRankChangedScenarios(),
                    team.baselineCommonWeekCount()));
            }
        }
        return List.copyOf(candidates);
    }

    private static List<MagnitudeCandidate> generateMagnitudeCandidates(List<Cluster> development) {
        Set<Integer> values = new TreeSet<>();
        for (Cluster cluster : development) {
            for (RowContext row : cluster.rows()) {
                values.add(row.team().baselineMaximumAbsoluteRankMovement());
            }
        }
        return values.stream().map(MagnitudeCandidate::new).toList();
    }

    private static FrequencyFoldEvaluation evaluateFrequency(Cluster heldOut, FrequencyCandidate candidate) {
        List<RowContext> meets = new ArrayList<>();
        List<RowContext> doesNotMeet = new ArrayList<>();
        for (RowContext row : heldOut.rows()) {
            var team = row.team();
            long left = (long) team.baselineRankChangedScenarios() * candidate.denominator();
            long right = (long) candidate.numerator() * team.baselineCommonWeekCount();
            if (left <= right) meets.add(row);
            else doesNotMeet.add(row);
        }
        CandidateFoldState state = meets.isEmpty() || doesNotMeet.isEmpty()
            ? CandidateFoldState.UNEVALUABLE_NO_HELD_OUT_SPLIT
            : CandidateFoldState.EVALUABLE;
        return new FrequencyFoldEvaluation(candidate, state, summarizeSide(meets), summarizeSide(doesNotMeet));
    }

    private static MagnitudeFoldEvaluation evaluateMagnitude(Cluster heldOut, MagnitudeCandidate candidate) {
        List<RowContext> meets = new ArrayList<>();
        List<RowContext> doesNotMeet = new ArrayList<>();
        for (RowContext row : heldOut.rows()) {
            if (row.team().baselineMaximumAbsoluteRankMovement() <= candidate.maximumMovementCutoff()) meets.add(row);
            else doesNotMeet.add(row);
        }
        CandidateFoldState state = meets.isEmpty() || doesNotMeet.isEmpty()
            ? CandidateFoldState.UNEVALUABLE_NO_HELD_OUT_SPLIT
            : CandidateFoldState.EVALUABLE;
        return new MagnitudeFoldEvaluation(candidate, state, summarizeSide(meets), summarizeSide(doesNotMeet));
    }

    private static SideSummary summarizeSide(List<RowContext> rows) {
        int retained = 0;
        int moved = 0;
        Map<Integer, Integer> absoluteDisplacement = new TreeMap<>();
        Map<Integer, Integer> signedDisplacement = new TreeMap<>();
        Map<LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass, Integer>
            sensitivityClasses = new EnumMap<>(
                LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass.class);
        Map<Integer, Integer> changedNumerators = new TreeMap<>();
        Map<Integer, Integer> denominators = new TreeMap<>();
        Map<Integer, Integer> teamCounts = new TreeMap<>();
        Set<Integer> cutoffWeeks = new TreeSet<>();

        for (RowContext row : rows) {
            var team = row.team();
            if (team.exactNumericRankRetained()) retained++;
            else moved++;
            absoluteDisplacement.merge(team.absoluteTemporalRankDisplacement(), 1, Integer::sum);
            signedDisplacement.merge(team.signedTemporalRankDisplacement(), 1, Integer::sum);
            sensitivityClasses.merge(team.baselineSensitivityClass(), 1, Integer::sum);
            changedNumerators.merge(team.baselineRankChangedScenarios(), 1, Integer::sum);
            denominators.merge(team.baselineCommonWeekCount(), 1, Integer::sum);
            teamCounts.merge(row.repositoryTeamCount(), 1, Integer::sum);
            cutoffWeeks.add(row.cutoffAfterWeek());
        }

        return new SideSummary(
            rows.size(),
            retained,
            moved,
            List.copyOf(cutoffWeeks),
            absoluteDisplacement,
            signedDisplacement,
            sensitivityClasses,
            changedNumerators,
            denominators,
            teamCounts);
    }

    private static FrequencyCandidateSummary summarizeFrequency(
        FrequencyCandidate candidate,
        List<FoldEvaluation> folds) {
        List<FrequencyCandidateFoldOutcome> outcomes = new ArrayList<>();
        for (FoldEvaluation fold : folds) {
            FrequencyFoldEvaluation evaluation = fold.frequencyEvaluations().stream()
                .filter(item -> item.candidate().equals(candidate))
                .findFirst().orElse(null);
            if (evaluation == null) {
                outcomes.add(new FrequencyCandidateFoldOutcome(
                    fold.heldOutLeagueSeason(), CandidateFoldState.NOT_GENERATED_IN_DEVELOPMENT_FOLD,
                    emptySide(), emptySide()));
            } else {
                outcomes.add(new FrequencyCandidateFoldOutcome(
                    fold.heldOutLeagueSeason(), evaluation.state(),
                    evaluation.meetsRule(), evaluation.doesNotMeetRule()));
            }
        }
        return new FrequencyCandidateSummary(candidate, List.copyOf(outcomes));
    }

    private static MagnitudeCandidateSummary summarizeMagnitude(
        MagnitudeCandidate candidate,
        List<FoldEvaluation> folds) {
        List<MagnitudeCandidateFoldOutcome> outcomes = new ArrayList<>();
        for (FoldEvaluation fold : folds) {
            MagnitudeFoldEvaluation evaluation = fold.magnitudeEvaluations().stream()
                .filter(item -> item.candidate().equals(candidate))
                .findFirst().orElse(null);
            if (evaluation == null) {
                outcomes.add(new MagnitudeCandidateFoldOutcome(
                    fold.heldOutLeagueSeason(), CandidateFoldState.NOT_GENERATED_IN_DEVELOPMENT_FOLD,
                    emptySide(), emptySide()));
            } else {
                outcomes.add(new MagnitudeCandidateFoldOutcome(
                    fold.heldOutLeagueSeason(), evaluation.state(),
                    evaluation.meetsRule(), evaluation.doesNotMeetRule()));
            }
        }
        return new MagnitudeCandidateSummary(candidate, List.copyOf(outcomes));
    }

    private static SideSummary emptySide() {
        return new SideSummary(0, 0, 0, List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    public enum StudyState {
        AVAILABLE,
        UNAVAILABLE_CORPUS_NOT_STRUCTURALLY_READY,
        UNAVAILABLE_INSUFFICIENT_EVALUATION_CLUSTERS
    }

    public enum CandidateFoldState {
        EVALUABLE,
        UNEVALUABLE_NO_HELD_OUT_SPLIT,
        NOT_GENERATED_IN_DEVELOPMENT_FOLD
    }

    public record FrequencyCandidate(int numerator, int denominator) {
        public FrequencyCandidate {
            if (numerator < 0 || denominator <= 0 || numerator > denominator) {
                throw new IllegalArgumentException("frequency candidate must satisfy 0 <= numerator <= denominator");
            }
            if (gcd(numerator, denominator) != 1) {
                throw new IllegalArgumentException("frequency candidate must use canonical reduced rational form");
            }
        }

        static FrequencyCandidate of(int numerator, int denominator) {
            if (numerator < 0 || denominator <= 0 || numerator > denominator) {
                throw new IllegalArgumentException("frequency candidate source ratio is invalid");
            }
            int gcd = gcd(numerator, denominator);
            return new FrequencyCandidate(numerator / gcd, denominator / gcd);
        }

        public BigDecimal displayValue() {
            return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator),
                    LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_SCALE,
                    LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_ROUNDING);
        }
    }

    public record MagnitudeCandidate(int maximumMovementCutoff) {
        public MagnitudeCandidate {
            if (maximumMovementCutoff < 0) {
                throw new IllegalArgumentException("magnitude candidate cutoff must not be negative");
            }
        }
    }

    public record SideSummary(
        int rows,
        int exactRankRetainedRows,
        int temporalRankMovedRows,
        List<Integer> cutoffAfterWeeks,
        Map<Integer, Integer> absoluteTemporalRankDisplacementDistribution,
        Map<Integer, Integer> signedTemporalRankDisplacementDistribution,
        Map<LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass, Integer>
            baselineSensitivityClassCounts,
        Map<Integer, Integer> changedScenarioNumeratorDistribution,
        Map<Integer, Integer> perturbationDenominatorDistribution,
        Map<Integer, Integer> repositoryTeamCountDistribution) {

        public SideSummary {
            if (rows < 0 || exactRankRetainedRows < 0 || temporalRankMovedRows < 0
                || exactRankRetainedRows + temporalRankMovedRows != rows) {
                throw new IllegalArgumentException("candidate side counts are inconsistent");
            }
            cutoffAfterWeeks = List.copyOf(Objects.requireNonNull(cutoffAfterWeeks, "cutoffAfterWeeks must not be null"));
            absoluteTemporalRankDisplacementDistribution = Map.copyOf(Objects.requireNonNull(
                absoluteTemporalRankDisplacementDistribution, "absoluteTemporalRankDisplacementDistribution must not be null"));
            signedTemporalRankDisplacementDistribution = Map.copyOf(Objects.requireNonNull(
                signedTemporalRankDisplacementDistribution, "signedTemporalRankDisplacementDistribution must not be null"));
            baselineSensitivityClassCounts = Map.copyOf(Objects.requireNonNull(
                baselineSensitivityClassCounts, "baselineSensitivityClassCounts must not be null"));
            changedScenarioNumeratorDistribution = Map.copyOf(Objects.requireNonNull(
                changedScenarioNumeratorDistribution, "changedScenarioNumeratorDistribution must not be null"));
            perturbationDenominatorDistribution = Map.copyOf(Objects.requireNonNull(
                perturbationDenominatorDistribution, "perturbationDenominatorDistribution must not be null"));
            repositoryTeamCountDistribution = Map.copyOf(Objects.requireNonNull(
                repositoryTeamCountDistribution, "repositoryTeamCountDistribution must not be null"));
        }
    }

    public record FrequencyFoldEvaluation(
        FrequencyCandidate candidate,
        CandidateFoldState state,
        SideSummary meetsRule,
        SideSummary doesNotMeetRule) {
        public FrequencyFoldEvaluation {
            Objects.requireNonNull(candidate, "candidate must not be null");
            validateEvaluation(state, meetsRule, doesNotMeetRule);
        }
    }

    public record MagnitudeFoldEvaluation(
        MagnitudeCandidate candidate,
        CandidateFoldState state,
        SideSummary meetsRule,
        SideSummary doesNotMeetRule) {
        public MagnitudeFoldEvaluation {
            Objects.requireNonNull(candidate, "candidate must not be null");
            validateEvaluation(state, meetsRule, doesNotMeetRule);
        }
    }

    public record FoldEvaluation(
        String heldOutLeagueId,
        int heldOutSeason,
        String heldOutLeagueSeason,
        int developmentClusterCount,
        int repositoryTeamCount,
        int heldOutAvailableCutoffs,
        int heldOutTeamCutoffRows,
        List<FrequencyFoldEvaluation> frequencyEvaluations,
        List<MagnitudeFoldEvaluation> magnitudeEvaluations) {
        public FoldEvaluation {
            heldOutLeagueId = requireText(heldOutLeagueId, "heldOutLeagueId");
            heldOutLeagueSeason = requireText(heldOutLeagueSeason, "heldOutLeagueSeason");
            if (heldOutSeason < 1999 || heldOutSeason > 2100 || developmentClusterCount < 1
                || repositoryTeamCount < 2 || heldOutAvailableCutoffs < 1 || heldOutTeamCutoffRows < 1) {
                throw new IllegalArgumentException("candidate fold counts are invalid");
            }
            if (!heldOutLeagueSeason.equals(heldOutLeagueId + ":" + heldOutSeason)) {
                throw new IllegalArgumentException("held-out league-season identity is inconsistent");
            }
            frequencyEvaluations = List.copyOf(Objects.requireNonNull(
                frequencyEvaluations, "frequencyEvaluations must not be null"));
            magnitudeEvaluations = List.copyOf(Objects.requireNonNull(
                magnitudeEvaluations, "magnitudeEvaluations must not be null"));
        }
    }

    public record FrequencyCandidateFoldOutcome(
        String heldOutLeagueSeason,
        CandidateFoldState state,
        SideSummary meetsRule,
        SideSummary doesNotMeetRule) {
        public FrequencyCandidateFoldOutcome {
            heldOutLeagueSeason = requireText(heldOutLeagueSeason, "heldOutLeagueSeason");
            validateOutcome(state, meetsRule, doesNotMeetRule);
        }
    }

    public record MagnitudeCandidateFoldOutcome(
        String heldOutLeagueSeason,
        CandidateFoldState state,
        SideSummary meetsRule,
        SideSummary doesNotMeetRule) {
        public MagnitudeCandidateFoldOutcome {
            heldOutLeagueSeason = requireText(heldOutLeagueSeason, "heldOutLeagueSeason");
            validateOutcome(state, meetsRule, doesNotMeetRule);
        }
    }

    public record FrequencyCandidateSummary(
        FrequencyCandidate candidate,
        List<FrequencyCandidateFoldOutcome> folds) {
        public FrequencyCandidateSummary {
            Objects.requireNonNull(candidate, "candidate must not be null");
            folds = List.copyOf(Objects.requireNonNull(folds, "folds must not be null"));
        }
    }

    public record MagnitudeCandidateSummary(
        MagnitudeCandidate candidate,
        List<MagnitudeCandidateFoldOutcome> folds) {
        public MagnitudeCandidateSummary {
            Objects.requireNonNull(candidate, "candidate must not be null");
            folds = List.copyOf(Objects.requireNonNull(folds, "folds must not be null"));
        }
    }

    public record CandidateThresholdStudyReport(
        String policyId,
        String metricScope,
        String studyPolicy,
        LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer.CorpusReadinessReport sourceReadiness,
        StudyState studyState,
        List<FoldEvaluation> folds,
        List<FrequencyCandidateSummary> frequencyCandidates,
        List<MagnitudeCandidateSummary> magnitudeCandidates) {

        public CandidateThresholdStudyReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            if (!STUDY_POLICY.equals(studyPolicy)) throw new IllegalArgumentException("unexpected studyPolicy");
            Objects.requireNonNull(sourceReadiness, "sourceReadiness must not be null");
            Objects.requireNonNull(studyState, "studyState must not be null");
            folds = List.copyOf(Objects.requireNonNull(folds, "folds must not be null"));
            frequencyCandidates = List.copyOf(Objects.requireNonNull(
                frequencyCandidates, "frequencyCandidates must not be null"));
            magnitudeCandidates = List.copyOf(Objects.requireNonNull(
                magnitudeCandidates, "magnitudeCandidates must not be null"));

            Computed expected = compute(sourceReadiness);
            if (studyState != expected.state()
                || !folds.equals(expected.folds())
                || !frequencyCandidates.equals(expected.frequencyCandidates())
                || !magnitudeCandidates.equals(expected.magnitudeCandidates())) {
                throw new IllegalArgumentException("candidate threshold study fields must match governed BF-522/BF-518 source evidence");
            }
        }
    }

    private static void validateEvaluation(
        CandidateFoldState state,
        SideSummary meets,
        SideSummary doesNotMeet) {
        Objects.requireNonNull(state, "candidate fold state must not be null");
        Objects.requireNonNull(meets, "meetsRule must not be null");
        Objects.requireNonNull(doesNotMeet, "doesNotMeetRule must not be null");
        if (state == CandidateFoldState.NOT_GENERATED_IN_DEVELOPMENT_FOLD) {
            throw new IllegalArgumentException("generated fold evaluation cannot use NOT_GENERATED state");
        }
        boolean split = meets.rows() > 0 && doesNotMeet.rows() > 0;
        if ((state == CandidateFoldState.EVALUABLE) != split) {
            throw new IllegalArgumentException("candidate fold state must match held-out split availability");
        }
    }

    private static void validateOutcome(
        CandidateFoldState state,
        SideSummary meets,
        SideSummary doesNotMeet) {
        Objects.requireNonNull(state, "candidate fold state must not be null");
        Objects.requireNonNull(meets, "meetsRule must not be null");
        Objects.requireNonNull(doesNotMeet, "doesNotMeetRule must not be null");
        if (state == CandidateFoldState.NOT_GENERATED_IN_DEVELOPMENT_FOLD
            && (meets.rows() != 0 || doesNotMeet.rows() != 0)) {
            throw new IllegalArgumentException("not-generated fold cannot publish candidate side rows");
        }
        if (state == CandidateFoldState.EVALUABLE && (meets.rows() == 0 || doesNotMeet.rows() == 0)) {
            throw new IllegalArgumentException("evaluable candidate outcome requires both held-out sides");
        }
        if (state == CandidateFoldState.UNEVALUABLE_NO_HELD_OUT_SPLIT
            && meets.rows() > 0 && doesNotMeet.rows() > 0) {
            throw new IllegalArgumentException("unevaluable candidate outcome cannot contain a complete held-out split");
        }
    }

    private static int gcd(int a, int b) {
        a = Math.abs(a);
        b = Math.abs(b);
        if (a == 0) return b == 0 ? 1 : b;
        while (b != 0) {
            int next = a % b;
            a = b;
            b = next;
        }
        return a;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static final Comparator<FrequencyCandidate> FREQUENCY_ORDER = (a, b) -> {
        long left = (long) a.numerator() * b.denominator();
        long right = (long) b.numerator() * a.denominator();
        int byValue = Long.compare(left, right);
        if (byValue != 0) return byValue;
        int byNumerator = Integer.compare(a.numerator(), b.numerator());
        return byNumerator != 0 ? byNumerator : Integer.compare(a.denominator(), b.denominator());
    };

    private record RowContext(
        int cutoffAfterWeek,
        int repositoryTeamCount,
        LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CalibrationTeamRow team) {}

    private record Cluster(
        String leagueId,
        int season,
        String identity,
        int repositoryTeamCount,
        List<LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffAudit> availableCutoffs,
        List<RowContext> rows) {}

    private record Computed(
        StudyState state,
        List<FoldEvaluation> folds,
        List<FrequencyCandidateSummary> frequencyCandidates,
        List<MagnitudeCandidateSummary> magnitudeCandidates) {}
}
