package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Calculates a descriptive retrospective points gap between one governed complete potential lineup
 * and one governed complete observed started lineup for the same team-week evidence boundary.
 *
 * <p>The gap is potential points minus recalculated started points. It is not a manager-efficiency
 * score and does not establish historical startability, intent, fault, rank, or recommendation.</p>
 */
public final class LeagueTeamWeekLineupPointsGapEvidenceAnalyzer {
    public static final String POLICY_ID =
        "team-week-lineup-points-gap-evidence-v1-complete-only-potential-minus-started-no-attribution";
    public static final String METRIC_SCOPE =
        "RETROSPECTIVE_RECALCULATED_POTENTIAL_MINUS_STARTED_POINTS_COMPLETE_GOVERNED_LINEUPS_ONLY_NOT_MANAGER_EFFICIENCY";

    private final Database database;

    public LeagueTeamWeekLineupPointsGapEvidenceAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public LineupPointsGapReport analyze(String leagueId, String teamId, int season, int week)
        throws SQLException {
        var potential = new LeagueTeamWeekPotentialLineupAnalyzer(database)
            .analyze(leagueId, teamId, season, week);
        var started = new LeagueTeamWeekStartedLineupEvidenceAnalyzer(database)
            .analyze(leagueId, teamId, season, week);

        requireSameEvidenceBoundary(potential, started);
        requireStartedScoresMatchPotentialEvidence(potential, started);

        if (!potential.lineup().complete()) {
            throw new IllegalStateException(
                "Lineup points gap unavailable: governed potential lineup is incomplete ("
                    + potential.lineup().filledSlots() + "/" + potential.lineup().startingSlots() + ")");
        }
        if (!started.complete()) {
            throw new IllegalStateException(
                "Lineup points gap unavailable: observed started lineup is incomplete ("
                    + started.filledSlots() + "/" + started.requiredSlots() + ")");
        }
        if (potential.lineup().startingSlots() != started.requiredSlots()) {
            throw new IllegalStateException(
                "Lineup points gap unavailable: potential and started required-slot counts differ");
        }

        BigDecimal potentialPoints = potential.lineup().totalPoints();
        BigDecimal startedPoints = started.totalStartedPoints();
        BigDecimal pointsGap = potentialPoints.subtract(startedPoints);
        if (pointsGap.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException(
                "Lineup points gap invariant failed: complete governed potential points are below started points");
        }

        return new LineupPointsGapReport(
            POLICY_ID,
            METRIC_SCOPE,
            potential.policyId(),
            potential.metricScope(),
            started.policyId(),
            started.metricScope(),
            potential.scoringPolicyId(),
            potential.solverPolicyId(),
            potential.eligibilityPolicyId(),
            potential.leagueId(),
            potential.teamId(),
            season,
            week,
            potential.leagueConfigurationAsOf(),
            potential.rosterEvidenceAsOf(),
            potential.productionCoverageAsOf(),
            potential.productionSourceUri(),
            potential.lineup().startingSlots(),
            startedPoints,
            potentialPoints,
            pointsGap);
    }

    private static void requireSameEvidenceBoundary(
        LeagueTeamWeekPotentialLineupAnalyzer.PotentialLineupReport potential,
        LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedLineupReport started) {
        if (!potential.leagueId().equals(started.leagueId())
            || !potential.teamId().equals(started.teamId())
            || potential.season() != started.season()
            || potential.week() != started.week()) {
            throw new IllegalStateException(
                "Lineup points gap unavailable: potential and started reports target different team-weeks");
        }
        if (!potential.leagueConfigurationAsOf().equals(started.leagueConfigurationAsOf())
            || !potential.rosterEvidenceAsOf().equals(started.rosterEvidenceAsOf())
            || !potential.productionCoverageAsOf().equals(started.productionCoverageAsOf())
            || !potential.productionSourceUri().equals(started.productionSourceUri())) {
            throw new IllegalStateException(
                "Lineup points gap unavailable: potential and started evidence provenance differs");
        }
        if (!potential.scoringPolicyId().equals(started.scoringPolicyId())
            || !potential.eligibilityPolicyId().equals(started.eligibilityPolicyId())) {
            throw new IllegalStateException(
                "Lineup points gap unavailable: potential and started scoring/eligibility policies differ");
        }
    }

    private static void requireStartedScoresMatchPotentialEvidence(
        LeagueTeamWeekPotentialLineupAnalyzer.PotentialLineupReport potential,
        LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedLineupReport started) {
        Map<String, LeagueTeamWeekPotentialLineupAnalyzer.PlayerScoreEvidence> potentialScores = new HashMap<>();
        for (var score : potential.playerScores()) {
            if (potentialScores.put(score.providerPlayerId(), score) != null) {
                throw new IllegalStateException(
                    "Lineup points gap unavailable: duplicate provider player in potential scoring evidence: "
                        + score.providerPlayerId());
            }
        }
        for (var slot : started.slots()) {
            if (slot.state() == LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedSlotState.EMPTY) continue;
            var potentialScore = potentialScores.get(slot.providerStarterId());
            if (potentialScore == null || !potentialScore.equals(slot.scoreEvidence())) {
                throw new IllegalStateException(
                    "Lineup points gap unavailable: started player scoring evidence differs from potential evidence: "
                        + slot.providerStarterId());
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public record LineupPointsGapReport(
        String policyId,
        String metricScope,
        String potentialLineupPolicyId,
        String potentialMetricScope,
        String startedLineupPolicyId,
        String startedMetricScope,
        String scoringPolicyId,
        String solverPolicyId,
        String eligibilityPolicyId,
        String leagueId,
        String teamId,
        int season,
        int week,
        LocalDate leagueConfigurationAsOf,
        LocalDate rosterEvidenceAsOf,
        LocalDate productionCoverageAsOf,
        URI productionSourceUri,
        int startingSlots,
        BigDecimal startedPoints,
        BigDecimal potentialPoints,
        BigDecimal pointsGap) {

        public LineupPointsGapReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            if (!LeagueTeamWeekPotentialLineupAnalyzer.POLICY_ID.equals(potentialLineupPolicyId)) {
                throw new IllegalArgumentException("unexpected potentialLineupPolicyId");
            }
            if (!LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE.equals(potentialMetricScope)) {
                throw new IllegalArgumentException("unexpected potentialMetricScope");
            }
            if (!LeagueTeamWeekStartedLineupEvidenceAnalyzer.POLICY_ID.equals(startedLineupPolicyId)) {
                throw new IllegalArgumentException("unexpected startedLineupPolicyId");
            }
            if (!LeagueTeamWeekStartedLineupEvidenceAnalyzer.METRIC_SCOPE.equals(startedMetricScope)) {
                throw new IllegalArgumentException("unexpected startedMetricScope");
            }
            if (!CoveredProductionScoringPolicy.POLICY_ID.equals(scoringPolicyId)) {
                throw new IllegalArgumentException("unexpected scoringPolicyId");
            }
            if (!OptimalLegalLineupSolver.POLICY_ID.equals(solverPolicyId)) {
                throw new IllegalArgumentException("unexpected solverPolicyId");
            }
            if (!LineupSlotEligibilityPolicy.POLICY_ID.equals(eligibilityPolicyId)) {
                throw new IllegalArgumentException("unexpected eligibilityPolicyId");
            }
            requireText(leagueId, "leagueId");
            requireText(teamId, "teamId");
            if (season < 1999 || season > 2100) {
                throw new IllegalArgumentException("season must be between 1999 and 2100");
            }
            if (week <= 0) throw new IllegalArgumentException("week must be positive");
            Objects.requireNonNull(leagueConfigurationAsOf, "leagueConfigurationAsOf must not be null");
            Objects.requireNonNull(rosterEvidenceAsOf, "rosterEvidenceAsOf must not be null");
            Objects.requireNonNull(productionCoverageAsOf, "productionCoverageAsOf must not be null");
            Objects.requireNonNull(productionSourceUri, "productionSourceUri must not be null");
            if (startingSlots <= 0) throw new IllegalArgumentException("startingSlots must be positive");
            Objects.requireNonNull(startedPoints, "startedPoints must not be null");
            Objects.requireNonNull(potentialPoints, "potentialPoints must not be null");
            Objects.requireNonNull(pointsGap, "pointsGap must not be null");
            if (pointsGap.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("pointsGap must not be negative");
            }
            if (potentialPoints.subtract(startedPoints).compareTo(pointsGap) != 0) {
                throw new IllegalArgumentException("pointsGap must equal potentialPoints minus startedPoints");
            }
        }
    }
}
