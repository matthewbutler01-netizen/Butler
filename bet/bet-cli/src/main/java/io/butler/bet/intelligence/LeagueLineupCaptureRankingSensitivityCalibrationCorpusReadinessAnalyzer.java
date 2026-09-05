package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Assesses structural variation in the governed BF-518 historical calibration corpus.
 * Passing these gates authorizes only a later threshold-study methodology design; it does not
 * establish statistical sufficiency, fit thresholds, estimate confidence, or evaluate managers.
 */
public final class LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer {
    public static final String POLICY_ID =
        "league-lineup-capture-ranking-sensitivity-calibration-corpus-structural-readiness-v1-multi-cluster-multi-season-multi-size-multi-denominator-outcome-variation-no-thresholds-no-confidence";
    public static final String METRIC_SCOPE =
        "STRUCTURAL_DIVERSITY_READINESS_OF_GOVERNED_BF518_HISTORICAL_CALIBRATION_CORPUS_NO_STATISTICAL_SUFFICIENCY_NO_THRESHOLDS_NO_CONFIDENCE_NO_MANAGER_ATTRIBUTION";
    public static final String READINESS_POLICY =
        "SIX_MINIMUM_VARIATION_GATES_OVER_AVAILABLE_BF518_CUTOFFS_AUTHORIZE_ONLY_LATER_THRESHOLD_STUDY_METHODOLOGY_DESIGN";

    private final Database database;

    public LeagueLineupCaptureRankingSensitivityCalibrationCorpusReadinessAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public CorpusReadinessReport analyze(int startSeason, int endSeason) throws SQLException {
        var source = new LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer(database)
            .analyze(startSeason, endSeason);
        return fromSource(source);
    }

    static CorpusReadinessReport fromSource(
        LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CorpusAuditReport source) {
        Objects.requireNonNull(source, "source corpus audit must not be null");
        Computed computed = compute(source);
        return new CorpusReadinessReport(
            POLICY_ID,
            METRIC_SCOPE,
            READINESS_POLICY,
            source,
            computed.state(),
            computed.gates(),
            computed.diagnostics());
    }

    private static Computed compute(
        LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CorpusAuditReport source) {
        Set<String> leagueIds = new TreeSet<>();
        Set<Integer> seasons = new TreeSet<>();
        Set<String> leagueSeasons = new TreeSet<>();
        Set<Integer> teamCounts = new TreeSet<>();
        Set<Integer> perturbationDenominators = new TreeSet<>();

        Map<String, Integer> availableCutoffsByLeagueId = new TreeMap<>();
        Map<Integer, Integer> availableCutoffsBySeason = new TreeMap<>();
        Map<String, Integer> availableCutoffsByLeagueSeason = new TreeMap<>();
        Map<Integer, Integer> availableCutoffsByTeamCount = new TreeMap<>();
        Map<Integer, Integer> availableCutoffsByPerturbationDenominator = new TreeMap<>();
        Map<Integer, Integer> changedScenarioNumeratorDistribution = new TreeMap<>();
        Map<BigDecimal, Integer> rankChangeFrequencyDistribution = new TreeMap<>();
        Map<LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass, Integer>
            sensitivityClassCounts = new EnumMap<>(
                LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass.class);

        int availableCutoffs = 0;
        int availableTeamCutoffRows = 0;
        int retainedRows = 0;
        int movedRows = 0;

        for (var leagueSeason : source.leagueSeasons()) {
            int teamCount = leagueSeason.sourceCommonUniverse().teams().size();
            String leagueSeasonId = leagueSeason.leagueId() + ":" + leagueSeason.season();
            boolean hasAvailableCutoff = false;

            for (var cutoff : leagueSeason.cutoffs()) {
                if (cutoff.state()
                    != LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffState.AVAILABLE) {
                    continue;
                }

                hasAvailableCutoff = true;
                availableCutoffs++;
                int denominator = cutoff.baselineCommonWeeks().size();
                perturbationDenominators.add(denominator);
                availableCutoffsByLeagueId.merge(leagueSeason.leagueId(), 1, Integer::sum);
                availableCutoffsBySeason.merge(leagueSeason.season(), 1, Integer::sum);
                availableCutoffsByLeagueSeason.merge(leagueSeasonId, 1, Integer::sum);
                availableCutoffsByTeamCount.merge(teamCount, 1, Integer::sum);
                availableCutoffsByPerturbationDenominator.merge(denominator, 1, Integer::sum);

                for (var team : cutoff.teams()) {
                    availableTeamCutoffRows++;
                    changedScenarioNumeratorDistribution.merge(
                        team.baselineRankChangedScenarios(), 1, Integer::sum);
                    rankChangeFrequencyDistribution.merge(
                        team.baselineRankChangeFrequency(), 1, Integer::sum);
                    sensitivityClassCounts.merge(team.baselineSensitivityClass(), 1, Integer::sum);
                    if (team.exactNumericRankRetained()) retainedRows++;
                    else movedRows++;
                }
            }

            if (hasAvailableCutoff) {
                leagueIds.add(leagueSeason.leagueId());
                seasons.add(leagueSeason.season());
                leagueSeasons.add(leagueSeasonId);
                teamCounts.add(teamCount);
            }
        }

        List<GateEvidence> gates = List.of(
            gate(
                GateId.MULTIPLE_LEAGUE_IDENTITIES,
                leagueIds.size(),
                "at least 2 distinct league IDs with available BF-518 cutoffs",
                leagueIds.size() >= 2,
                new ArrayList<>(leagueIds)),
            gate(
                GateId.MULTIPLE_SEASONS,
                seasons.size(),
                "at least 2 distinct seasons with available BF-518 cutoffs",
                seasons.size() >= 2,
                seasons.stream().map(String::valueOf).toList()),
            gate(
                GateId.MULTIPLE_AVAILABLE_LEAGUE_SEASONS,
                leagueSeasons.size(),
                "at least 2 distinct league-season clusters with available BF-518 cutoffs",
                leagueSeasons.size() >= 2,
                new ArrayList<>(leagueSeasons)),
            gate(
                GateId.MULTIPLE_TEAM_COUNT_STRATA,
                teamCounts.size(),
                "at least 2 distinct repository team-count values among available BF-518 cutoffs",
                teamCounts.size() >= 2,
                teamCounts.stream().map(String::valueOf).toList()),
            gate(
                GateId.MULTIPLE_PERTURBATION_DENOMINATORS,
                perturbationDenominators.size(),
                "at least 2 distinct baseline perturbation denominators among available BF-518 cutoffs",
                perturbationDenominators.size() >= 2,
                perturbationDenominators.stream().map(String::valueOf).toList()),
            gate(
                GateId.TEMPORAL_OUTCOME_VARIATION,
                (retainedRows > 0 ? 1 : 0) + (movedRows > 0 ? 1 : 0),
                "at least one retained and at least one moved future-only temporal rank outcome",
                retainedRows > 0 && movedRows > 0,
                List.of("RETAINED_ROWS=" + retainedRows, "MOVED_ROWS=" + movedRows)));

        ReadinessState state = gates.stream().allMatch(GateEvidence::passed)
            ? ReadinessState.READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN
            : ReadinessState.NOT_READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN;

        var sourceSummary = source.summary();
        CorpusReadinessDiagnostics diagnostics = new CorpusReadinessDiagnostics(
            List.copyOf(leagueIds),
            List.copyOf(seasons),
            List.copyOf(leagueSeasons),
            List.copyOf(teamCounts),
            List.copyOf(perturbationDenominators),
            availableCutoffs,
            availableTeamCutoffRows,
            retainedRows,
            movedRows,
            sourceSummary.requestedLeagueSeasons(),
            sourceSummary.auditedLeagueSeasons(),
            sourceSummary.sourceFailureLeagueSeasons(),
            sourceSummary.excludedCutoffs(),
            sensitivityClassCounts,
            changedScenarioNumeratorDistribution,
            rankChangeFrequencyDistribution,
            availableCutoffsByLeagueId,
            availableCutoffsBySeason,
            availableCutoffsByLeagueSeason,
            availableCutoffsByTeamCount,
            availableCutoffsByPerturbationDenominator);

        return new Computed(state, gates, diagnostics);
    }

    private static GateEvidence gate(
        GateId id,
        int observedDistinctCount,
        String requiredCondition,
        boolean passed,
        List<String> observedValues) {
        return new GateEvidence(id, observedDistinctCount, requiredCondition, passed, observedValues);
    }

    public enum ReadinessState {
        READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN,
        NOT_READY_FOR_THRESHOLD_STUDY_METHODOLOGY_DESIGN
    }

    public enum GateId {
        MULTIPLE_LEAGUE_IDENTITIES,
        MULTIPLE_SEASONS,
        MULTIPLE_AVAILABLE_LEAGUE_SEASONS,
        MULTIPLE_TEAM_COUNT_STRATA,
        MULTIPLE_PERTURBATION_DENOMINATORS,
        TEMPORAL_OUTCOME_VARIATION
    }

    public record GateEvidence(
        GateId gateId,
        int observedDistinctCount,
        String requiredCondition,
        boolean passed,
        List<String> observedValues) {

        public GateEvidence {
            Objects.requireNonNull(gateId, "gateId must not be null");
            if (observedDistinctCount < 0) {
                throw new IllegalArgumentException("observedDistinctCount must not be negative");
            }
            requiredCondition = requireText(requiredCondition, "requiredCondition");
            observedValues = List.copyOf(Objects.requireNonNull(observedValues, "observedValues must not be null"));
        }
    }

    public record CorpusReadinessDiagnostics(
        List<String> availableLeagueIds,
        List<Integer> availableSeasons,
        List<String> availableLeagueSeasonIdentities,
        List<Integer> repositoryTeamCountStrata,
        List<Integer> perturbationDenominators,
        int availableCutoffs,
        int availableTeamCutoffRows,
        int exactNumericRankRetainedRows,
        int temporalRankMovedRows,
        int requestedLeagueSeasons,
        int auditedLeagueSeasons,
        int sourceFailureLeagueSeasons,
        int excludedCutoffs,
        Map<LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass, Integer>
            sensitivityClassCounts,
        Map<Integer, Integer> changedScenarioNumeratorDistribution,
        Map<BigDecimal, Integer> rankChangeFrequencyDistribution,
        Map<String, Integer> availableCutoffsByLeagueId,
        Map<Integer, Integer> availableCutoffsBySeason,
        Map<String, Integer> availableCutoffsByLeagueSeason,
        Map<Integer, Integer> availableCutoffsByTeamCount,
        Map<Integer, Integer> availableCutoffsByPerturbationDenominator) {

        public CorpusReadinessDiagnostics {
            availableLeagueIds = List.copyOf(Objects.requireNonNull(availableLeagueIds, "availableLeagueIds must not be null"));
            availableSeasons = List.copyOf(Objects.requireNonNull(availableSeasons, "availableSeasons must not be null"));
            availableLeagueSeasonIdentities = List.copyOf(Objects.requireNonNull(
                availableLeagueSeasonIdentities, "availableLeagueSeasonIdentities must not be null"));
            repositoryTeamCountStrata = List.copyOf(Objects.requireNonNull(
                repositoryTeamCountStrata, "repositoryTeamCountStrata must not be null"));
            perturbationDenominators = List.copyOf(Objects.requireNonNull(
                perturbationDenominators, "perturbationDenominators must not be null"));
            if (availableCutoffs < 0 || availableTeamCutoffRows < 0
                || exactNumericRankRetainedRows < 0 || temporalRankMovedRows < 0
                || requestedLeagueSeasons < 0 || auditedLeagueSeasons < 0
                || sourceFailureLeagueSeasons < 0 || excludedCutoffs < 0) {
                throw new IllegalArgumentException("readiness diagnostic counts must not be negative");
            }
            if (auditedLeagueSeasons + sourceFailureLeagueSeasons != requestedLeagueSeasons) {
                throw new IllegalArgumentException("readiness source league-season counts are inconsistent");
            }
            if (exactNumericRankRetainedRows + temporalRankMovedRows != availableTeamCutoffRows) {
                throw new IllegalArgumentException("readiness temporal outcome rows must match available team-cutoff rows");
            }
            sensitivityClassCounts = Map.copyOf(Objects.requireNonNull(
                sensitivityClassCounts, "sensitivityClassCounts must not be null"));
            changedScenarioNumeratorDistribution = Map.copyOf(Objects.requireNonNull(
                changedScenarioNumeratorDistribution, "changedScenarioNumeratorDistribution must not be null"));
            rankChangeFrequencyDistribution = Map.copyOf(Objects.requireNonNull(
                rankChangeFrequencyDistribution, "rankChangeFrequencyDistribution must not be null"));
            availableCutoffsByLeagueId = Map.copyOf(Objects.requireNonNull(
                availableCutoffsByLeagueId, "availableCutoffsByLeagueId must not be null"));
            availableCutoffsBySeason = Map.copyOf(Objects.requireNonNull(
                availableCutoffsBySeason, "availableCutoffsBySeason must not be null"));
            availableCutoffsByLeagueSeason = Map.copyOf(Objects.requireNonNull(
                availableCutoffsByLeagueSeason, "availableCutoffsByLeagueSeason must not be null"));
            availableCutoffsByTeamCount = Map.copyOf(Objects.requireNonNull(
                availableCutoffsByTeamCount, "availableCutoffsByTeamCount must not be null"));
            availableCutoffsByPerturbationDenominator = Map.copyOf(Objects.requireNonNull(
                availableCutoffsByPerturbationDenominator,
                "availableCutoffsByPerturbationDenominator must not be null"));
        }
    }

    public record CorpusReadinessReport(
        String policyId,
        String metricScope,
        String readinessPolicy,
        LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CorpusAuditReport sourceCorpusAudit,
        ReadinessState readinessState,
        List<GateEvidence> gates,
        CorpusReadinessDiagnostics diagnostics) {

        public CorpusReadinessReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            if (!READINESS_POLICY.equals(readinessPolicy)) throw new IllegalArgumentException("unexpected readinessPolicy");
            Objects.requireNonNull(sourceCorpusAudit, "sourceCorpusAudit must not be null");
            Objects.requireNonNull(readinessState, "readinessState must not be null");
            gates = List.copyOf(Objects.requireNonNull(gates, "gates must not be null"));
            Objects.requireNonNull(diagnostics, "diagnostics must not be null");

            Computed expected = compute(sourceCorpusAudit);
            if (readinessState != expected.state()
                || !gates.equals(expected.gates())
                || !diagnostics.equals(expected.diagnostics())) {
                throw new IllegalArgumentException(
                    "corpus structural readiness fields must match governed BF-518 source evidence");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record Computed(
        ReadinessState state,
        List<GateEvidence> gates,
        CorpusReadinessDiagnostics diagnostics) {}
}
