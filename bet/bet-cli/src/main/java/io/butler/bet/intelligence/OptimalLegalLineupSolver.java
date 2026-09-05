package io.butler.bet.intelligence;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministically selects the maximum-cardinality, maximum-points legal starting lineup from
 * explicit scored player candidates and provider lineup slots.
 *
 * <p>Cardinality is optimized before points: an eligible negative-scoring player still fills a
 * starting slot when doing so increases the number of legally filled starters. Non-starting slots
 * are ignored. Unsupported provider slots fail closed.</p>
 */
public final class OptimalLegalLineupSolver {
    public static final String POLICY_ID =
        "optimal-legal-lineup-solver-v1-max-fill-then-points-deterministic";

    private final LineupSlotEligibilityPolicy eligibilityPolicy = new LineupSlotEligibilityPolicy();

    public LineupResult solve(List<String> providerLineupSlots, List<ScoredPlayerCandidate> candidates) {
        Objects.requireNonNull(providerLineupSlots, "providerLineupSlots must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");

        List<StartingSlot> startingSlots = startingSlots(providerLineupSlots);
        if (startingSlots.isEmpty()) {
            throw new IllegalStateException("No supported starting lineup slots are available");
        }

        List<ScoredPlayerCandidate> orderedCandidates = validateAndOrderCandidates(candidates);
        int source = 0;
        int playerOffset = 1;
        int slotOffset = playerOffset + orderedCandidates.size();
        int sink = slotOffset + startingSlots.size();
        List<List<Edge>> graph = graph(sink + 1);

        for (int playerIndex = 0; playerIndex < orderedCandidates.size(); playerIndex++) {
            addEdge(graph, source, playerOffset + playerIndex, BigDecimal.ZERO);
        }
        for (int slotIndex = 0; slotIndex < startingSlots.size(); slotIndex++) {
            addEdge(graph, slotOffset + slotIndex, sink, BigDecimal.ZERO);
        }

        List<PlayerSlotEdge> assignmentEdges = new ArrayList<>();
        for (int playerIndex = 0; playerIndex < orderedCandidates.size(); playerIndex++) {
            ScoredPlayerCandidate candidate = orderedCandidates.get(playerIndex);
            for (int slotIndex = 0; slotIndex < startingSlots.size(); slotIndex++) {
                StartingSlot slot = startingSlots.get(slotIndex);
                if (eligibilityPolicy.isPlayerEligible(slot.slot(), candidate.providerFantasyPositions())) {
                    Edge edge = addEdge(
                        graph,
                        playerOffset + playerIndex,
                        slotOffset + slotIndex,
                        candidate.fantasyPoints().negate());
                    assignmentEdges.add(new PlayerSlotEdge(playerIndex, slotIndex, edge));
                }
            }
        }

        int filledSlots = 0;
        while (augmentShortestPath(graph, source, sink)) filledSlots++;

        Assignment[] assignments = new Assignment[startingSlots.size()];
        BigDecimal totalPoints = BigDecimal.ZERO;
        for (PlayerSlotEdge assignmentEdge : assignmentEdges) {
            if (assignmentEdge.edge().capacity != 0) continue;
            StartingSlot slot = startingSlots.get(assignmentEdge.slotIndex());
            ScoredPlayerCandidate player = orderedCandidates.get(assignmentEdge.playerIndex());
            assignments[assignmentEdge.slotIndex()] = new Assignment(
                slot.ordinal(), slot.slot(), player.playerId(), player.fantasyPoints());
            totalPoints = totalPoints.add(player.fantasyPoints());
        }
        for (int i = 0; i < assignments.length; i++) {
            if (assignments[i] == null) {
                StartingSlot slot = startingSlots.get(i);
                assignments[i] = new Assignment(slot.ordinal(), slot.slot(), null, null);
            }
        }

        return new LineupResult(
            POLICY_ID,
            LineupSlotEligibilityPolicy.POLICY_ID,
            startingSlots.size(),
            filledSlots,
            totalPoints,
            List.of(assignments));
    }

    private List<StartingSlot> startingSlots(List<String> providerLineupSlots) {
        List<StartingSlot> result = new ArrayList<>();
        for (int ordinal = 0; ordinal < providerLineupSlots.size(); ordinal++) {
            String slot = requireText(providerLineupSlots.get(ordinal), "slot");
            var rule = eligibilityPolicy.ruleFor(slot);
            switch (rule.state()) {
                case STARTING_SUPPORTED -> result.add(new StartingSlot(ordinal, slot));
                case NON_STARTING -> { }
                case UNSUPPORTED -> throw new IllegalStateException("Unsupported lineup slot: " + slot);
            }
        }
        return List.copyOf(result);
    }

    private static List<ScoredPlayerCandidate> validateAndOrderCandidates(List<ScoredPlayerCandidate> candidates) {
        Set<String> playerIds = new HashSet<>();
        List<ScoredPlayerCandidate> ordered = new ArrayList<>();
        for (ScoredPlayerCandidate candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate must not be null");
            if (!playerIds.add(candidate.playerId())) {
                throw new IllegalArgumentException("duplicate candidate playerId: " + candidate.playerId());
            }
            ordered.add(candidate);
        }
        ordered.sort(Comparator.comparing(ScoredPlayerCandidate::playerId));
        return List.copyOf(ordered);
    }

    private static List<List<Edge>> graph(int nodeCount) {
        List<List<Edge>> graph = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) graph.add(new ArrayList<>());
        return graph;
    }

    private static Edge addEdge(List<List<Edge>> graph, int from, int to, BigDecimal cost) {
        Edge forward = new Edge(to, graph.get(to).size(), 1, cost);
        Edge reverse = new Edge(from, graph.get(from).size(), 0, cost.negate());
        graph.get(from).add(forward);
        graph.get(to).add(reverse);
        return forward;
    }

    /**
     * Adds one unit of minimum-cost flow. Repeating until no path remains yields maximum
     * cardinality first; minimum path costs then maximize exact fantasy points within that
     * cardinality. Bellman-Ford permits negative player-to-slot costs without floating-point math.
     */
    private static boolean augmentShortestPath(List<List<Edge>> graph, int source, int sink) {
        int nodeCount = graph.size();
        BigDecimal[] distance = new BigDecimal[nodeCount];
        int[] previousNode = new int[nodeCount];
        int[] previousEdge = new int[nodeCount];
        Arrays.fill(previousNode, -1);
        Arrays.fill(previousEdge, -1);
        distance[source] = BigDecimal.ZERO;

        for (int pass = 0; pass < nodeCount - 1; pass++) {
            boolean changed = false;
            for (int from = 0; from < nodeCount; from++) {
                if (distance[from] == null) continue;
                List<Edge> edges = graph.get(from);
                for (int edgeIndex = 0; edgeIndex < edges.size(); edgeIndex++) {
                    Edge edge = edges.get(edgeIndex);
                    if (edge.capacity <= 0) continue;
                    BigDecimal candidateDistance = distance[from].add(edge.cost);
                    if (distance[edge.to] == null || candidateDistance.compareTo(distance[edge.to]) < 0) {
                        distance[edge.to] = candidateDistance;
                        previousNode[edge.to] = from;
                        previousEdge[edge.to] = edgeIndex;
                        changed = true;
                    }
                }
            }
            if (!changed) break;
        }

        if (distance[sink] == null) return false;
        for (int node = sink; node != source; node = previousNode[node]) {
            int from = previousNode[node];
            int edgeIndex = previousEdge[node];
            if (from < 0 || edgeIndex < 0) throw new IllegalStateException("Incomplete augmenting path");
            Edge edge = graph.get(from).get(edgeIndex);
            edge.capacity--;
            graph.get(edge.to).get(edge.reverseIndex).capacity++;
        }
        return true;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public record ScoredPlayerCandidate(
        String playerId,
        List<String> providerFantasyPositions,
        BigDecimal fantasyPoints) {
        public ScoredPlayerCandidate {
            requireText(playerId, "playerId");
            providerFantasyPositions = List.copyOf(Objects.requireNonNull(
                providerFantasyPositions, "providerFantasyPositions must not be null"));
            for (String position : providerFantasyPositions) requireText(position, "providerFantasyPosition");
            Objects.requireNonNull(fantasyPoints, "fantasyPoints must not be null");
        }
    }

    public record Assignment(
        int slotOrdinal,
        String slot,
        String playerId,
        BigDecimal fantasyPoints) {
        public Assignment {
            if (slotOrdinal < 0) throw new IllegalArgumentException("slotOrdinal must not be negative");
            requireText(slot, "slot");
            if ((playerId == null) != (fantasyPoints == null)) {
                throw new IllegalArgumentException("playerId and fantasyPoints must both be present or both be absent");
            }
            if (playerId != null) requireText(playerId, "playerId");
        }

        public boolean filled() {
            return playerId != null;
        }
    }

    public record LineupResult(
        String policyId,
        String eligibilityPolicyId,
        int startingSlots,
        int filledSlots,
        BigDecimal totalPoints,
        List<Assignment> assignments) {
        public LineupResult {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!LineupSlotEligibilityPolicy.POLICY_ID.equals(eligibilityPolicyId)) {
                throw new IllegalArgumentException("unexpected eligibilityPolicyId");
            }
            if (startingSlots <= 0) throw new IllegalArgumentException("startingSlots must be positive");
            if (filledSlots < 0 || filledSlots > startingSlots) {
                throw new IllegalArgumentException("filledSlots must be within startingSlots");
            }
            Objects.requireNonNull(totalPoints, "totalPoints must not be null");
            assignments = List.copyOf(Objects.requireNonNull(assignments, "assignments must not be null"));
            if (assignments.size() != startingSlots) {
                throw new IllegalArgumentException("assignments must contain every starting slot");
            }
            long countedFilled = assignments.stream().filter(Assignment::filled).count();
            if (countedFilled != filledSlots) {
                throw new IllegalArgumentException("filledSlots must match assignments");
            }
        }

        public boolean complete() {
            return filledSlots == startingSlots;
        }
    }

    private record StartingSlot(int ordinal, String slot) {}
    private record PlayerSlotEdge(int playerIndex, int slotIndex, Edge edge) {}

    private static final class Edge {
        private final int to;
        private final int reverseIndex;
        private int capacity;
        private final BigDecimal cost;

        private Edge(int to, int reverseIndex, int capacity, BigDecimal cost) {
            this.to = to;
            this.reverseIndex = reverseIndex;
            this.capacity = capacity;
            this.cost = Objects.requireNonNull(cost, "cost must not be null");
        }
    }
}
