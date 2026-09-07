package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.PlayerFantasyPositionObservationRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerWeekProductionCoverageRepository;
import io.butler.bet.data.PlayerWeekProductionRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.LeagueConfigurationObservation;
import io.butler.bet.domain.PlayerFantasyPositionObservation;
import io.butler.bet.domain.PlayerWeekProductionCoverage;
import io.butler.bet.domain.TeamWeekRosterEvidence;
import io.butler.bet.sleeper.SleeperProviderNativeSeasonScoringAudit;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Determines whether one team-week has sufficient persisted evidence to calculate Butler's
 * retrospective potential lineup. This class does not calculate fantasy points or solve a lineup.
 */
public final class LeagueTeamWeekPotentialLineupCoverageAnalyzer {
    public static final String POLICY_ID =
        "team-week-potential-lineup-coverage-v2-deterministic-historical-scoring-lane-fail-closed";
    public static final String METRIC_SCOPE =
        "RETROSPECTIVE_POTENTIAL_USING_OBSERVED_PROVIDER_CONFIGURATION_NOT_HISTORICAL_STARTABILITY";
    public static final String SLEEPER_SOURCE = "sleeper";
    public static final String PRODUCTION_SOURCE = NflversePlayerWeekProductionImporter.SOURCE;

    private final Database database;

    public LeagueTeamWeekPotentialLineupCoverageAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public CoverageReport analyze(String leagueId, String teamId, int season, int week) throws SQLException {
        HistoricalScoringLaneSelector.Selection scoringLane =
            new HistoricalScoringLaneSelector(database).select(leagueId, season);
        return analyze(leagueId, teamId, season, week, scoringLane);
    }

    CoverageReport analyze(
        String leagueId,
        String teamId,
        int season,
        int week,
        HistoricalScoringLaneSelector.Selection scoringLane) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedTeamId = requireText(teamId, "teamId");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        if (week <= 0) throw new IllegalArgumentException("week must be positive");
        Objects.requireNonNull(scoringLane, "scoringLane must not be null");
        if (!normalizedLeagueId.equals(scoringLane.leagueId()) || season != scoringLane.season()) {
            throw new IllegalArgumentException("scoring lane identity must match requested league-season");
        }

        var team = new TeamRepository(database).findById(normalizedTeamId)
            .orElseThrow(() -> new IllegalArgumentException("Team not found: " + normalizedTeamId));
        if (!normalizedLeagueId.equals(team.getLeagueId())) {
            throw new IllegalArgumentException(
                "Team " + normalizedTeamId + " does not belong to league " + normalizedLeagueId);
        }

        List<String> blockers = new ArrayList<>();
        if (!scoringLane.ready()) blockers.addAll(scoringLane.blockers());

        LeagueConfigurationObservation configuration = new LeagueConfigurationObservationRepository(database)
            .findLatestForSeason(normalizedLeagueId, season, SLEEPER_SOURCE)
            .orElse(null);
        if (configuration == null) {
            blockers.add("No Sleeper league configuration observation for requested season " + season);
        } else {
            validateConfiguration(
                configuration,
                blockers,
                scoringLane.lane() == HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT);
        }

        TeamWeekRosterEvidence rosterEvidence = new TeamWeekRosterEvidenceRepository(database)
            .findLatest(normalizedTeamId, season, week, SLEEPER_SOURCE)
            .orElse(null);
        if (rosterEvidence == null) {
            blockers.add("No persisted Sleeper team-week roster evidence");
        } else if (!normalizedLeagueId.equals(rosterEvidence.leagueId())) {
            blockers.add("Team-week roster evidence belongs to a different league");
        }

        PlayerWeekProductionCoverage productionCoverage = null;
        SleeperProviderNativeSeasonScoringAudit.TeamWeekAudit providerTeamWeek = null;
        if (scoringLane.lane() == HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT) {
            productionCoverage = new PlayerWeekProductionCoverageRepository(database)
                .findLatest(season, week, PRODUCTION_SOURCE)
                .orElse(null);
            if (productionCoverage == null) {
                blockers.add("No persisted nflverse week production coverage");
            }
        } else if (scoringLane.ready()) {
            providerTeamWeek = scoringLane.providerTeamWeek(normalizedTeamId, week);
            if (providerTeamWeek == null) {
                blockers.add("Validated provider-native season lane contains no requested team-week");
            } else if (providerTeamWeek.state() != SleeperProviderNativeSeasonScoringAudit.TeamWeekState.READY) {
                blockers.add("Validated provider-native season lane contains a non-ready requested team-week");
            }
        }

        List<PlayerCoverage> playerCoverage = rosterEvidence == null
            ? List.of()
            : analyzePlayers(
                rosterEvidence,
                scoringLane,
                providerTeamWeek,
                productionCoverage,
                season,
                week,
                blockers);

        CoverageState state = blockers.isEmpty() ? CoverageState.READY : CoverageState.BLOCKED;
        return new CoverageReport(
            POLICY_ID,
            METRIC_SCOPE,
            HistoricalScoringLaneSelector.POLICY_ID,
            normalizedLeagueId,
            normalizedTeamId,
            season,
            week,
            state,
            scoringLane.lane(),
            configuration == null ? null : configuration.asOfDate(),
            rosterEvidence == null ? null : rosterEvidence.asOfDate(),
            productionCoverage == null ? null : productionCoverage.asOfDate(),
            productionCoverage == null ? null : productionCoverage.sourceUri(),
            scoringLane.providerPointsAsOf(),
            scoringLane.providerSourceSurface(),
            scoringLane.providerLeagueId(),
            playerCoverage,
            List.copyOf(blockers));
    }

    private static void validateConfiguration(
        LeagueConfigurationObservation configuration,
        List<String> blockers,
        boolean requireNflverseScoringRepresentability) {
        if (configuration.lineupSlots().isEmpty()) {
            blockers.add("Observed league configuration contains no lineup slots");
        } else {
            LineupSlotEligibilityPolicy slotPolicy = new LineupSlotEligibilityPolicy();
            int supportedStarters = 0;
            for (String slot : configuration.lineupSlots()) {
                var rule = slotPolicy.ruleFor(slot);
                if (rule.state() == LineupSlotEligibilityPolicy.SlotState.UNSUPPORTED) {
                    blockers.add("Unsupported observed lineup slot: " + slot);
                } else if (rule.state() == LineupSlotEligibilityPolicy.SlotState.STARTING_SUPPORTED) {
                    supportedStarters++;
                }
            }
            if (supportedStarters == 0) {
                blockers.add("Observed league configuration contains no supported starting slots");
            }
        }

        if (configuration.scoringSettings().isEmpty()) {
            blockers.add("Observed league configuration contains no scoring settings");
        } else if (requireNflverseScoringRepresentability) {
            configuration.scoringSettings().forEach((statKey, points) -> {
                if (Double.compare(points, 0.0d) != 0 && SupportedScoringStat.find(statKey) == null) {
                    blockers.add("Unsupported nonzero observed scoring rule: " + statKey);
                }
            });
        }
    }

    private List<PlayerCoverage> analyzePlayers(
        TeamWeekRosterEvidence rosterEvidence,
        HistoricalScoringLaneSelector.Selection scoringLane,
        SleeperProviderNativeSeasonScoringAudit.TeamWeekAudit providerTeamWeek,
        PlayerWeekProductionCoverage productionCoverage,
        int season,
        int week,
        List<String> reportBlockers) throws SQLException {
        PlayerRepository players = new PlayerRepository(database);
        PlayerFantasyPositionObservationRepository positions =
            new PlayerFantasyPositionObservationRepository(database);
        PlayerWeekProductionRepository production = new PlayerWeekProductionRepository(database);
        Map<String, SleeperProviderNativeSeasonScoringAudit.PlayerScoreEvidence> providerScores = new HashMap<>();
        if (providerTeamWeek != null) {
            for (var score : providerTeamWeek.scores()) {
                providerScores.put(score.providerPlayerId(), score);
            }
        }

        List<PlayerCoverage> result = new ArrayList<>();
        Set<String> seenProviderIds = new HashSet<>();
        for (String providerPlayerId : rosterEvidence.providerPlayerIds()) {
            List<String> playerBlockers = new ArrayList<>();
            var providerScore = providerScores.get(providerPlayerId);
            BigDecimal providerFantasyPoints = providerScore == null ? null : providerScore.points();
            String providerPointsEvidenceId = providerScore == null ? null : providerScore.evidenceId();

            if (!seenProviderIds.add(providerPlayerId)) {
                String blocker = "Duplicate provider player id in team-week roster evidence: " + providerPlayerId;
                reportBlockers.add(blocker);
                playerBlockers.add(blocker);
                result.add(new PlayerCoverage(
                    providerPlayerId,
                    null,
                    null,
                    List.of(),
                    ProductionState.NOT_EVALUATED,
                    null,
                    providerFantasyPoints,
                    providerPointsEvidenceId,
                    List.copyOf(playerBlockers)));
                continue;
            }

            if (scoringLane.lane() == HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE
                && scoringLane.ready() && providerScore == null) {
                String blocker = "Validated provider-native lane has no score evidence for Sleeper player id "
                    + providerPlayerId;
                reportBlockers.add(blocker);
                playerBlockers.add(blocker);
            }

            var player = players.findByExternalId(providerPlayerId).orElse(null);
            if (player == null) {
                String blocker = "No Butler player mapping for Sleeper player id " + providerPlayerId;
                reportBlockers.add(blocker);
                playerBlockers.add(blocker);
                result.add(new PlayerCoverage(
                    providerPlayerId,
                    null,
                    null,
                    List.of(),
                    ProductionState.NOT_EVALUATED,
                    null,
                    providerFantasyPoints,
                    providerPointsEvidenceId,
                    List.copyOf(playerBlockers)));
                continue;
            }

            PlayerFantasyPositionObservation eligibility = positions
                .findLatest(player.getId(), SLEEPER_SOURCE).orElse(null);
            if (eligibility == null) {
                String blocker = "No Sleeper fantasy-position observation for player " + player.getId();
                reportBlockers.add(blocker);
                playerBlockers.add(blocker);
            }

            ProductionState productionState = ProductionState.NOT_EVALUATED;
            String productionId = null;
            if (scoringLane.lane() == HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT) {
                if (productionCoverage == null) {
                    productionState = ProductionState.NO_WEEK_COVERAGE;
                    String blocker = "No week production coverage for player " + player.getId();
                    reportBlockers.add(blocker);
                    playerBlockers.add(blocker);
                } else {
                    var observedProduction = production.findAtAsOf(
                        player.getId(), season, week, PRODUCTION_SOURCE, productionCoverage.asOfDate())
                        .orElse(null);
                    if (observedProduction != null) {
                        productionState = ProductionState.OBSERVED;
                        productionId = observedProduction.id();
                    } else if (productionCoverage.coversIdentity(player.getId())) {
                        productionState = ProductionState.IDENTITY_COVERED_ZERO;
                    } else {
                        productionState = ProductionState.IDENTITY_NOT_COVERED;
                        String blocker = "No exact production row or import-time identity coverage for player "
                            + player.getId();
                        reportBlockers.add(blocker);
                        playerBlockers.add(blocker);
                    }
                }
            }

            result.add(new PlayerCoverage(
                providerPlayerId,
                player.getId(),
                eligibility == null ? null : eligibility.asOfDate(),
                eligibility == null ? List.of() : eligibility.providerFantasyPositions(),
                productionState,
                productionId,
                providerFantasyPoints,
                providerPointsEvidenceId,
                List.copyOf(playerBlockers)));
        }
        return List.copyOf(result);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum CoverageState {
        READY,
        BLOCKED
    }

    public enum ProductionState {
        OBSERVED,
        IDENTITY_COVERED_ZERO,
        IDENTITY_NOT_COVERED,
        NO_WEEK_COVERAGE,
        NOT_EVALUATED
    }

    public record PlayerCoverage(
        String providerPlayerId,
        String playerId,
        LocalDate eligibilityObservationAsOf,
        List<String> providerFantasyPositions,
        ProductionState productionState,
        String productionId,
        BigDecimal providerFantasyPoints,
        String providerPointsEvidenceId,
        List<String> blockers) {
        public PlayerCoverage {
            requireText(providerPlayerId, "providerPlayerId");
            if (playerId != null) requireText(playerId, "playerId");
            providerFantasyPositions = List.copyOf(Objects.requireNonNull(
                providerFantasyPositions, "providerFantasyPositions must not be null"));
            Objects.requireNonNull(productionState, "productionState must not be null");
            if (productionId != null) requireText(productionId, "productionId");
            if (providerPointsEvidenceId != null) {
                providerPointsEvidenceId = requireText(providerPointsEvidenceId, "providerPointsEvidenceId");
            }
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers must not be null"));
            if (productionState == ProductionState.OBSERVED && productionId == null) {
                throw new IllegalArgumentException("observed production must include productionId");
            }
            if (productionState != ProductionState.OBSERVED && productionId != null) {
                throw new IllegalArgumentException("only observed production may include productionId");
            }
            if ((providerFantasyPoints == null) != (providerPointsEvidenceId == null)) {
                throw new IllegalArgumentException(
                    "provider fantasy points and provider evidence id must be present together");
            }
        }
    }

    public record CoverageReport(
        String policyId,
        String metricScope,
        String scoringLaneSelectionPolicyId,
        String leagueId,
        String teamId,
        int season,
        int week,
        CoverageState state,
        HistoricalScoringLaneSelector.Lane scoringLane,
        LocalDate leagueConfigurationAsOf,
        LocalDate rosterEvidenceAsOf,
        LocalDate productionCoverageAsOf,
        URI productionSourceUri,
        LocalDate providerPointsAsOf,
        String providerPointsSourceSurface,
        String providerLeagueId,
        List<PlayerCoverage> players,
        List<String> blockers) {
        public CoverageReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            if (!HistoricalScoringLaneSelector.POLICY_ID.equals(scoringLaneSelectionPolicyId)) {
                throw new IllegalArgumentException("unexpected scoringLaneSelectionPolicyId");
            }
            requireText(leagueId, "leagueId");
            requireText(teamId, "teamId");
            if (season < 1999 || season > 2100) {
                throw new IllegalArgumentException("season must be between 1999 and 2100");
            }
            if (week <= 0) throw new IllegalArgumentException("week must be positive");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(scoringLane, "scoringLane must not be null");
            if (providerPointsSourceSurface != null) {
                providerPointsSourceSurface = requireText(providerPointsSourceSurface, "providerPointsSourceSurface");
            }
            if (providerLeagueId != null) providerLeagueId = requireText(providerLeagueId, "providerLeagueId");
            players = List.copyOf(Objects.requireNonNull(players, "players must not be null"));
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers must not be null"));
            if ((state == CoverageState.READY) != blockers.isEmpty()) {
                throw new IllegalArgumentException("READY coverage must have no blockers and BLOCKED must have blockers");
            }
            if (state == CoverageState.READY) {
                if (leagueConfigurationAsOf == null || rosterEvidenceAsOf == null) {
                    throw new IllegalArgumentException("READY coverage must include configuration and roster provenance");
                }
                if (scoringLane == HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT) {
                    if (productionCoverageAsOf == null || productionSourceUri == null
                        || providerPointsAsOf != null || providerPointsSourceSurface != null || providerLeagueId != null) {
                        throw new IllegalArgumentException(
                            "READY nflverse coverage requires only nflverse scoring provenance");
                    }
                    if (players.stream().anyMatch(player -> player.providerFantasyPoints() != null)) {
                        throw new IllegalArgumentException("READY nflverse coverage cannot contain provider scores");
                    }
                } else {
                    if (providerPointsAsOf == null || providerPointsSourceSurface == null || providerLeagueId == null
                        || productionCoverageAsOf != null || productionSourceUri != null) {
                        throw new IllegalArgumentException(
                            "READY provider-native coverage requires only provider scoring provenance");
                    }
                    if (players.stream().anyMatch(player -> player.providerFantasyPoints() == null
                        || player.providerPointsEvidenceId() == null
                        || player.productionState() != ProductionState.NOT_EVALUATED)) {
                        throw new IllegalArgumentException(
                            "READY provider-native coverage requires exact provider evidence for every roster identity");
                    }
                }
            }
        }

        public boolean ready() {
            return state == CoverageState.READY;
        }
    }
}
