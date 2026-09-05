package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic observed rank-change frequency over the complete governed BF-504 leave-one-week-out
 * perturbation set. Frequency is descriptive sensitivity context, not probability, confidence,
 * manager consistency, reliability, quality, skill, or a replacement rank.
 */
public final class LeagueSeasonLineupCaptureRankingChangeFrequencyEvidenceAnalyzer {
    public static final String POLICY_ID =
        "league-season-lineup-capture-ranking-change-frequency-v1-complete-leave-one-out-changed-over-total-no-confidence-no-manager-attribution";
    public static final String METRIC_SCOPE =
        "DETERMINISTIC_OBSERVED_RANK_CHANGE_FREQUENCY_ACROSS_COMPLETE_BF504_LEAVE_ONE_COMMON_WEEK_OUT_SCENARIOS_NO_CONFIDENCE_NO_MANAGER_ATTRIBUTION";
    public static final String FREQUENCY_POLICY =
        "CHANGED_SCENARIOS_OVER_COMPLETE_PERTURBATION_COUNT_SIX_DECIMAL_HALF_UP_NO_FREQUENCY_TIERS_NO_MAGNITUDE_FREQUENCY_COMPOSITE";
    public static final int FREQUENCY_SCALE = 6;
    public static final RoundingMode FREQUENCY_ROUNDING = RoundingMode.HALF_UP;

    private final Database database;

    public LeagueSeasonLineupCaptureRankingChangeFrequencyEvidenceAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public LeagueRankChangeFrequencyReport analyze(String leagueId, int season) throws SQLException {
        var source = new LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer(database).analyze(leagueId, season);
        return fromSource(source);
    }

    static LeagueRankChangeFrequencyReport fromSource(
        LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.LeagueStabilityReport source) {
        Objects.requireNonNull(source, "source ranking stability report must not be null");
        Computed computed = compute(source);
        return new LeagueRankChangeFrequencyReport(
            POLICY_ID,
            METRIC_SCOPE,
            FREQUENCY_POLICY,
            source,
            computed.state(),
            computed.teams());
    }

    private static Computed compute(
        LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.LeagueStabilityReport source) {
        if (source.stabilityState()
            != LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.StabilityState.AVAILABLE) {
            return new Computed(FrequencyState.UNAVAILABLE_SOURCE_STABILITY, List.of());
        }

        List<TeamRankChangeFrequencyEvidence> teams = new ArrayList<>();
        for (var team : source.teamSummaries()) {
            teams.add(TeamRankChangeFrequencyEvidence.fromSource(team));
        }
        if (teams.isEmpty()) {
            throw new IllegalStateException(
                "ranking change frequency invariant failed: available source stability requires team summaries");
        }
        return new Computed(FrequencyState.AVAILABLE, List.copyOf(teams));
    }

    private static BigDecimal frequency(int numerator, int denominator) {
        if (numerator < 0 || denominator <= 0 || numerator > denominator) {
            throw new IllegalArgumentException("frequency numerator/denominator are inconsistent");
        }
        return BigDecimal.valueOf(numerator)
            .divide(BigDecimal.valueOf(denominator), FREQUENCY_SCALE, FREQUENCY_ROUNDING);
    }

    public enum FrequencyState {
        AVAILABLE,
        UNAVAILABLE_SOURCE_STABILITY
    }

    public record TeamRankChangeFrequencyEvidence(
        String teamId,
        String teamName,
        int baselineRank,
        BigDecimal baselineLineupCaptureRate,
        int perturbationScenarioCount,
        int baselineRankUnchangedScenarios,
        int baselineRankChangedScenarios,
        BigDecimal rankChangeFrequency,
        BigDecimal rankRetentionFrequency,
        int maximumAbsoluteRankMovement,
        LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer.SensitivityClass magnitudeSensitivityClass) {

        public TeamRankChangeFrequencyEvidence {
            teamId = requireText(teamId, "teamId");
            teamName = requireText(teamName, "teamName");
            if (baselineRank <= 0 || perturbationScenarioCount <= 0) {
                throw new IllegalArgumentException("frequency baseline rank and scenario count must be positive");
            }
            Objects.requireNonNull(baselineLineupCaptureRate, "baselineLineupCaptureRate must not be null");
            if (baselineRankUnchangedScenarios < 0
                || baselineRankChangedScenarios < 0
                || baselineRankUnchangedScenarios + baselineRankChangedScenarios != perturbationScenarioCount
                || maximumAbsoluteRankMovement < 0) {
                throw new IllegalArgumentException("frequency source movement context is inconsistent");
            }
            Objects.requireNonNull(rankChangeFrequency, "rankChangeFrequency must not be null");
            Objects.requireNonNull(rankRetentionFrequency, "rankRetentionFrequency must not be null");
            Objects.requireNonNull(magnitudeSensitivityClass, "magnitudeSensitivityClass must not be null");

            BigDecimal expectedChange = frequency(baselineRankChangedScenarios, perturbationScenarioCount);
            BigDecimal expectedRetention = frequency(baselineRankUnchangedScenarios, perturbationScenarioCount);
            if (!rankChangeFrequency.equals(expectedChange)
                || !rankRetentionFrequency.equals(expectedRetention)
                || rankChangeFrequency.scale() != FREQUENCY_SCALE
                || rankRetentionFrequency.scale() != FREQUENCY_SCALE
                || rankChangeFrequency.compareTo(BigDecimal.ZERO) < 0
                || rankChangeFrequency.compareTo(BigDecimal.ONE) > 0
                || rankRetentionFrequency.compareTo(BigDecimal.ZERO) < 0
                || rankRetentionFrequency.compareTo(BigDecimal.ONE) > 0
                || rankChangeFrequency.add(rankRetentionFrequency)
                    .compareTo(BigDecimal.ONE.setScale(FREQUENCY_SCALE)) != 0) {
                throw new IllegalArgumentException("frequency values must match governed complete-scenario counts");
            }

            var magnitude = LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer
                .TeamSensitivityClassification.fromSource(new LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer
                    .TeamStabilitySummary(
                        teamId,
                        teamName,
                        baselineRank,
                        baselineLineupCaptureRate,
                        perturbationScenarioCount,
                        List.of(baselineRank),
                        baselineRank,
                        baselineRank,
                        0,
                        maximumAbsoluteRankMovement,
                        baselineRankUnchangedScenarios,
                        baselineRankChangedScenarios,
                        baselineLineupCaptureRate,
                        baselineLineupCaptureRate,
                        BigDecimal.ZERO.setScale(LeagueTeamWeekLineupCaptureEvidenceAnalyzer.RATE_SCALE),
                        baselineRankUnchangedScenarios == perturbationScenarioCount));
            if (magnitudeSensitivityClass != magnitude.sensitivityClass()) {
                throw new IllegalArgumentException("magnitudeSensitivityClass must match governed BF-508 movement rule");
            }
        }

        static TeamRankChangeFrequencyEvidence fromSource(
            LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.TeamStabilitySummary source) {
            var magnitude = LeagueSeasonLineupCaptureRankingSensitivityClassificationEvidenceAnalyzer
                .TeamSensitivityClassification.fromSource(source);
            return new TeamRankChangeFrequencyEvidence(
                source.teamId(),
                source.teamName(),
                source.baselineRank(),
                source.baselineLineupCaptureRate(),
                source.perturbationScenarioCount(),
                source.baselineRankUnchangedScenarios(),
                source.baselineRankChangedScenarios(),
                frequency(source.baselineRankChangedScenarios(), source.perturbationScenarioCount()),
                frequency(source.baselineRankUnchangedScenarios(), source.perturbationScenarioCount()),
                source.maximumAbsoluteRankMovement(),
                magnitude.sensitivityClass());
        }
    }

    public record LeagueRankChangeFrequencyReport(
        String policyId,
        String metricScope,
        String frequencyPolicy,
        LeagueSeasonLineupCaptureRankingStabilityEvidenceAnalyzer.LeagueStabilityReport sourceRankingStability,
        FrequencyState frequencyState,
        List<TeamRankChangeFrequencyEvidence> teams) {

        public LeagueRankChangeFrequencyReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            if (!FREQUENCY_POLICY.equals(frequencyPolicy)) throw new IllegalArgumentException("unexpected frequencyPolicy");
            Objects.requireNonNull(sourceRankingStability, "sourceRankingStability must not be null");
            Objects.requireNonNull(frequencyState, "frequencyState must not be null");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));

            Computed expected = compute(sourceRankingStability);
            if (frequencyState != expected.state() || !teams.equals(expected.teams())) {
                throw new IllegalArgumentException(
                    "rank-change-frequency fields must match governed ranking stability source evidence");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record Computed(FrequencyState state, List<TeamRankChangeFrequencyEvidence> teams) {}
}
