package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.PlayerFantasyPositionObservationRepository;
import io.butler.bet.data.PlayerWeekProductionCoverageRepository;
import io.butler.bet.data.PlayerWeekProductionRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Calculates one retrospective potential lineup only after the dedicated coverage analyzer proves
 * that every required evidence boundary is ready.
 */
public final class LeagueTeamWeekPotentialLineupAnalyzer {
    public static final String POLICY_ID =
        "team-week-potential-lineup-v1-ready-evidence-exact-score-optimal-solver";

    private final Database database;

    public LeagueTeamWeekPotentialLineupAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public PotentialLineupReport analyze(String leagueId, String teamId, int season, int week)
        throws SQLException {
        var coverage = new LeagueTeamWeekPotentialLineupCoverageAnalyzer(database)
            .analyze(leagueId, teamId, season, week);
        if (!coverage.ready()) {
            throw new IllegalStateException(
                "Potential lineup unavailable: " + String.join("; ", coverage.blockers()));
        }

        var configuration = new LeagueConfigurationObservationRepository(database)
            .findLatestForSeason(coverage.leagueId(), season,
                LeagueTeamWeekPotentialLineupCoverageAnalyzer.SLEEPER_SOURCE)
            .orElseThrow(() -> new IllegalStateException("League configuration moved after readiness check"));
        if (!configuration.asOfDate().equals(coverage.leagueConfigurationAsOf())) {
            throw new IllegalStateException("League configuration moved after readiness check");
        }

        var rosterEvidence = new TeamWeekRosterEvidenceRepository(database)
            .findLatest(coverage.teamId(), season, week,
                LeagueTeamWeekPotentialLineupCoverageAnalyzer.SLEEPER_SOURCE)
            .orElseThrow(() -> new IllegalStateException("Team-week roster evidence moved after readiness check"));
        if (!rosterEvidence.asOfDate().equals(coverage.rosterEvidenceAsOf())
            || !rosterEvidence.providerPlayerIds().equals(
                coverage.players().stream()
                    .map(LeagueTeamWeekPotentialLineupCoverageAnalyzer.PlayerCoverage::providerPlayerId)
                    .toList())) {
            throw new IllegalStateException("Team-week roster evidence moved after readiness check");
        }

        var productionCoverage = new PlayerWeekProductionCoverageRepository(database)
            .findLatest(season, week, LeagueTeamWeekPotentialLineupCoverageAnalyzer.PRODUCTION_SOURCE)
            .orElseThrow(() -> new IllegalStateException("Production coverage moved after readiness check"));
        if (!productionCoverage.asOfDate().equals(coverage.productionCoverageAsOf())
            || !productionCoverage.sourceUri().equals(coverage.productionSourceUri())) {
            throw new IllegalStateException("Production coverage moved after readiness check");
        }

        PlayerFantasyPositionObservationRepository eligibilityObservations =
            new PlayerFantasyPositionObservationRepository(database);
        PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
        CoveredProductionScoringPolicy scoringPolicy = new CoveredProductionScoringPolicy();
        List<OptimalLegalLineupSolver.ScoredPlayerCandidate> candidates = new ArrayList<>();
        List<PlayerScoreEvidence> playerScores = new ArrayList<>();

        for (var playerCoverage : coverage.players()) {
            String playerId = Objects.requireNonNull(
                playerCoverage.playerId(), "READY coverage playerId must not be null");
            var eligibility = eligibilityObservations
                .findLatest(playerId, LeagueTeamWeekPotentialLineupCoverageAnalyzer.SLEEPER_SOURCE)
                .orElseThrow(() -> new IllegalStateException(
                    "Player eligibility moved after readiness check: " + playerId));
            if (!eligibility.asOfDate().equals(playerCoverage.eligibilityObservationAsOf())
                || !eligibility.providerFantasyPositions().equals(playerCoverage.providerFantasyPositions())) {
                throw new IllegalStateException(
                    "Player eligibility moved after readiness check: " + playerId);
            }

            BigDecimal points;
            String productionId = null;
            String scoringPolicyId = null;
            switch (playerCoverage.productionState()) {
                case OBSERVED -> {
                    var observed = production.findAtAsOf(
                        playerId, season, week,
                        LeagueTeamWeekPotentialLineupCoverageAnalyzer.PRODUCTION_SOURCE,
                        productionCoverage.asOfDate())
                        .orElseThrow(() -> new IllegalStateException(
                            "Observed production moved after readiness check: " + playerId));
                    if (!observed.id().equals(playerCoverage.productionId())) {
                        throw new IllegalStateException(
                            "Observed production moved after readiness check: " + playerId);
                    }
                    var score = scoringPolicy.score(observed, configuration.scoringSettings());
                    points = score.totalPoints();
                    productionId = observed.id();
                    scoringPolicyId = score.policyId();
                }
                case IDENTITY_COVERED_ZERO -> points = BigDecimal.ZERO;
                default -> throw new IllegalStateException(
                    "READY coverage contained non-ready production state for player " + playerId
                        + ": " + playerCoverage.productionState());
            }

            candidates.add(new OptimalLegalLineupSolver.ScoredPlayerCandidate(
                playerId, eligibility.providerFantasyPositions(), points));
            playerScores.add(new PlayerScoreEvidence(
                playerCoverage.providerPlayerId(),
                playerId,
                eligibility.asOfDate(),
                eligibility.providerFantasyPositions(),
                playerCoverage.productionState(),
                productionId,
                productionCoverage.asOfDate(),
                scoringPolicyId,
                points));
        }

        var lineup = new OptimalLegalLineupSolver().solve(configuration.lineupSlots(), candidates);
        return new PotentialLineupReport(
            POLICY_ID,
            coverage.policyId(),
            coverage.metricScope(),
            CoveredProductionScoringPolicy.POLICY_ID,
            lineup.policyId(),
            lineup.eligibilityPolicyId(),
            coverage.leagueId(),
            coverage.teamId(),
            season,
            week,
            coverage.leagueConfigurationAsOf(),
            coverage.rosterEvidenceAsOf(),
            coverage.productionCoverageAsOf(),
            List.copyOf(playerScores),
            lineup);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public record PlayerScoreEvidence(
        String providerPlayerId,
        String playerId,
        LocalDate eligibilityObservationAsOf,
        List<String> providerFantasyPositions,
        LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState productionState,
        String productionId,
        LocalDate productionCoverageAsOf,
        String scoringPolicyId,
        BigDecimal fantasyPoints) {
        public PlayerScoreEvidence {
            requireText(providerPlayerId, "providerPlayerId");
            requireText(playerId, "playerId");
            Objects.requireNonNull(eligibilityObservationAsOf, "eligibilityObservationAsOf must not be null");
            providerFantasyPositions = List.copyOf(Objects.requireNonNull(
                providerFantasyPositions, "providerFantasyPositions must not be null"));
            Objects.requireNonNull(productionState, "productionState must not be null");
            Objects.requireNonNull(productionCoverageAsOf, "productionCoverageAsOf must not be null");
            Objects.requireNonNull(fantasyPoints, "fantasyPoints must not be null");
            if (productionState == LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.OBSERVED) {
                requireText(productionId, "productionId");
                requireText(scoringPolicyId, "scoringPolicyId");
            } else if (productionState
                == LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.IDENTITY_COVERED_ZERO) {
                if (productionId != null || scoringPolicyId != null || fantasyPoints.compareTo(BigDecimal.ZERO) != 0) {
                    throw new IllegalArgumentException(
                        "identity-covered zero must have no production/scoring artifact and exactly zero points");
                }
            } else {
                throw new IllegalArgumentException("PlayerScoreEvidence requires a ready production state");
            }
        }
    }

    public record PotentialLineupReport(
        String policyId,
        String coveragePolicyId,
        String metricScope,
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
        List<PlayerScoreEvidence> playerScores,
        OptimalLegalLineupSolver.LineupResult lineup) {
        public PotentialLineupReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!LeagueTeamWeekPotentialLineupCoverageAnalyzer.POLICY_ID.equals(coveragePolicyId)) {
                throw new IllegalArgumentException("unexpected coveragePolicyId");
            }
            if (!LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE.equals(metricScope)) {
                throw new IllegalArgumentException("unexpected metricScope");
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
            playerScores = List.copyOf(Objects.requireNonNull(playerScores, "playerScores must not be null"));
            Objects.requireNonNull(lineup, "lineup must not be null");
        }
    }
}
