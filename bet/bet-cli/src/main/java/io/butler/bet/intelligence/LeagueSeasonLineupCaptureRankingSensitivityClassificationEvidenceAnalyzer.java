package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Classifies observed sensitivity of the governed lineup-capture rank from the complete BF-504
 * leave-one-common-week-out stability artifact. This is deterministic ordinal context, not
 * statistical confidence, manager consistency, reliability, quality, skill, or fault.
 */
public final class LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer {
    public static final String POLICY_ID =
        "league-season-lineup-capture-ranking-sensitivity-classification-v1-max-absolute-rank-movement-0-1-2plus-observed-no-confidence-no-manager-attribution";
    public static final String METRIC_SCOPE =
        "DETERMINISTIC_QUALITATIVE_CLASSIFICATION_OF_OBSERVED_BF504_ORDINAL_RANK_SENSITIVITY_NO_STATISTICAL_CONFIDENCE_NO_MANAGER_ATTRIBUTION";
    public static final String CLASSIFICATION_POLICY =
        "MAX_ABSOLUTE_RANK_MOVEMENT_0_LOW_1_MODERATE_2PLUS_HIGH_NO_PARTIAL_CLASSIFICATION_NO_SECONDARY_INPUTS";

    private final Database database;

    public LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public LeagueSensitivityClassificationReport analyze(String leagueId, int season) throws SQLException {
        var source = new LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer(database).analyze(leagueId, season);
        return fromSource(source);
    }

    static LeagueSensitivityClassificationReport fromSource(
        LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.LeagueStabilityReport source) {
        Objects.requireNonNull(source, "source ranking stability report must not be null");
        Computed computed = compute(source);
        return new LeagueSensitivityClassificationReport(
            POLICY_ID,
            METRIC_SCOPE,
            CLASSIFICATION_POLICY,
            source,
            computed.state(),
            computed.teamClassifications());
    }

    private static Computed compute(
        LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.LeagueStabilityReport source) {
        if (source.stabilityState()
            != LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.StabilityState.AVAILABLE) {
            return new Computed(ClassificationState.UNAVAILABLE_SOURCE_STABILITY, List.of());
        }

        List<TeamSensitivityClassification> classifications = new ArrayList<>();
        for (var team : source.teamSummaries()) {
            classifications.add(TeamSensitivityClassification.fromSource(team));
        }
        if (classifications.isEmpty()) {
            throw new IllegalStateException(
                "ranking sensitivity classification invariant failed: available source stability requires team summaries");
        }
        return new Computed(ClassificationState.AVAILABLE, List.copyOf(classifications));
    }

    private static SensitivityClass classify(int maximumAbsoluteRankMovement) {
        if (maximumAbsoluteRankMovement < 0) {
            throw new IllegalArgumentException("maximumAbsoluteRankMovement must not be negative");
        }
        if (maximumAbsoluteRankMovement == 0) return SensitivityClass.LOW_SENSITIVITY;
        if (maximumAbsoluteRankMovement == 1) return SensitivityClass.MODERATE_SENSITIVITY;
        return SensitivityClass.HIGH_SENSITIVITY;
    }

    public enum ClassificationState {
        AVAILABLE,
        UNAVAILABLE_SOURCE_STABILITY
    }

    public enum SensitivityClass {
        LOW_SENSITIVITY,
        MODERATE_SENSITIVITY,
        HIGH_SENSITIVITY
    }

    public record TeamSensitivityClassification(
        String teamId,
        String teamName,
        int baselineRank,
        BigDecimal baselineLineupCaptureRate,
        int perturbationScenarioCount,
        int maximumAbsoluteRankMovement,
        int rankSensitivityRangeWidth,
        int baselineRankUnchangedScenarios,
        int baselineRankChangedScenarios,
        SensitivityClass sensitivityClass) {

        public TeamSensitivityClassification {
            teamId = requireText(teamId, "teamId");
            teamName = requireText(teamName, "teamName");
            if (baselineRank <= 0 || perturbationScenarioCount <= 0) {
                throw new IllegalArgumentException("classification baseline rank and scenario count must be positive");
            }
            Objects.requireNonNull(baselineLineupCaptureRate, "baselineLineupCaptureRate must not be null");
            if (maximumAbsoluteRankMovement < 0
                || rankSensitivityRangeWidth < 0
                || baselineRankUnchangedScenarios < 0
                || baselineRankChangedScenarios < 0
                || baselineRankUnchangedScenarios + baselineRankChangedScenarios != perturbationScenarioCount) {
                throw new IllegalArgumentException("classification source movement context is inconsistent");
            }
            Objects.requireNonNull(sensitivityClass, "sensitivityClass must not be null");
            if (sensitivityClass != classify(maximumAbsoluteRankMovement)) {
                throw new IllegalArgumentException(
                    "sensitivityClass must match governed maximum absolute rank movement rule");
            }
        }

        static TeamSensitivityClassification fromSource(
            LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.TeamStabilitySummary source) {
            return new TeamSensitivityClassification(
                source.teamId(),
                source.teamName(),
                source.baselineRank(),
                source.baselineLineupCaptureRate(),
                source.perturbationScenarioCount(),
                source.maximumAbsoluteRankMovement(),
                source.rankSensitivityRangeWidth(),
                source.baselineRankUnchangedScenarios(),
                source.baselineRankChangedScenarios(),
                classify(source.maximumAbsoluteRankMovement()));
        }
    }

    public record LeagueSensitivityClassificationReport(
        String policyId,
        String metricScope,
        String classificationPolicy,
        LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.LeagueStabilityReport sourceRankingStability,
        ClassificationState classificationState,
        List<TeamSensitivityClassification> teamClassifications) {

        public LeagueSensitivityClassificationReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            if (!CLASSIFICATION_POLICY.equals(classificationPolicy)) {
                throw new IllegalArgumentException("unexpected classificationPolicy");
            }
            Objects.requireNonNull(sourceRankingStability, "sourceRankingStability must not be null");
            Objects.requireNonNull(classificationState, "classificationState must not be null");
            teamClassifications = List.copyOf(Objects.requireNonNull(
                teamClassifications, "teamClassifications must not be null"));

            Computed expected = compute(sourceRankingStability);
            if (classificationState != expected.state()
                || !teamClassifications.equals(expected.teamClassifications())) {
                throw new IllegalArgumentException(
                    "sensitivity classification fields must match governed ranking stability source evidence");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record Computed(
        ClassificationState state,
        List<TeamSensitivityClassification> teamClassifications) {}
}
