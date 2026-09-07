package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerFantasyPositionObservationRepository;
import io.butler.bet.data.PlayerWeekProductionCoverageRepository;
import io.butler.bet.data.PlayerWeekProductionRepository;
import io.butler.bet.data.ProviderPlayerWeekPointsEvidenceRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.ProviderPlayerWeekPointsEvidence;
import io.butler.bet.sleeper.SleeperProviderNativeSeasonScoringAudit;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Calculates one retrospective potential lineup only after the dedicated coverage analyzer proves
 * that every required evidence boundary is ready.
 */
public final class LeagueTeamWeekPotentialLineupAnalyzer {
    public static final String POLICY_ID =
        "team-week-potential-lineup-v2-deterministic-historical-scoring-lane-optimal-solver";

    private final Database database;

    public LeagueTeamWeekPotentialLineupAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public PotentialLineupReport analyze(String leagueId, String teamId, int season, int week)
        throws SQLException {
        var coverage = new LeagueTeamWeekPotentialLineupCoverageAnalyzer(database)
            .analyze(leagueId, teamId, season, week);
        return analyze(coverage);
    }

    PotentialLineupReport analyze(LeagueTeamWeekPotentialLineupCoverageAnalyzer.CoverageReport coverage)
        throws SQLException {
        Objects.requireNonNull(coverage, "coverage must not be null");
        if (!coverage.ready()) {
            throw new IllegalStateException(
                "Potential lineup unavailable: " + String.join("; ", coverage.blockers()));
        }

        int season = coverage.season();
        int week = coverage.week();
        var lineupConfiguration = new HistoricalEffectiveLineupConfigurationResolver(database)
            .select(coverage.leagueId(), season, LeagueTeamWeekPotentialLineupCoverageAnalyzer.SLEEPER_SOURCE);
        var configuration = lineupConfiguration.rawConfiguration();
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

        PlayerWeekProductionCoverageRepository productionCoverageRepository =
            new PlayerWeekProductionCoverageRepository(database);
        PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
        PlayerFantasyPositionObservationRepository eligibilityObservations =
            new PlayerFantasyPositionObservationRepository(database);
        CoveredProductionScoringPolicy scoringPolicy = new CoveredProductionScoringPolicy();

        var productionCoverage = coverage.scoringLane() == HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT
            ? productionCoverageRepository
                .findLatest(season, week, LeagueTeamWeekPotentialLineupCoverageAnalyzer.PRODUCTION_SOURCE)
                .orElseThrow(() -> new IllegalStateException("Production coverage moved after readiness check"))
            : null;
        if (productionCoverage != null
            && (!productionCoverage.asOfDate().equals(coverage.productionCoverageAsOf())
                || !productionCoverage.sourceUri().equals(coverage.productionSourceUri()))) {
            throw new IllegalStateException("Production coverage moved after readiness check");
        }

        Map<String, ProviderPlayerWeekPointsEvidence> providerScores =
            coverage.scoringLane() == HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE
                ? reloadProviderScores(coverage)
                : Map.of();

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
            LocalDate playerProductionCoverageAsOf = null;
            String playerScoringPolicyId;
            String providerPointsEvidenceId = null;

            if (coverage.scoringLane() == HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE) {
                ProviderPlayerWeekPointsEvidence providerScore = providerScores.get(playerCoverage.providerPlayerId());
                if (providerScore == null) {
                    throw new IllegalStateException(
                        "Provider score moved after readiness check: " + playerCoverage.providerPlayerId());
                }
                if (!providerScore.id().equals(playerCoverage.providerPointsEvidenceId())
                    || !providerScore.points().equals(playerCoverage.providerFantasyPoints())) {
                    throw new IllegalStateException(
                        "Provider score moved after readiness check: " + playerCoverage.providerPlayerId());
                }
                if (playerCoverage.productionState()
                    != LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.NOT_EVALUATED
                    || playerCoverage.productionId() != null) {
                    throw new IllegalStateException(
                        "Provider-native READY coverage contains nflverse production evidence");
                }
                points = providerScore.points();
                providerPointsEvidenceId = providerScore.id();
                playerScoringPolicyId = HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID;
            } else {
                playerProductionCoverageAsOf = productionCoverage.asOfDate();
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
                        playerScoringPolicyId = score.policyId();
                    }
                    case IDENTITY_COVERED_ZERO -> {
                        points = BigDecimal.ZERO;
                        playerScoringPolicyId = null;
                    }
                    default -> throw new IllegalStateException(
                        "READY nflverse coverage contained non-ready production state for player " + playerId
                            + ": " + playerCoverage.productionState());
                }
            }

            candidates.add(new OptimalLegalLineupSolver.ScoredPlayerCandidate(
                playerId, eligibility.providerFantasyPositions(), points));
            playerScores.add(new PlayerScoreEvidence(
                playerCoverage.providerPlayerId(),
                playerId,
                eligibility.asOfDate(),
                eligibility.providerFantasyPositions(),
                coverage.scoringLane(),
                playerCoverage.productionState(),
                productionId,
                playerProductionCoverageAsOf,
                providerPointsEvidenceId,
                playerScoringPolicyId,
                points));
        }

        var lineup = new OptimalLegalLineupSolver().solve(lineupConfiguration.effectiveLineupSlots(), candidates);
        return new PotentialLineupReport(
            POLICY_ID,
            coverage.policyId(),
            coverage.metricScope(),
            coverage.scoringLaneSelectionPolicyId(),
            coverage.scoringLane(),
            coverage.scoringLane() == HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE
                ? HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID
                : CoveredProductionScoringPolicy.POLICY_ID,
            lineup.policyId(),
            lineup.eligibilityPolicyId(),
            coverage.leagueId(),
            coverage.teamId(),
            season,
            week,
            coverage.leagueConfigurationAsOf(),
            coverage.rosterEvidenceAsOf(),
            coverage.productionCoverageAsOf(),
            coverage.productionSourceUri(),
            coverage.providerPointsAsOf(),
            coverage.providerPointsSourceSurface(),
            coverage.providerLeagueId(),
            List.copyOf(playerScores),
            lineup);
    }

    private Map<String, ProviderPlayerWeekPointsEvidence> reloadProviderScores(
        LeagueTeamWeekPotentialLineupCoverageAnalyzer.CoverageReport coverage) throws SQLException {
        List<ProviderPlayerWeekPointsEvidence> latest = new ProviderPlayerWeekPointsEvidenceRepository(database)
            .findLatestByLeagueSeason(
                coverage.leagueId(),
                coverage.season(),
                SleeperProviderNativeSeasonScoringAudit.SOURCE);
        if (latest.isEmpty()) {
            throw new IllegalStateException("Provider-points snapshot moved after readiness check");
        }
        for (ProviderPlayerWeekPointsEvidence row : latest) {
            if (!coverage.providerPointsAsOf().equals(row.asOfDate())
                || !coverage.leagueId().equals(row.leagueId())
                || coverage.season() != row.season()
                || !SleeperProviderNativeSeasonScoringAudit.SOURCE.equals(row.source())
                || !coverage.providerPointsSourceSurface().equals(row.sourceSurface())
                || !coverage.providerLeagueId().equals(row.providerLeagueId())) {
                throw new IllegalStateException("Provider-points snapshot moved after readiness check");
            }
        }

        var team = new TeamRepository(database).findById(coverage.teamId())
            .orElseThrow(() -> new IllegalStateException("Team moved after readiness check"));
        if (!coverage.leagueId().equals(team.getLeagueId()) || team.getExternalId() == null) {
            throw new IllegalStateException("Team moved after readiness check");
        }

        Map<String, ProviderPlayerWeekPointsEvidence> result = new LinkedHashMap<>();
        for (ProviderPlayerWeekPointsEvidence row : latest) {
            if (!coverage.teamId().equals(row.teamId()) || coverage.week() != row.week()) continue;
            if (!team.getExternalId().equals(row.providerRosterId())) {
                throw new IllegalStateException("Provider roster provenance moved after readiness check");
            }
            ProviderPlayerWeekPointsEvidence existing = result.putIfAbsent(row.providerPlayerId(), row);
            if (existing != null) {
                throw new IllegalStateException(
                    "Duplicate provider score after readiness check: " + row.providerPlayerId());
            }
        }

        List<String> expectedProviderIds = coverage.players().stream()
            .map(LeagueTeamWeekPotentialLineupCoverageAnalyzer.PlayerCoverage::providerPlayerId)
            .toList();
        if (result.size() != expectedProviderIds.size()
            || !result.keySet().equals(new java.util.LinkedHashSet<>(expectedProviderIds))) {
            throw new IllegalStateException("Provider score identity set moved after readiness check");
        }
        return Map.copyOf(result);
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
        HistoricalScoringLaneSelector.Lane scoringLane,
        LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState productionState,
        String productionId,
        LocalDate productionCoverageAsOf,
        String providerPointsEvidenceId,
        String scoringPolicyId,
        BigDecimal fantasyPoints) {
        public PlayerScoreEvidence {
            requireText(providerPlayerId, "providerPlayerId");
            requireText(playerId, "playerId");
            Objects.requireNonNull(eligibilityObservationAsOf, "eligibilityObservationAsOf must not be null");
            providerFantasyPositions = List.copyOf(Objects.requireNonNull(
                providerFantasyPositions, "providerFantasyPositions must not be null"));
            Objects.requireNonNull(scoringLane, "scoringLane must not be null");
            Objects.requireNonNull(productionState, "productionState must not be null");
            Objects.requireNonNull(fantasyPoints, "fantasyPoints must not be null");
            if (productionId != null) requireText(productionId, "productionId");
            if (providerPointsEvidenceId != null) {
                providerPointsEvidenceId = requireText(providerPointsEvidenceId, "providerPointsEvidenceId");
            }
            if (scoringPolicyId != null) requireText(scoringPolicyId, "scoringPolicyId");

            if (scoringLane == HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE) {
                if (productionState != LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.NOT_EVALUATED
                    || productionId != null || productionCoverageAsOf != null
                    || providerPointsEvidenceId == null
                    || !HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID.equals(scoringPolicyId)) {
                    throw new IllegalArgumentException(
                        "provider-native player score requires only provider scoring provenance");
                }
            } else if (productionState == LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.OBSERVED) {
                Objects.requireNonNull(productionCoverageAsOf, "productionCoverageAsOf must not be null");
                requireText(productionId, "productionId");
                if (providerPointsEvidenceId != null
                    || !CoveredProductionScoringPolicy.POLICY_ID.equals(scoringPolicyId)) {
                    throw new IllegalArgumentException(
                        "observed nflverse score requires covered-production policy and no provider evidence");
                }
            } else if (productionState
                == LeagueTeamWeekPotentialLineupCoverageAnalyzer.ProductionState.IDENTITY_COVERED_ZERO) {
                Objects.requireNonNull(productionCoverageAsOf, "productionCoverageAsOf must not be null");
                if (productionId != null || providerPointsEvidenceId != null || scoringPolicyId != null
                    || fantasyPoints.compareTo(BigDecimal.ZERO) != 0) {
                    throw new IllegalArgumentException(
                        "identity-covered zero must have no production/scoring artifact and exactly zero points");
                }
            } else {
                throw new IllegalArgumentException("PlayerScoreEvidence requires a ready scoring state");
            }
        }
    }

    public record PotentialLineupReport(
        String policyId,
        String coveragePolicyId,
        String metricScope,
        String scoringLaneSelectionPolicyId,
        HistoricalScoringLaneSelector.Lane scoringLane,
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
        LocalDate providerPointsAsOf,
        String providerPointsSourceSurface,
        String providerLeagueId,
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
            if (!HistoricalScoringLaneSelector.POLICY_ID.equals(scoringLaneSelectionPolicyId)) {
                throw new IllegalArgumentException("unexpected scoringLaneSelectionPolicyId");
            }
            Objects.requireNonNull(scoringLane, "scoringLane must not be null");
            String expectedScoringPolicy = scoringLane == HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE
                ? HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID
                : CoveredProductionScoringPolicy.POLICY_ID;
            if (!expectedScoringPolicy.equals(scoringPolicyId)) {
                throw new IllegalArgumentException("scoring policy does not match selected lane");
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
            playerScores = List.copyOf(Objects.requireNonNull(playerScores, "playerScores must not be null"));
            Objects.requireNonNull(lineup, "lineup must not be null");

            if (scoringLane == HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT) {
                Objects.requireNonNull(productionCoverageAsOf, "productionCoverageAsOf must not be null");
                Objects.requireNonNull(productionSourceUri, "productionSourceUri must not be null");
                if (providerPointsAsOf != null || providerPointsSourceSurface != null || providerLeagueId != null
                    || playerScores.stream().anyMatch(score -> score.providerPointsEvidenceId() != null)) {
                    throw new IllegalArgumentException("nflverse report cannot contain provider scoring provenance");
                }
            } else {
                Objects.requireNonNull(providerPointsAsOf, "providerPointsAsOf must not be null");
                requireText(providerPointsSourceSurface, "providerPointsSourceSurface");
                requireText(providerLeagueId, "providerLeagueId");
                if (productionCoverageAsOf != null || productionSourceUri != null
                    || playerScores.stream().anyMatch(score -> score.providerPointsEvidenceId() == null)) {
                    throw new IllegalArgumentException("provider-native report requires only provider scoring provenance");
                }
            }
        }
    }
}
