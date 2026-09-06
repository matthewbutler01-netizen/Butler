package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Performs the BF-529 cluster-aware support and raw displacement-direction audit over BF-526.
 * This analyzer is descriptive only: it does not score, rank, select, fit, or deploy candidates.
 */
public final class LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer {
    public static final String POLICY_ID =
        "league-lineup-capture-ranking-sensitivity-candidate-cross-fold-support-v1-cluster-aware-breadth-and-direction-audit-no-selection-no-score-no-confidence";
    public static final String METRIC_SCOPE =
        "STRUCTURAL_CANDIDATE_SUPPORT_AND_RAW_HELD_OUT_DISPLACEMENT_DIRECTION_ACROSS_GOVERNED_LEAGUE_SEASON_FOLDS_NO_SELECTION_NO_SCORING_NO_OPTIMIZATION_NO_FITTING_NO_DEPLOYMENT";
    public static final String AUDIT_POLICY =
        "BF_526_CANDIDATE_IDENTITIES_ONLY_CLUSTER_COUNTS_NOT_TEAM_CUTOFF_SAMPLE_N_RAW_TOTAL_ABSOLUTE_DISPLACEMENT_DIRECTION_WITH_SIDE_ROW_COUNTS_AND_DISTRIBUTIONS";

    private final Database database;

    public LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public CandidateCrossFoldSupportAuditReport analyze(int startSeason, int endSeason) throws SQLException {
        var source = new LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer(database)
            .analyze(startSeason, endSeason);
        return fromSource(source);
    }

    static CandidateCrossFoldSupportAuditReport fromSource(
        LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateThresholdStudyReport source) {
        Objects.requireNonNull(source, "candidate threshold study source must not be null");
        Computed computed = compute(source);
        return new CandidateCrossFoldSupportAuditReport(
            POLICY_ID, METRIC_SCOPE, AUDIT_POLICY, source,
            computed.state(), computed.frequencyCandidates(), computed.magnitudeCandidates());
    }

    private static Computed compute(
        LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateThresholdStudyReport source) {
        if (source.studyState()
            != LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.StudyState.AVAILABLE) {
            return new Computed(ReportState.UNAVAILABLE_CANDIDATE_STUDY, List.of(), List.of());
        }

        Map<String, LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FoldEvaluation> foldsByIdentity =
            new LinkedHashMap<>();
        for (var fold : source.folds()) {
            if (foldsByIdentity.put(fold.heldOutLeagueSeason(), fold) != null) {
                throw new IllegalArgumentException("BF-526 source contains duplicate held-out league-season identity");
            }
        }

        List<FrequencyCandidateAudit> frequency = source.frequencyCandidates().stream()
            .map(candidate -> auditFrequency(candidate, source.folds().size(), foldsByIdentity))
            .toList();
        List<MagnitudeCandidateAudit> magnitude = source.magnitudeCandidates().stream()
            .map(candidate -> auditMagnitude(candidate, source.folds().size(), foldsByIdentity))
            .toList();
        return new Computed(ReportState.AVAILABLE, frequency, magnitude);
    }

    private static FrequencyCandidateAudit auditFrequency(
        LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FrequencyCandidateSummary candidate,
        int totalFolds,
        Map<String, LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FoldEvaluation> foldsByIdentity) {
        CandidateCounts counts = counts(candidate.folds().stream()
            .map(LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FrequencyCandidateFoldOutcome::state)
            .toList(), totalFolds);
        Breadth breadth = frequencyBreadth(candidate.folds(), foldsByIdentity);
        List<FoldDirectionAudit> directions = new ArrayList<>();
        for (var outcome : candidate.folds()) {
            if (outcome.state()
                == LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateFoldState.EVALUABLE) {
                directions.add(directionAudit(
                    requireFold(foldsByIdentity, outcome.heldOutLeagueSeason()),
                    outcome.meetsRule(), outcome.doesNotMeetRule()));
            }
        }
        SupportState state = supportState(
            counts.evaluableFolds(), breadth.heldOutLeagueSeasons().size(), breadth.repositoryTeamCountStrata().size(),
            breadth.sharedSidePerturbationDenominators().size(), true);
        return new FrequencyCandidateAudit(
            candidate.candidate(), counts,
            breadth.heldOutLeagueIds(), breadth.heldOutSeasons(), breadth.heldOutLeagueSeasons(),
            breadth.repositoryTeamCountStrata(), breadth.sharedSidePerturbationDenominators(),
            state, List.copyOf(directions), directionCounts(directions));
    }

    private static MagnitudeCandidateAudit auditMagnitude(
        LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.MagnitudeCandidateSummary candidate,
        int totalFolds,
        Map<String, LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FoldEvaluation> foldsByIdentity) {
        CandidateCounts counts = counts(candidate.folds().stream()
            .map(LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.MagnitudeCandidateFoldOutcome::state)
            .toList(), totalFolds);
        Breadth breadth = magnitudeBreadth(candidate.folds(), foldsByIdentity);
        List<FoldDirectionAudit> directions = new ArrayList<>();
        for (var outcome : candidate.folds()) {
            if (outcome.state()
                == LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateFoldState.EVALUABLE) {
                directions.add(directionAudit(
                    requireFold(foldsByIdentity, outcome.heldOutLeagueSeason()),
                    outcome.meetsRule(), outcome.doesNotMeetRule()));
            }
        }
        SupportState state = supportState(
            counts.evaluableFolds(), breadth.heldOutLeagueSeasons().size(), breadth.repositoryTeamCountStrata().size(),
            0, false);
        return new MagnitudeCandidateAudit(
            candidate.candidate(), counts,
            breadth.heldOutLeagueIds(), breadth.heldOutSeasons(), breadth.heldOutLeagueSeasons(),
            breadth.repositoryTeamCountStrata(), state, List.copyOf(directions), directionCounts(directions));
    }

    private static Breadth frequencyBreadth(
        List<LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FrequencyCandidateFoldOutcome> outcomes,
        Map<String, LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FoldEvaluation> foldsByIdentity) {
        Set<String> leagueIds = new TreeSet<>();
        Set<Integer> seasons = new TreeSet<>();
        Set<String> leagueSeasons = new TreeSet<>();
        Set<Integer> teamCounts = new TreeSet<>();
        Set<Integer> meetsDenominators = new TreeSet<>();
        Set<Integer> doesNotMeetDenominators = new TreeSet<>();
        for (var outcome : outcomes) {
            if (outcome.state()
                != LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateFoldState.EVALUABLE) continue;
            var fold = requireFold(foldsByIdentity, outcome.heldOutLeagueSeason());
            addBreadth(fold, leagueIds, seasons, leagueSeasons, teamCounts);
            meetsDenominators.addAll(outcome.meetsRule().perturbationDenominatorDistribution().keySet());
            doesNotMeetDenominators.addAll(outcome.doesNotMeetRule().perturbationDenominatorDistribution().keySet());
        }
        Set<Integer> shared = new TreeSet<>(meetsDenominators);
        shared.retainAll(doesNotMeetDenominators);
        return breadth(leagueIds, seasons, leagueSeasons, teamCounts, shared);
    }

    private static Breadth magnitudeBreadth(
        List<LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.MagnitudeCandidateFoldOutcome> outcomes,
        Map<String, LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FoldEvaluation> foldsByIdentity) {
        Set<String> leagueIds = new TreeSet<>();
        Set<Integer> seasons = new TreeSet<>();
        Set<String> leagueSeasons = new TreeSet<>();
        Set<Integer> teamCounts = new TreeSet<>();
        for (var outcome : outcomes) {
            if (outcome.state()
                != LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateFoldState.EVALUABLE) continue;
            addBreadth(requireFold(foldsByIdentity, outcome.heldOutLeagueSeason()),
                leagueIds, seasons, leagueSeasons, teamCounts);
        }
        return breadth(leagueIds, seasons, leagueSeasons, teamCounts, Set.of());
    }

    private static void addBreadth(
        LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FoldEvaluation fold,
        Set<String> leagueIds,
        Set<Integer> seasons,
        Set<String> leagueSeasons,
        Set<Integer> teamCounts) {
        leagueIds.add(fold.heldOutLeagueId());
        seasons.add(fold.heldOutSeason());
        leagueSeasons.add(fold.heldOutLeagueSeason());
        teamCounts.add(fold.repositoryTeamCount());
    }

    private static Breadth breadth(
        Set<String> leagueIds,
        Set<Integer> seasons,
        Set<String> leagueSeasons,
        Set<Integer> teamCounts,
        Set<Integer> sharedDenominators) {
        return new Breadth(
            List.copyOf(leagueIds), List.copyOf(seasons), List.copyOf(leagueSeasons),
            List.copyOf(teamCounts), List.copyOf(sharedDenominators));
    }

    private static LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FoldEvaluation requireFold(
        Map<String, LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FoldEvaluation> foldsByIdentity,
        String identity) {
        var fold = foldsByIdentity.get(identity);
        if (fold == null) {
            throw new IllegalArgumentException("candidate outcome references unknown BF-526 held-out fold: " + identity);
        }
        return fold;
    }

    private static CandidateCounts counts(
        List<LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateFoldState> states,
        int totalFolds) {
        int generated = 0;
        int notGenerated = 0;
        int evaluable = 0;
        int unevaluable = 0;
        for (var state : states) {
            switch (state) {
                case EVALUABLE -> { generated++; evaluable++; }
                case UNEVALUABLE_NO_HELD_OUT_SPLIT -> { generated++; unevaluable++; }
                case NOT_GENERATED_IN_DEVELOPMENT_FOLD -> notGenerated++;
            }
        }
        return new CandidateCounts(totalFolds, generated, notGenerated, evaluable, unevaluable);
    }

    static SupportState supportState(
        int evaluableFolds,
        int evaluableLeagueSeasonIdentities,
        int repositoryTeamCountStrata,
        int sharedSidePerturbationDenominators,
        boolean frequencyCandidate) {
        if (evaluableFolds < 0 || evaluableLeagueSeasonIdentities < 0 || repositoryTeamCountStrata < 0
            || sharedSidePerturbationDenominators < 0) {
            throw new IllegalArgumentException("support breadth counts must not be negative");
        }
        if (evaluableFolds == 0) return SupportState.NO_EVALUABLE_FOLDS;
        if (evaluableFolds == 1) return SupportState.SINGLE_EVALUABLE_FOLD;
        boolean diverse = evaluableLeagueSeasonIdentities >= 2
            && repositoryTeamCountStrata >= 2
            && (!frequencyCandidate || sharedSidePerturbationDenominators >= 2);
        return diverse ? SupportState.MULTI_FOLD_DIVERSE_SUPPORT : SupportState.MULTI_FOLD_NARROW_SUPPORT;
    }

    private static FoldDirectionAudit directionAudit(
        LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FoldEvaluation fold,
        LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.SideSummary meets,
        LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.SideSummary doesNotMeet) {
        if (meets.rows() == 0 || doesNotMeet.rows() == 0) {
            throw new IllegalArgumentException("direction audit requires an evaluable BF-526 held-out split");
        }
        long meetsTotal = totalAbsoluteDisplacement(meets.absoluteTemporalRankDisplacementDistribution());
        long doesNotMeetTotal = totalAbsoluteDisplacement(doesNotMeet.absoluteTemporalRankDisplacementDistribution());
        DirectionState state = directionState(meetsTotal, doesNotMeetTotal);
        return new FoldDirectionAudit(
            fold.heldOutLeagueId(), fold.heldOutSeason(), fold.heldOutLeagueSeason(), fold.repositoryTeamCount(),
            state, meetsTotal, doesNotMeetTotal, meets, doesNotMeet);
    }

    private static DirectionState directionState(long meetsTotal, long doesNotMeetTotal) {
        if (meetsTotal < doesNotMeetTotal) return DirectionState.MEETS_SIDE_LOWER_TOTAL_ABSOLUTE_DISPLACEMENT;
        if (meetsTotal > doesNotMeetTotal) return DirectionState.MEETS_SIDE_HIGHER_TOTAL_ABSOLUTE_DISPLACEMENT;
        return DirectionState.EQUAL_TOTAL_ABSOLUTE_DISPLACEMENT;
    }

    static long totalAbsoluteDisplacement(Map<Integer, Integer> distribution) {
        Objects.requireNonNull(distribution, "absolute displacement distribution must not be null");
        long total = 0L;
        for (var entry : distribution.entrySet()) {
            if (entry.getKey() < 0 || entry.getValue() < 0) {
                throw new IllegalArgumentException("absolute displacement distribution must not contain negative values");
            }
            total = Math.addExact(total, Math.multiplyExact((long) entry.getKey(), entry.getValue()));
        }
        return total;
    }

    private static Map<DirectionState, Integer> directionCounts(List<FoldDirectionAudit> directions) {
        Map<DirectionState, Integer> counts = new EnumMap<>(DirectionState.class);
        for (var direction : directions) counts.merge(direction.directionState(), 1, Integer::sum);
        return Map.copyOf(counts);
    }

    public enum ReportState { AVAILABLE, UNAVAILABLE_CANDIDATE_STUDY }

    public enum SupportState {
        NO_EVALUABLE_FOLDS,
        SINGLE_EVALUABLE_FOLD,
        MULTI_FOLD_NARROW_SUPPORT,
        MULTI_FOLD_DIVERSE_SUPPORT
    }

    public enum DirectionState {
        MEETS_SIDE_LOWER_TOTAL_ABSOLUTE_DISPLACEMENT,
        MEETS_SIDE_HIGHER_TOTAL_ABSOLUTE_DISPLACEMENT,
        EQUAL_TOTAL_ABSOLUTE_DISPLACEMENT
    }

    public record CandidateCounts(
        int totalFolds,
        int generatedFolds,
        int notGeneratedFolds,
        int evaluableFolds,
        int unevaluableNoHeldOutSplitFolds) {
        public CandidateCounts {
            if (totalFolds < 0 || generatedFolds < 0 || notGeneratedFolds < 0 || evaluableFolds < 0
                || unevaluableNoHeldOutSplitFolds < 0
                || generatedFolds + notGeneratedFolds != totalFolds
                || evaluableFolds + unevaluableNoHeldOutSplitFolds != generatedFolds) {
                throw new IllegalArgumentException("candidate fold support counts are inconsistent");
            }
        }
    }

    public record FoldDirectionAudit(
        String heldOutLeagueId,
        int heldOutSeason,
        String heldOutLeagueSeason,
        int repositoryTeamCount,
        DirectionState directionState,
        long meetsRuleTotalAbsoluteTemporalRankDisplacement,
        long doesNotMeetRuleTotalAbsoluteTemporalRankDisplacement,
        LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.SideSummary meetsRule,
        LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.SideSummary doesNotMeetRule) {
        public FoldDirectionAudit {
            heldOutLeagueId = requireText(heldOutLeagueId, "heldOutLeagueId");
            heldOutLeagueSeason = requireText(heldOutLeagueSeason, "heldOutLeagueSeason");
            if (heldOutSeason < 1999 || heldOutSeason > 2100 || repositoryTeamCount < 2) {
                throw new IllegalArgumentException("fold direction identity fields are invalid");
            }
            if (!heldOutLeagueSeason.equals(heldOutLeagueId + ":" + heldOutSeason)) {
                throw new IllegalArgumentException("fold direction league-season identity is inconsistent");
            }
            Objects.requireNonNull(directionState, "directionState must not be null");
            Objects.requireNonNull(meetsRule, "meetsRule must not be null");
            Objects.requireNonNull(doesNotMeetRule, "doesNotMeetRule must not be null");
            if (meetsRule.rows() == 0 || doesNotMeetRule.rows() == 0) {
                throw new IllegalArgumentException("fold direction requires rows on both candidate sides");
            }
            long expectedMeets = totalAbsoluteDisplacement(meetsRule.absoluteTemporalRankDisplacementDistribution());
            long expectedDoesNotMeet = totalAbsoluteDisplacement(doesNotMeetRule.absoluteTemporalRankDisplacementDistribution());
            if (meetsRuleTotalAbsoluteTemporalRankDisplacement != expectedMeets
                || doesNotMeetRuleTotalAbsoluteTemporalRankDisplacement != expectedDoesNotMeet) {
                throw new IllegalArgumentException("fold direction totals must match raw BF-526 side distributions");
            }
            if (directionState != directionState(expectedMeets, expectedDoesNotMeet)) {
                throw new IllegalArgumentException("fold direction state must match raw BF-526 side totals");
            }
        }
    }

    public record FrequencyCandidateAudit(
        LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.FrequencyCandidate candidate,
        CandidateCounts counts,
        List<String> evaluableHeldOutLeagueIds,
        List<Integer> evaluableHeldOutSeasons,
        List<String> evaluableHeldOutLeagueSeasons,
        List<Integer> repositoryTeamCountStrata,
        List<Integer> perturbationDenominatorsRepresentedOnBothSides,
        SupportState supportState,
        List<FoldDirectionAudit> foldDirections,
        Map<DirectionState, Integer> directionCounts) {
        public FrequencyCandidateAudit {
            Objects.requireNonNull(candidate, "candidate must not be null");
            Objects.requireNonNull(counts, "counts must not be null");
            evaluableHeldOutLeagueIds = immutable(evaluableHeldOutLeagueIds, "evaluableHeldOutLeagueIds");
            evaluableHeldOutSeasons = immutable(evaluableHeldOutSeasons, "evaluableHeldOutSeasons");
            evaluableHeldOutLeagueSeasons = immutable(evaluableHeldOutLeagueSeasons, "evaluableHeldOutLeagueSeasons");
            repositoryTeamCountStrata = immutable(repositoryTeamCountStrata, "repositoryTeamCountStrata");
            perturbationDenominatorsRepresentedOnBothSides = immutable(
                perturbationDenominatorsRepresentedOnBothSides, "perturbationDenominatorsRepresentedOnBothSides");
            Objects.requireNonNull(supportState, "supportState must not be null");
            foldDirections = immutable(foldDirections, "foldDirections");
            directionCounts = Map.copyOf(Objects.requireNonNull(directionCounts, "directionCounts must not be null"));
            if (foldDirections.size() != counts.evaluableFolds()) {
                throw new IllegalArgumentException("frequency direction rows must equal evaluable fold count");
            }
            SupportState expected = LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer.supportState(
                counts.evaluableFolds(), evaluableHeldOutLeagueSeasons.size(), repositoryTeamCountStrata.size(),
                perturbationDenominatorsRepresentedOnBothSides.size(), true);
            if (supportState != expected) throw new IllegalArgumentException("frequency support state is inconsistent");
            validateDirectionCounts(foldDirections, directionCounts);
        }
    }

    public record MagnitudeCandidateAudit(
        LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.MagnitudeCandidate candidate,
        CandidateCounts counts,
        List<String> evaluableHeldOutLeagueIds,
        List<Integer> evaluableHeldOutSeasons,
        List<String> evaluableHeldOutLeagueSeasons,
        List<Integer> repositoryTeamCountStrata,
        SupportState supportState,
        List<FoldDirectionAudit> foldDirections,
        Map<DirectionState, Integer> directionCounts) {
        public MagnitudeCandidateAudit {
            Objects.requireNonNull(candidate, "candidate must not be null");
            Objects.requireNonNull(counts, "counts must not be null");
            evaluableHeldOutLeagueIds = immutable(evaluableHeldOutLeagueIds, "evaluableHeldOutLeagueIds");
            evaluableHeldOutSeasons = immutable(evaluableHeldOutSeasons, "evaluableHeldOutSeasons");
            evaluableHeldOutLeagueSeasons = immutable(evaluableHeldOutLeagueSeasons, "evaluableHeldOutLeagueSeasons");
            repositoryTeamCountStrata = immutable(repositoryTeamCountStrata, "repositoryTeamCountStrata");
            Objects.requireNonNull(supportState, "supportState must not be null");
            foldDirections = immutable(foldDirections, "foldDirections");
            directionCounts = Map.copyOf(Objects.requireNonNull(directionCounts, "directionCounts must not be null"));
            if (foldDirections.size() != counts.evaluableFolds()) {
                throw new IllegalArgumentException("magnitude direction rows must equal evaluable fold count");
            }
            SupportState expected = LeagueLineupCaptureRankingSensitivityCandidateCrossFoldSupportAuditAnalyzer.supportState(
                counts.evaluableFolds(), evaluableHeldOutLeagueSeasons.size(), repositoryTeamCountStrata.size(), 0, false);
            if (supportState != expected) throw new IllegalArgumentException("magnitude support state is inconsistent");
            validateDirectionCounts(foldDirections, directionCounts);
        }
    }

    public record CandidateCrossFoldSupportAuditReport(
        String policyId,
        String metricScope,
        String auditPolicy,
        LeagueLineupCaptureRankingSensitivityCandidateThresholdStudyAnalyzer.CandidateThresholdStudyReport sourceCandidateStudy,
        ReportState reportState,
        List<FrequencyCandidateAudit> frequencyCandidates,
        List<MagnitudeCandidateAudit> magnitudeCandidates) {
        public CandidateCrossFoldSupportAuditReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            if (!AUDIT_POLICY.equals(auditPolicy)) throw new IllegalArgumentException("unexpected auditPolicy");
            Objects.requireNonNull(sourceCandidateStudy, "sourceCandidateStudy must not be null");
            Objects.requireNonNull(reportState, "reportState must not be null");
            frequencyCandidates = immutable(frequencyCandidates, "frequencyCandidates");
            magnitudeCandidates = immutable(magnitudeCandidates, "magnitudeCandidates");
            Computed expected = compute(sourceCandidateStudy);
            if (reportState != expected.state()
                || !frequencyCandidates.equals(expected.frequencyCandidates())
                || !magnitudeCandidates.equals(expected.magnitudeCandidates())) {
                throw new IllegalArgumentException(
                    "candidate cross-fold support audit fields must match governed BF-526 source evidence");
            }
        }
    }

    private static void validateDirectionCounts(
        List<FoldDirectionAudit> foldDirections,
        Map<DirectionState, Integer> counts) {
        Map<DirectionState, Integer> expected = directionCounts(foldDirections);
        if (!counts.equals(expected)) {
            throw new IllegalArgumentException("direction counts must match evaluable fold direction rows");
        }
        if (counts.values().stream().mapToInt(Integer::intValue).sum() != foldDirections.size()) {
            throw new IllegalArgumentException("direction counts must preserve evaluable fold count");
        }
    }

    private static <T> List<T> immutable(List<T> values, String field) {
        return List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record Breadth(
        List<String> heldOutLeagueIds,
        List<Integer> heldOutSeasons,
        List<String> heldOutLeagueSeasons,
        List<Integer> repositoryTeamCountStrata,
        List<Integer> sharedSidePerturbationDenominators) {}

    private record Computed(
        ReportState state,
        List<FrequencyCandidateAudit> frequencyCandidates,
        List<MagnitudeCandidateAudit> magnitudeCandidates) {}
}