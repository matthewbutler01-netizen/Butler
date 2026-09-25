package io.butler.bet.intelligence;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * BF-800/BF-825 read-only current-lineup optimizer.
 *
 * <p>This class does not fetch evidence and does not mutate a roster. It consumes an explicit
 * ordered provider lineup, explicit active-roster eligibility, explicit weekly projections, and
 * (when BF-825 has proved it upstream) exact player ids that are unavailable to play. Missing
 * projection evidence still fails closed for every startable player. Reserve, taxi, and explicitly
 * unavailable players are never candidates.</p>
 */
public final class AutoFillLineupOptimizer {
    public static final String POLICY_ID =
        "autofill-lineup-v1-active-roster-complete-projection-optimal-read-only";

    public Recommendation optimize(
        List<String> providerLineupSlots,
        List<RosterPlayer> rosterPlayers,
        Map<String, BigDecimal> weeklyProjectionByPlayerId) {
        return optimize(providerLineupSlots, rosterPlayers, weeklyProjectionByPlayerId, Set.of(), Set.of());
    }

    public Recommendation optimize(
        List<String> providerLineupSlots,
        List<RosterPlayer> rosterPlayers,
        Map<String, BigDecimal> weeklyProjectionByPlayerId,
        Set<String> explicitlyUnavailablePlayerIds) {
        return optimize(
            providerLineupSlots,
            rosterPlayers,
            weeklyProjectionByPlayerId,
            explicitlyUnavailablePlayerIds,
            Set.of());
    }

    /**
     * BF-902 partial-coverage path.
     *
     * <p>Players in {@code projectionHoldPlayerIds} have exact live roster identity but no
     * scoreable current-week projection and are not proven unavailable. Butler therefore leaves
     * held starters in their existing starter slot and excludes held bench players from promotion
     * candidates. No zero projection or inferred availability is synthesized. Remaining scoreable
     * slots are still optimized.</p>
     */
    public Recommendation optimize(
        List<String> providerLineupSlots,
        List<RosterPlayer> rosterPlayers,
        Map<String, BigDecimal> weeklyProjectionByPlayerId,
        Set<String> explicitlyUnavailablePlayerIds,
        Set<String> projectionHoldPlayerIds) {
        Objects.requireNonNull(providerLineupSlots, "providerLineupSlots must not be null");
        Objects.requireNonNull(rosterPlayers, "rosterPlayers must not be null");
        Objects.requireNonNull(weeklyProjectionByPlayerId, "weeklyProjectionByPlayerId must not be null");
        Objects.requireNonNull(explicitlyUnavailablePlayerIds, "explicitlyUnavailablePlayerIds must not be null");
        Objects.requireNonNull(projectionHoldPlayerIds, "projectionHoldPlayerIds must not be null");

        Set<String> ids = new HashSet<>();
        for (RosterPlayer player : rosterPlayers) {
            if (!ids.add(player.playerId())) {
                throw new IllegalArgumentException("duplicate roster playerId: " + player.playerId());
            }
        }

        List<RosterPlayer> active = rosterPlayers.stream()
            .filter(player -> player.rosterSlot() == RosterSlot.STARTER || player.rosterSlot() == RosterSlot.BENCH)
            .sorted(Comparator.comparing(RosterPlayer::playerId))
            .toList();
        if (active.isEmpty()) {
            return Recommendation.unavailable("No active starter/bench players are available for AutoFill.");
        }

        Set<String> activeIds = new HashSet<>();
        for (RosterPlayer player : active) activeIds.add(player.playerId());
        for (String unavailableId : explicitlyUnavailablePlayerIds) {
            String normalizedId = requireText(unavailableId, "explicitlyUnavailablePlayerId");
            if (!activeIds.contains(normalizedId)) {
                throw new IllegalArgumentException(
                    "explicitly unavailable player must be an exact active roster playerId: " + normalizedId);
            }
        }
        for (String holdId : projectionHoldPlayerIds) {
            String normalizedId = requireText(holdId, "projectionHoldPlayerId");
            if (!activeIds.contains(normalizedId)) {
                throw new IllegalArgumentException(
                    "projection hold player must be an exact active roster playerId: " + normalizedId);
            }
            if (explicitlyUnavailablePlayerIds.contains(normalizedId)) {
                throw new IllegalArgumentException(
                    "projection hold player cannot also be explicitly unavailable: " + normalizedId);
            }
        }

        List<RosterPlayer> candidates = active.stream()
            .filter(player -> !explicitlyUnavailablePlayerIds.contains(player.playerId()))
            .filter(player -> !projectionHoldPlayerIds.contains(player.playerId()))
            .toList();
        if (candidates.isEmpty()) {
            return Recommendation.unavailable(
                "A complete legal starting lineup cannot be built because every active roster player is explicitly unavailable.");
        }

        List<String> missing = candidates.stream()
            .filter(player -> !weeklyProjectionByPlayerId.containsKey(player.playerId())
                || weeklyProjectionByPlayerId.get(player.playerId()) == null)
            .map(player -> player.displayName() + " [" + player.playerId() + "]")
            .toList();
        if (!missing.isEmpty()) {
            return Recommendation.unavailable(
                "Weekly projection evidence is incomplete for active roster players: " + String.join(", ", missing));
        }

        List<RosterPlayer> currentStarters = rosterPlayers.stream()
            .filter(player -> player.rosterSlot() == RosterSlot.STARTER)
            .sorted(Comparator.comparingInt(player -> Objects.requireNonNull(
                player.starterOrdinal(), "starterOrdinal must be present for STARTER")))
            .toList();
        if (currentStarters.size() != providerLineupSlots.size()) {
            throw new IllegalStateException("Current starter count does not match governed starting-slot count");
        }
        for (int index = 0; index < currentStarters.size(); index++) {
            if (currentStarters.get(index).starterOrdinal() != index) {
                throw new IllegalStateException("Current starter ordinals must be contiguous and ordered");
            }
        }

        List<Integer> openOrdinals = new ArrayList<>();
        List<String> openLineupSlots = new ArrayList<>();
        List<RosterPlayer> currentOpenStarters = new ArrayList<>();
        for (RosterPlayer starter : currentStarters) {
            int ordinal = Objects.requireNonNull(starter.starterOrdinal());
            if (projectionHoldPlayerIds.contains(starter.playerId())) {
                continue;
            }
            openOrdinals.add(ordinal);
            openLineupSlots.add(providerLineupSlots.get(ordinal));
            currentOpenStarters.add(starter);
        }
        if (openLineupSlots.isEmpty()) {
            return Recommendation.unavailable(
                "No scoreable starting slots remain after preserving projection-held starters.");
        }

        List<OptimalLegalLineupSolver.ScoredPlayerCandidate> scoredCandidates = new ArrayList<>();
        for (RosterPlayer player : candidates) {
            scoredCandidates.add(new OptimalLegalLineupSolver.ScoredPlayerCandidate(
                player.playerId(), player.providerFantasyPositions(), weeklyProjectionByPlayerId.get(player.playerId())));
        }

        OptimalLegalLineupSolver.LineupResult solved =
            new OptimalLegalLineupSolver().solve(openLineupSlots, scoredCandidates);
        if (!solved.complete()) {
            return Recommendation.unavailable(
                "A complete legal lineup cannot be built for the scoreable open slots from current eligibility evidence.");
        }
        if (currentOpenStarters.size() != solved.assignments().size()) {
            throw new IllegalStateException("Open starter count does not match solved open-slot count");
        }

        List<OptimalLegalLineupSolver.Assignment> normalizedAssignments =
            preserveCurrentOrderWithinEquivalentSlots(solved.assignments(), currentOpenStarters);

        Map<String, RosterPlayer> byId = new HashMap<>();
        for (RosterPlayer player : rosterPlayers) byId.put(player.playerId(), player);

        List<SlotRecommendation> assignments = new ArrayList<>();
        Set<String> recommendedStarterIds = new LinkedHashSet<>();
        Set<String> currentStarterIds = new LinkedHashSet<>();
        for (RosterPlayer starter : currentStarters) currentStarterIds.add(starter.playerId());
        for (RosterPlayer starter : currentStarters) {
            if (projectionHoldPlayerIds.contains(starter.playerId())) {
                recommendedStarterIds.add(starter.playerId());
            }
        }

        for (int index = 0; index < normalizedAssignments.size(); index++) {
            var assignment = normalizedAssignments.get(index);
            RosterPlayer current = currentOpenStarters.get(index);
            RosterPlayer recommended = byId.get(assignment.playerId());
            if (recommended == null) {
                throw new IllegalStateException("Solved lineup contains player absent from roster evidence: " + assignment.playerId());
            }
            recommendedStarterIds.add(recommended.playerId());
            BigDecimal currentProjectedPoints = weeklyProjectionByPlayerId.get(current.playerId());
            if (currentProjectedPoints == null
                && !explicitlyUnavailablePlayerIds.contains(current.playerId())) {
                throw new IllegalStateException(
                    "Scoreable current starter projection became unavailable during AutoFill: " + current.playerId());
            }
            BigDecimal recommendedProjectedPoints = assignment.fantasyPoints();
            BigDecimal projectedGain = currentProjectedPoints == null
                ? null
                : recommendedProjectedPoints.subtract(currentProjectedPoints);
            assignments.add(new SlotRecommendation(
                openOrdinals.get(index),
                assignment.slot(),
                current.playerId(),
                current.displayName(),
                recommended.playerId(),
                recommended.displayName(),
                currentProjectedPoints,
                recommendedProjectedPoints,
                projectedGain,
                !current.playerId().equals(recommended.playerId())));
        }

        List<RosterPlayer> movesToBench = currentStarters.stream()
            .filter(player -> !projectionHoldPlayerIds.contains(player.playerId()))
            .filter(player -> !recommendedStarterIds.contains(player.playerId()))
            .sorted(Comparator.comparing(RosterPlayer::playerId))
            .toList();
        List<RosterPlayer> promotions = candidates.stream()
            .filter(player -> recommendedStarterIds.contains(player.playerId())
                && !currentStarterIds.contains(player.playerId()))
            .sorted(Comparator.comparing(RosterPlayer::playerId))
            .toList();

        return Recommendation.ready(
            solved.policyId(), solved.eligibilityPolicyId(), solved.totalPoints(),
            assignments, movesToBench, promotions);
    }

    /**
     * Preserve the provider's existing player order inside repeated identical starting slots
     * whenever the solver selected the same starter set for that slot type. This removes
     * meaningless RB1/RB2 or WR1/WR2 permutations without changing legality or projected total.
     */
    private static List<OptimalLegalLineupSolver.Assignment> preserveCurrentOrderWithinEquivalentSlots(
        List<OptimalLegalLineupSolver.Assignment> solvedAssignments,
        List<RosterPlayer> currentOpenStarters) {
        if (solvedAssignments.size() != currentOpenStarters.size()) {
            throw new IllegalArgumentException("solved assignments and current starters must align");
        }

        List<OptimalLegalLineupSolver.Assignment> normalized = new ArrayList<>(solvedAssignments);
        Map<String, List<Integer>> indicesBySlot = new LinkedHashMap<>();
        for (int index = 0; index < solvedAssignments.size(); index++) {
            var assignment = solvedAssignments.get(index);
            if (!assignment.filled()) {
                throw new IllegalStateException("Equivalent-slot normalization requires a complete solved lineup");
            }
            indicesBySlot.computeIfAbsent(assignment.slot(), ignored -> new ArrayList<>()).add(index);
        }

        for (List<Integer> indices : indicesBySlot.values()) {
            if (indices.size() < 2) continue;

            Map<String, OptimalLegalLineupSolver.Assignment> byPlayerId = new LinkedHashMap<>();
            for (int index : indices) {
                var assignment = solvedAssignments.get(index);
                byPlayerId.put(assignment.playerId(), assignment);
            }

            Set<String> preservedPlayerIds = new LinkedHashSet<>();
            Set<Integer> preservedIndices = new HashSet<>();
            for (int index : indices) {
                String currentPlayerId = currentOpenStarters.get(index).playerId();
                var selected = byPlayerId.get(currentPlayerId);
                if (selected == null || !preservedPlayerIds.add(currentPlayerId)) continue;

                var slot = solvedAssignments.get(index);
                normalized.set(index, new OptimalLegalLineupSolver.Assignment(
                    slot.slotOrdinal(), slot.slot(), currentPlayerId, selected.fantasyPoints()));
                preservedIndices.add(index);
            }

            List<OptimalLegalLineupSolver.Assignment> remaining = new ArrayList<>();
            for (int index : indices) {
                var assignment = solvedAssignments.get(index);
                if (!preservedPlayerIds.contains(assignment.playerId())) remaining.add(assignment);
            }

            int remainingIndex = 0;
            for (int index : indices) {
                if (preservedIndices.contains(index)) continue;
                var slot = solvedAssignments.get(index);
                var selected = remaining.get(remainingIndex++);
                normalized.set(index, new OptimalLegalLineupSolver.Assignment(
                    slot.slotOrdinal(), slot.slot(), selected.playerId(), selected.fantasyPoints()));
            }
        }

        return List.copyOf(normalized);
    }

    public enum RosterSlot {
        STARTER,
        BENCH,
        RESERVE,
        TAXI
    }

    public record RosterPlayer(
        String playerId,
        String displayName,
        List<String> providerFantasyPositions,
        RosterSlot rosterSlot,
        Integer starterOrdinal,
        String currentLineupSlot) {
        public RosterPlayer {
            playerId = requireText(playerId, "playerId");
            displayName = requireText(displayName, "displayName");
            providerFantasyPositions = List.copyOf(Objects.requireNonNull(
                providerFantasyPositions, "providerFantasyPositions must not be null"));
            if (providerFantasyPositions.isEmpty()) {
                throw new IllegalArgumentException("providerFantasyPositions must not be empty");
            }
            for (String position : providerFantasyPositions) requireText(position, "providerFantasyPosition");
            Objects.requireNonNull(rosterSlot, "rosterSlot must not be null");
            if (rosterSlot == RosterSlot.STARTER) {
                if (starterOrdinal == null || starterOrdinal < 0) {
                    throw new IllegalArgumentException("STARTER requires a nonnegative starterOrdinal");
                }
                currentLineupSlot = requireText(currentLineupSlot, "currentLineupSlot");
            } else if (starterOrdinal != null || currentLineupSlot != null) {
                throw new IllegalArgumentException("Only STARTER may declare starterOrdinal/currentLineupSlot");
            }
        }
    }

    public record SlotRecommendation(
        int starterOrdinal,
        String slot,
        String currentPlayerId,
        String currentPlayerName,
        String recommendedPlayerId,
        String recommendedPlayerName,
        BigDecimal currentProjectedPoints,
        BigDecimal projectedPoints,
        BigDecimal projectedGain,
        boolean changed) {
        public SlotRecommendation {
            if (starterOrdinal < 0) throw new IllegalArgumentException("starterOrdinal must not be negative");
            slot = requireText(slot, "slot");
            currentPlayerId = requireText(currentPlayerId, "currentPlayerId");
            currentPlayerName = requireText(currentPlayerName, "currentPlayerName");
            recommendedPlayerId = requireText(recommendedPlayerId, "recommendedPlayerId");
            recommendedPlayerName = requireText(recommendedPlayerName, "recommendedPlayerName");
            Objects.requireNonNull(projectedPoints, "projectedPoints must not be null");
            if ((currentProjectedPoints == null) != (projectedGain == null)) {
                throw new IllegalArgumentException(
                    "currentProjectedPoints and projectedGain must both be present or both be unavailable");
            }
            if (currentProjectedPoints != null
                && projectedGain.compareTo(projectedPoints.subtract(currentProjectedPoints)) != 0) {
                throw new IllegalArgumentException("projectedGain must equal projectedPoints - currentProjectedPoints");
            }
        }
    }

    public record Recommendation(
        String policyId,
        boolean ready,
        String reason,
        String solverPolicyId,
        String eligibilityPolicyId,
        BigDecimal projectedTotal,
        List<SlotRecommendation> assignments,
        List<RosterPlayer> movesToBench,
        List<RosterPlayer> promotions) {
        public Recommendation {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            assignments = List.copyOf(Objects.requireNonNull(assignments, "assignments must not be null"));
            movesToBench = List.copyOf(Objects.requireNonNull(movesToBench, "movesToBench must not be null"));
            promotions = List.copyOf(Objects.requireNonNull(promotions, "promotions must not be null"));
            if (ready) {
                if (reason != null) throw new IllegalArgumentException("ready recommendation cannot have a reason");
                if (!OptimalLegalLineupSolver.POLICY_ID.equals(solverPolicyId)) {
                    throw new IllegalArgumentException("unexpected solverPolicyId");
                }
                if (!LineupSlotEligibilityPolicy.POLICY_ID.equals(eligibilityPolicyId)) {
                    throw new IllegalArgumentException("unexpected eligibilityPolicyId");
                }
                Objects.requireNonNull(projectedTotal, "projectedTotal must not be null");
                if (assignments.isEmpty()) throw new IllegalArgumentException("ready recommendation requires assignments");
            } else {
                reason = requireText(reason, "reason");
                if (solverPolicyId != null || eligibilityPolicyId != null || projectedTotal != null
                    || !assignments.isEmpty() || !movesToBench.isEmpty() || !promotions.isEmpty()) {
                    throw new IllegalArgumentException("unavailable recommendation cannot contain lineup output");
                }
            }
        }

        public static Recommendation unavailable(String reason) {
            return new Recommendation(POLICY_ID, false, reason, null, null, null, List.of(), List.of(), List.of());
        }

        public static Recommendation ready(
            String solverPolicyId,
            String eligibilityPolicyId,
            BigDecimal projectedTotal,
            List<SlotRecommendation> assignments,
            List<RosterPlayer> movesToBench,
            List<RosterPlayer> promotions) {
            return new Recommendation(
                POLICY_ID, true, null, solverPolicyId, eligibilityPolicyId, projectedTotal,
                assignments, movesToBench, promotions);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
