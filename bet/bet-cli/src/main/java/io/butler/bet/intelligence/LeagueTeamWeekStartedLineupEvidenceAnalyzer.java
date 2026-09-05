package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Scores the exact ordered Sleeper starter snapshot for one team-week under the same governed,
 * dated evidence boundary used by potential-lineup scoring.
 *
 * <p>This artifact does not compare the started lineup with the potential lineup, rank managers,
 * or infer intent. Sleeper's literal {@code "0"} starter sentinel is preserved as an explicit
 * empty starting slot and never converted into player production.</p>
 */
public final class LeagueTeamWeekStartedLineupEvidenceAnalyzer {
    public static final String POLICY_ID =
        "team-week-started-lineup-evidence-v1-exact-ordered-starters-zero-is-empty-fail-closed";
    public static final String METRIC_SCOPE =
        "RETROSPECTIVE_STARTED_LINEUP_USING_OBSERVED_PROVIDER_CONFIGURATION_NOT_PROVIDER_REPORTED_POINTS";

    private final Database database;

    public LeagueTeamWeekStartedLineupEvidenceAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public StartedLineupReport analyze(String leagueId, String teamId, int season, int week)
        throws SQLException {
        var scoredRoster = new LeagueTeamWeekPotentialLineupAnalyzer(database)
            .analyze(leagueId, teamId, season, week);

        var configuration = new LeagueConfigurationObservationRepository(database)
            .findLatestForSeason(scoredRoster.leagueId(), season,
                LeagueTeamWeekPotentialLineupCoverageAnalyzer.SLEEPER_SOURCE)
            .orElseThrow(() -> new IllegalStateException(
                "Started lineup unavailable: league configuration moved after scoring evidence"));
        if (!configuration.asOfDate().equals(scoredRoster.leagueConfigurationAsOf())) {
            throw new IllegalStateException(
                "Started lineup unavailable: league configuration moved after scoring evidence");
        }

        var rosterEvidence = new TeamWeekRosterEvidenceRepository(database)
            .findLatest(scoredRoster.teamId(), season, week,
                LeagueTeamWeekPotentialLineupCoverageAnalyzer.SLEEPER_SOURCE)
            .orElseThrow(() -> new IllegalStateException(
                "Started lineup unavailable: team-week roster evidence moved after scoring evidence"));
        if (!rosterEvidence.asOfDate().equals(scoredRoster.rosterEvidenceAsOf())) {
            throw new IllegalStateException(
                "Started lineup unavailable: team-week roster evidence moved after scoring evidence");
        }

        LineupSlotEligibilityPolicy eligibilityPolicy = new LineupSlotEligibilityPolicy();
        List<String> startingSlots = configuration.lineupSlots().stream()
            .filter(slot -> eligibilityPolicy.ruleFor(slot).state()
                == LineupSlotEligibilityPolicy.SlotState.STARTING_SUPPORTED)
            .toList();

        if (rosterEvidence.providerStarterIds().size() != startingSlots.size()) {
            throw new IllegalStateException(
                "Started lineup unavailable: ordered Sleeper starter count "
                    + rosterEvidence.providerStarterIds().size()
                    + " does not match supported starting-slot count " + startingSlots.size());
        }

        Map<String, LeagueTeamWeekPotentialLineupAnalyzer.PlayerScoreEvidence> scoresByProviderId =
            new HashMap<>();
        for (var score : scoredRoster.playerScores()) {
            if (scoresByProviderId.put(score.providerPlayerId(), score) != null) {
                throw new IllegalStateException(
                    "Started lineup unavailable: duplicate scored provider player id "
                        + score.providerPlayerId());
            }
        }

        Set<String> rosterProviderIds = Set.copyOf(rosterEvidence.providerPlayerIds());
        Set<String> seenStarterIds = new HashSet<>();
        List<StartedSlotEvidence> slots = new java.util.ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int filled = 0;

        for (int ordinal = 0; ordinal < startingSlots.size(); ordinal++) {
            String slot = startingSlots.get(ordinal);
            String providerStarterId = rosterEvidence.providerStarterIds().get(ordinal);
            if ("0".equals(providerStarterId)) {
                slots.add(StartedSlotEvidence.empty(ordinal, slot));
                continue;
            }
            if (!seenStarterIds.add(providerStarterId)) {
                throw new IllegalStateException(
                    "Started lineup unavailable: duplicate starter player " + providerStarterId);
            }
            if (!rosterProviderIds.contains(providerStarterId)) {
                throw new IllegalStateException(
                    "Started lineup unavailable: starter " + providerStarterId
                        + " is not present in the exact team-week roster snapshot");
            }

            var score = scoresByProviderId.get(providerStarterId);
            if (score == null) {
                throw new IllegalStateException(
                    "Started lineup unavailable: no governed scoring evidence for starter "
                        + providerStarterId);
            }
            if (!eligibilityPolicy.isPlayerEligible(slot, score.providerFantasyPositions())) {
                throw new IllegalStateException(
                    "Started lineup unavailable: starter " + providerStarterId
                        + " is not eligible for ordered slot " + slot);
            }

            slots.add(StartedSlotEvidence.filled(ordinal, slot, score));
            total = total.add(score.fantasyPoints());
            filled++;
        }

        return new StartedLineupReport(
            POLICY_ID,
            METRIC_SCOPE,
            scoredRoster.coveragePolicyId(),
            CoveredProductionScoringPolicy.POLICY_ID,
            LineupSlotEligibilityPolicy.POLICY_ID,
            scoredRoster.leagueId(),
            scoredRoster.teamId(),
            season,
            week,
            scoredRoster.leagueConfigurationAsOf(),
            scoredRoster.rosterEvidenceAsOf(),
            scoredRoster.productionCoverageAsOf(),
            scoredRoster.productionSourceUri(),
            List.copyOf(slots),
            filled,
            startingSlots.size(),
            filled == startingSlots.size(),
            total);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public enum StartedSlotState {
        FILLED,
        EMPTY
    }

    public record StartedSlotEvidence(
        int ordinal,
        String slot,
        StartedSlotState state,
        String providerStarterId,
        String playerId,
        LeagueTeamWeekPotentialLineupAnalyzer.PlayerScoreEvidence scoreEvidence) {

        public StartedSlotEvidence {
            if (ordinal < 0) throw new IllegalArgumentException("ordinal must not be negative");
            requireText(slot, "slot");
            Objects.requireNonNull(state, "state must not be null");
            requireText(providerStarterId, "providerStarterId");
            if (state == StartedSlotState.EMPTY) {
                if (!"0".equals(providerStarterId) || playerId != null || scoreEvidence != null) {
                    throw new IllegalArgumentException(
                        "empty started slot must preserve provider starter id 0 without player scoring evidence");
                }
            } else {
                requireText(playerId, "playerId");
                Objects.requireNonNull(scoreEvidence, "filled slot scoreEvidence must not be null");
                if (!providerStarterId.equals(scoreEvidence.providerPlayerId())
                    || !playerId.equals(scoreEvidence.playerId())) {
                    throw new IllegalArgumentException(
                        "filled started slot must match nested provider/player scoring evidence");
                }
            }
        }

        public static StartedSlotEvidence empty(int ordinal, String slot) {
            return new StartedSlotEvidence(ordinal, slot, StartedSlotState.EMPTY, "0", null, null);
        }

        public static StartedSlotEvidence filled(
            int ordinal,
            String slot,
            LeagueTeamWeekPotentialLineupAnalyzer.PlayerScoreEvidence scoreEvidence) {
            Objects.requireNonNull(scoreEvidence, "scoreEvidence must not be null");
            return new StartedSlotEvidence(
                ordinal,
                slot,
                StartedSlotState.FILLED,
                scoreEvidence.providerPlayerId(),
                scoreEvidence.playerId(),
                scoreEvidence);
        }

        public BigDecimal fantasyPoints() {
            return state == StartedSlotState.EMPTY ? BigDecimal.ZERO : scoreEvidence.fantasyPoints();
        }
    }

    public record StartedLineupReport(
        String policyId,
        String metricScope,
        String coveragePolicyId,
        String scoringPolicyId,
        String eligibilityPolicyId,
        String leagueId,
        String teamId,
        int season,
        int week,
        LocalDate leagueConfigurationAsOf,
        LocalDate rosterEvidenceAsOf,
        LocalDate productionCoverageAsOf,
        URI productionSourceUri,
        List<StartedSlotEvidence> slots,
        int filledSlots,
        int requiredSlots,
        boolean complete,
        BigDecimal totalStartedPoints) {

        public StartedLineupReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            if (!LeagueTeamWeekPotentialLineupCoverageAnalyzer.POLICY_ID.equals(coveragePolicyId)) {
                throw new IllegalArgumentException("unexpected coveragePolicyId");
            }
            if (!CoveredProductionScoringPolicy.POLICY_ID.equals(scoringPolicyId)) {
                throw new IllegalArgumentException("unexpected scoringPolicyId");
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
            slots = List.copyOf(Objects.requireNonNull(slots, "slots must not be null"));
            Objects.requireNonNull(totalStartedPoints, "totalStartedPoints must not be null");
            if (requiredSlots != slots.size() || filledSlots < 0 || filledSlots > requiredSlots) {
                throw new IllegalArgumentException("started slot counts must match slot evidence");
            }
            int observedFilled = 0;
            BigDecimal observedTotal = BigDecimal.ZERO;
            for (int ordinal = 0; ordinal < slots.size(); ordinal++) {
                StartedSlotEvidence slot = slots.get(ordinal);
                if (slot.ordinal() != ordinal) {
                    throw new IllegalArgumentException("started slot evidence must preserve ordinal order");
                }
                if (slot.state() == StartedSlotState.FILLED) observedFilled++;
                observedTotal = observedTotal.add(slot.fantasyPoints());
            }
            if (observedFilled != filledSlots) {
                throw new IllegalArgumentException("filledSlots must equal filled slot evidence count");
            }
            if (complete != (filledSlots == requiredSlots)) {
                throw new IllegalArgumentException("complete must reflect filled versus required slots");
            }
            if (observedTotal.compareTo(totalStartedPoints) != 0) {
                throw new IllegalArgumentException("totalStartedPoints must equal started slot evidence total");
            }
        }
    }
}
