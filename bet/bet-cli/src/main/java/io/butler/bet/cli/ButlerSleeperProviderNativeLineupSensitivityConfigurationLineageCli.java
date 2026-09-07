package io.butler.bet.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.intelligence.LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer;
import io.butler.bet.intelligence.LineupSlotEligibilityPolicy;
import io.butler.bet.intelligence.SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer;
import io.butler.bet.sleeper.SleeperClient;
import io.butler.bet.sleeper.SleeperJsonParser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** BF-591 read-only source-lineage diagnostic for ambiguous historical starter-slot shape. */
public final class ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String SLEEPER_SOURCE = "sleeper";
    private static final int MAX_HISTORY_HOPS = 30;

    private ButlerSleeperProviderNativeLineupSensitivityConfigurationLineageCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            var report = new SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer(database).audit();
            print(report, collect(database, report, new SleeperApiSource()));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void parse(String[] args) {
        if (args != null && args.length != 0) {
            throw new IllegalArgumentException(
                "Usage: sleeperProviderNativeLineupSensitivityConfigurationLineage");
        }
    }

    static Map<LeagueSeasonKey, CollectedDiagnostic> collect(
        Database database,
        SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer.AuditReport report,
        SleeperLeagueSource source) throws Exception {
        Objects.requireNonNull(database, "database must not be null");
        Objects.requireNonNull(report, "report must not be null");
        Objects.requireNonNull(source, "source must not be null");

        var leagues = new LeagueRepository(database);
        var configurations = new LeagueConfigurationObservationRepository(database);
        Map<LeagueSeasonKey, CollectedDiagnostic> result = new LinkedHashMap<>();

        for (var entry : report.entries()) {
            if (entry.downstreamAudit().isEmpty()) continue;
            var commonUniverse = entry.downstreamAudit().orElseThrow().sourceCommonUniverse();
            if (commonUniverse.commonUniverseState()
                != LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState
                    .UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS) {
                continue;
            }

            var key = new LeagueSeasonKey(entry.leagueId(), entry.season());
            try {
                if (entry.selection().providerAudit() == null) {
                    throw new IllegalStateException("no provider-native audit provenance on zero-common-week entry");
                }
                String providerLeagueId = entry.selection().providerAudit().providerLeagueId();
                var butlerLeague = leagues.findById(entry.leagueId())
                    .orElseThrow(() -> new IllegalStateException("Butler league not found: " + entry.leagueId()));
                List<String> persistedRosterPositions = configurations
                    .findLatestForSeason(entry.leagueId(), entry.season(), SLEEPER_SOURCE)
                    .map(value -> value.lineupSlots())
                    .orElse(List.of());

                var diagnostic = analyzeLineage(
                    providerLeagueId,
                    entry.season(),
                    butlerLeague.getExternalId(),
                    butlerLeague.getSeason(),
                    persistedRosterPositions,
                    source);
                result.put(key, CollectedDiagnostic.available(diagnostic));
            } catch (Exception e) {
                result.put(key, CollectedDiagnostic.unavailable(e.getMessage()));
            }
        }
        return Map.copyOf(result);
    }

    static LineageDiagnostic analyzeLineage(
        String providerLeagueId,
        int expectedSeason,
        String currentButlerExternalLeagueId,
        Integer currentButlerSeason,
        List<String> persistedRosterPositions,
        SleeperLeagueSource source) throws Exception {
        String providerId = requireText(providerLeagueId, "providerLeagueId");
        if (expectedSeason < 1999 || expectedSeason > 2100) {
            throw new IllegalArgumentException("expectedSeason must be between 1999 and 2100");
        }
        Objects.requireNonNull(persistedRosterPositions, "persistedRosterPositions must not be null");
        Objects.requireNonNull(source, "source must not be null");

        LeagueShape provider = fetchShape(providerId, source);
        if (provider.season() != expectedSeason) {
            throw new IllegalStateException(
                "provider league season mismatch: expected=" + expectedSeason + " actual=" + provider.season());
        }

        Optional<LeagueShape> predecessor = Optional.empty();
        if (provider.previousLeagueId() != null) {
            predecessor = Optional.of(fetchShape(provider.previousLeagueId(), source));
        }

        SuccessorSearch successorSearch = findImmediateSuccessor(
            currentButlerExternalLeagueId, provider.id(), source);

        List<SlotDelta> providerAdditions = predecessor
            .map(value -> exactSingleSlotAdditions(provider.supportedStartingSlots(), value.supportedStartingSlots()))
            .orElse(List.of());
        List<SlotDelta> successorAdditions = successorSearch.successor()
            .map(value -> exactSingleSlotAdditions(value.supportedStartingSlots(), provider.supportedStartingSlots()))
            .orElse(List.of());

        PredecessorEvidenceState predecessorState;
        if (predecessor.isEmpty()) {
            predecessorState = PredecessorEvidenceState.PREDECESSOR_UNAVAILABLE;
        } else if (providerAdditions.size() == 1) {
            predecessorState = PredecessorEvidenceState.PREDECESSOR_UNIQUE_SINGLE_SLOT_ADDITION;
        } else if (providerAdditions.size() > 1) {
            predecessorState = PredecessorEvidenceState.PREDECESSOR_AMBIGUOUS_SINGLE_SLOT_ADDITION;
        } else if (provider.supportedStartingSlots().equals(predecessor.orElseThrow().supportedStartingSlots())) {
            predecessorState = PredecessorEvidenceState.PREDECESSOR_SAME_SUPPORTED_STARTING_SHAPE;
        } else {
            predecessorState = PredecessorEvidenceState.PREDECESSOR_NOT_EXACT_SINGLE_SLOT_TRANSITION;
        }

        return new LineageDiagnostic(
            provider,
            predecessor,
            successorSearch,
            currentButlerExternalLeagueId,
            currentButlerSeason,
            List.copyOf(persistedRosterPositions),
            persistedRosterPositions.equals(provider.rosterPositions()),
            providerAdditions,
            successorAdditions,
            predecessorState);
    }

    private static SuccessorSearch findImmediateSuccessor(
        String currentButlerExternalLeagueId,
        String providerLeagueId,
        SleeperLeagueSource source) throws Exception {
        if (currentButlerExternalLeagueId == null || currentButlerExternalLeagueId.isBlank()) {
            return new SuccessorSearch(SuccessorState.CURRENT_EXTERNAL_UNAVAILABLE, Optional.empty(), 0);
        }
        String currentId = currentButlerExternalLeagueId.trim();
        if (currentId.equals(providerLeagueId)) {
            return new SuccessorSearch(SuccessorState.CURRENT_IS_PROVIDER, Optional.empty(), 0);
        }

        Set<String> visited = new HashSet<>();
        String cursor = currentId;
        LeagueShape child = null;
        for (int hops = 0; hops <= MAX_HISTORY_HOPS; hops++) {
            if (!visited.add(cursor)) {
                throw new IllegalStateException("Sleeper lineage contains a cycle at: " + cursor);
            }
            LeagueShape node = fetchShape(cursor, source);
            if (node.id().equals(providerLeagueId)) {
                return new SuccessorSearch(
                    child == null ? SuccessorState.CURRENT_IS_PROVIDER : SuccessorState.FOUND,
                    Optional.ofNullable(child),
                    hops);
            }
            if (node.previousLeagueId() == null) {
                return new SuccessorSearch(SuccessorState.PROVIDER_NOT_IN_CURRENT_LINEAGE, Optional.empty(), hops);
            }
            child = node;
            cursor = node.previousLeagueId();
        }
        throw new IllegalStateException(
            "Sleeper lineage exceeded " + MAX_HISTORY_HOPS + " links before provider league " + providerLeagueId);
    }

    private static LeagueShape fetchShape(String sleeperLeagueId, SleeperLeagueSource source) throws Exception {
        String expectedId = requireText(sleeperLeagueId, "sleeperLeagueId");
        String payload = source.fetchLeague(expectedId);
        var parsed = new SleeperJsonParser().parseLeague(payload);
        if (!expectedId.equals(parsed.id())) {
            throw new IllegalStateException(
                "Sleeper league identity mismatch: requested=" + expectedId + " returned=" + parsed.id());
        }

        JsonNode root = new ObjectMapper().readTree(payload);
        String previousLeagueId = normalizePrevious(root.get("previous_league_id"));
        var slotPolicy = new LineupSlotEligibilityPolicy();
        List<String> supportedStartingSlots = parsed.rosterPositions().stream()
            .filter(slot -> slotPolicy.ruleFor(slot).state()
                == LineupSlotEligibilityPolicy.SlotState.STARTING_SUPPORTED)
            .toList();
        return new LeagueShape(
            parsed.id(), parsed.name(), parsed.season(), previousLeagueId,
            parsed.rosterPositions(), supportedStartingSlots);
    }

    private static String normalizePrevious(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String value = node.asText(null);
        if (value == null || value.isBlank() || "0".equals(value.trim())) return null;
        return value.trim();
    }

    static List<SlotDelta> exactSingleSlotAdditions(List<String> newer, List<String> older) {
        Objects.requireNonNull(newer, "newer must not be null");
        Objects.requireNonNull(older, "older must not be null");
        if (newer.size() != older.size() + 1) return List.of();

        List<SlotDelta> result = new ArrayList<>();
        for (int omitted = 0; omitted < newer.size(); omitted++) {
            List<String> candidate = new ArrayList<>(newer);
            String removed = candidate.remove(omitted);
            if (candidate.equals(older)) result.add(new SlotDelta(omitted, removed));
        }
        return List.copyOf(result);
    }

    static void print(
        SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer.AuditReport report,
        Map<LeagueSeasonKey, CollectedDiagnostic> diagnostics) {
        Objects.requireNonNull(report, "report must not be null");
        Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        System.out.println("Sleeper provider-native lineup-sensitivity configuration-lineage diagnostic");
        System.out.println("Policy: BF-591 read-only source-lineage structural diagnostic");
        System.out.println("Fixed-frame league-seasons: " + report.summary().fixedFrameLeagueSeasons());
        System.out.println("Selection rule: use the same BF-565 fixed provider-points frame; inspect lineage only for entries whose governed BF-518 common universe has zero comparable weeks.");
        System.out.println();

        for (var entry : report.entries()) {
            var key = new LeagueSeasonKey(entry.leagueId(), entry.season());
            var collected = diagnostics.get(key);
            if (collected == null) {
                System.out.println(entry.season() + " | " + entry.leagueName() + " [" + entry.leagueId()
                    + "] | BF-591 state=NOT_APPLICABLE_COMMON_UNIVERSE_NOT_ZERO");
                continue;
            }
            System.out.println(entry.season() + " | " + entry.leagueName() + " [" + entry.leagueId() + "]");
            if (collected.diagnostic().isEmpty()) {
                System.out.println("  BF-591 state=SOURCE_LINEAGE_UNAVAILABLE | detail=" + collected.detail());
                continue;
            }
            printDiagnostic(collected.diagnostic().orElseThrow());
        }

        System.out.println();
        System.out.println("BF-591 boundary: source-lineage shape is descriptive structural evidence only. A unique adjacent-season slot transition does not by itself authorize rewriting historical configuration, dropping a configured slot, reconstructing starters, changing common-universe/BF-518/BF-521 semantics, or selecting a threshold or evaluating a manager.");
    }

    static void printDiagnostic(LineageDiagnostic diagnostic) {
        System.out.println("  BF-591 state=AVAILABLE");
        System.out.println("  persisted BF-589 roster_positions: " + diagnostic.persistedRosterPositions());
        System.out.println("  live provider roster_positions exact persisted match: "
            + diagnostic.persistedMatchesProviderLive());
        printShape("provider", diagnostic.provider());
        if (diagnostic.predecessor().isPresent()) {
            printShape("predecessor", diagnostic.predecessor().orElseThrow());
        } else {
            System.out.println("  predecessor: unavailable");
        }
        System.out.println("  exact provider additions relative to predecessor: " + diagnostic.providerAdditions());
        System.out.println("  predecessor evidence state: " + diagnostic.predecessorEvidenceState());
        System.out.println("  Butler current external league: "
            + (diagnostic.currentButlerExternalLeagueId() == null ? "n/a" : diagnostic.currentButlerExternalLeagueId())
            + " | Butler recorded season="
            + (diagnostic.currentButlerSeason() == null ? "n/a" : diagnostic.currentButlerSeason()));
        System.out.println("  successor search state: " + diagnostic.successorSearch().state()
            + " | lineage hops=" + diagnostic.successorSearch().hops());
        if (diagnostic.successorSearch().successor().isPresent()) {
            printShape("immediate successor", diagnostic.successorSearch().successor().orElseThrow());
        } else {
            System.out.println("  immediate successor: unavailable");
        }
        System.out.println("  exact successor additions relative to provider: " + diagnostic.successorAdditions());
    }

    private static void printShape(String label, LeagueShape shape) {
        System.out.println("  " + label + ": season=" + shape.season()
            + " | league=" + shape.id()
            + " | previous=" + (shape.previousLeagueId() == null ? "n/a" : shape.previousLeagueId()));
        System.out.println("    roster_positions: " + shape.rosterPositions());
        System.out.println("    supported starting slots: " + shape.supportedStartingSlots());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    interface SleeperLeagueSource {
        String fetchLeague(String sleeperLeagueId) throws Exception;
    }

    private static final class SleeperApiSource implements SleeperLeagueSource {
        private final SleeperClient client = new SleeperClient();

        @Override
        public String fetchLeague(String sleeperLeagueId) throws Exception {
            return client.getLeague(sleeperLeagueId);
        }
    }

    enum PredecessorEvidenceState {
        PREDECESSOR_UNAVAILABLE,
        PREDECESSOR_UNIQUE_SINGLE_SLOT_ADDITION,
        PREDECESSOR_AMBIGUOUS_SINGLE_SLOT_ADDITION,
        PREDECESSOR_SAME_SUPPORTED_STARTING_SHAPE,
        PREDECESSOR_NOT_EXACT_SINGLE_SLOT_TRANSITION
    }

    enum SuccessorState {
        CURRENT_EXTERNAL_UNAVAILABLE,
        CURRENT_IS_PROVIDER,
        FOUND,
        PROVIDER_NOT_IN_CURRENT_LINEAGE
    }

    record LeagueSeasonKey(String leagueId, int season) {
        LeagueSeasonKey {
            leagueId = requireText(leagueId, "leagueId");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
        }
    }

    record SlotDelta(int ordinal, String slot) {
        SlotDelta {
            if (ordinal < 0) throw new IllegalArgumentException("ordinal must not be negative");
            slot = requireText(slot, "slot");
        }

        @Override
        public String toString() {
            return "ordinal " + ordinal + " " + slot;
        }
    }

    record LeagueShape(
        String id,
        String name,
        int season,
        String previousLeagueId,
        List<String> rosterPositions,
        List<String> supportedStartingSlots) {
        LeagueShape {
            id = requireText(id, "id");
            name = requireText(name, "name");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
            previousLeagueId = previousLeagueId == null || previousLeagueId.isBlank()
                ? null : previousLeagueId.trim();
            rosterPositions = List.copyOf(Objects.requireNonNull(rosterPositions, "rosterPositions must not be null"));
            supportedStartingSlots = List.copyOf(Objects.requireNonNull(
                supportedStartingSlots, "supportedStartingSlots must not be null"));
        }
    }

    record SuccessorSearch(SuccessorState state, Optional<LeagueShape> successor, int hops) {
        SuccessorSearch {
            Objects.requireNonNull(state, "state must not be null");
            successor = Objects.requireNonNull(successor, "successor must not be null");
            if (hops < 0) throw new IllegalArgumentException("hops must not be negative");
            if (state == SuccessorState.FOUND && successor.isEmpty()) {
                throw new IllegalArgumentException("FOUND successor state requires a successor");
            }
        }
    }

    record LineageDiagnostic(
        LeagueShape provider,
        Optional<LeagueShape> predecessor,
        SuccessorSearch successorSearch,
        String currentButlerExternalLeagueId,
        Integer currentButlerSeason,
        List<String> persistedRosterPositions,
        boolean persistedMatchesProviderLive,
        List<SlotDelta> providerAdditions,
        List<SlotDelta> successorAdditions,
        PredecessorEvidenceState predecessorEvidenceState) {
        LineageDiagnostic {
            Objects.requireNonNull(provider, "provider must not be null");
            predecessor = Objects.requireNonNull(predecessor, "predecessor must not be null");
            Objects.requireNonNull(successorSearch, "successorSearch must not be null");
            currentButlerExternalLeagueId = currentButlerExternalLeagueId == null
                || currentButlerExternalLeagueId.isBlank() ? null : currentButlerExternalLeagueId.trim();
            persistedRosterPositions = List.copyOf(Objects.requireNonNull(
                persistedRosterPositions, "persistedRosterPositions must not be null"));
            providerAdditions = List.copyOf(Objects.requireNonNull(providerAdditions, "providerAdditions must not be null"));
            successorAdditions = List.copyOf(Objects.requireNonNull(successorAdditions, "successorAdditions must not be null"));
            Objects.requireNonNull(predecessorEvidenceState, "predecessorEvidenceState must not be null");
        }
    }

    record CollectedDiagnostic(Optional<LineageDiagnostic> diagnostic, String detail) {
        CollectedDiagnostic {
            diagnostic = Objects.requireNonNull(diagnostic, "diagnostic must not be null");
            detail = detail == null ? "" : detail.trim();
        }

        static CollectedDiagnostic available(LineageDiagnostic diagnostic) {
            return new CollectedDiagnostic(Optional.of(Objects.requireNonNull(diagnostic)), "");
        }

        static CollectedDiagnostic unavailable(String detail) {
            String message = detail == null || detail.isBlank() ? "unknown source-lineage error" : detail.trim();
            return new CollectedDiagnostic(Optional.empty(), message);
        }
    }
}