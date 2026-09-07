package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Read-only BF-596 discovery of the unique 2026 Sleeper successor for a linked 2025 league. */
public final class SleeperCurrentSeasonSuccessorDiscovery {
    public static final String POLICY_ID =
        "sleeper-current-season-successor-discovery-v1-2026-explicit-lineage-read-only-fail-closed";
    public static final int LINKED_SEASON = 2025;
    public static final int TARGET_SEASON = 2026;

    private final Database database;
    private final Source source;
    private final ObjectMapper mapper = new ObjectMapper();

    public SleeperCurrentSeasonSuccessorDiscovery(Database database) {
        this(database, new LiveSource());
    }

    SleeperCurrentSeasonSuccessorDiscovery(Database database, Source source) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
    }

    public DiscoveryReport discover(String butlerLeagueId)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(butlerLeagueId, "butlerLeagueId");
        var league = new LeagueRepository(database).findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + normalizedLeagueId));
        String linkedSleeperLeagueId = requireText(league.getExternalId(), "league external Sleeper id");

        LeagueObservation linked = parseLeagueObject(source.league(linkedSleeperLeagueId));
        if (!linkedSleeperLeagueId.equals(linked.leagueId())) {
            throw new IllegalStateException(
                "Linked Sleeper league id does not match provider league payload: linked="
                    + linkedSleeperLeagueId + " provider=" + linked.leagueId());
        }
        if (linked.season() != LINKED_SEASON) {
            throw new IllegalStateException(
                "BF-596 requires linked provider season " + LINKED_SEASON + " but found " + linked.season());
        }

        RosterOwnerFrame ownerFrame = parseRosterOwners(source.rosters(linkedSleeperLeagueId));
        Map<String, CandidateAccumulator> candidatesById = new LinkedHashMap<>();
        for (String ownerId : ownerFrame.ownerIds()) {
            for (LeagueObservation candidate : parseLeagueArray(source.userLeagues(ownerId, TARGET_SEASON))) {
                if (candidate.season() != TARGET_SEASON) continue;
                candidatesById
                    .computeIfAbsent(candidate.leagueId(), ignored -> new CandidateAccumulator(candidate))
                    .observe(candidate, ownerId);
            }
        }

        List<CandidateObservation> candidates = new ArrayList<>();
        candidatesById.values().stream()
            .sorted(Comparator.comparing(accumulator -> accumulator.league.leagueId()))
            .forEach(accumulator -> {
                try {
                    var lineage = source.lineage(accumulator.league.leagueId());
                    if (lineage.startingSeason() != TARGET_SEASON) {
                        throw new IllegalStateException(
                            "Candidate lineage starts in season " + lineage.startingSeason()
                                + " instead of " + TARGET_SEASON + ": " + accumulator.league.leagueId());
                    }
                    List<String> lineageIds = lineage.linksNewestToOldest().stream()
                        .map(SleeperLeagueLineageResolver.LeagueLink::leagueId)
                        .toList();
                    boolean matches = lineage.containsSleeperLeagueId(linkedSleeperLeagueId);
                    candidates.add(new CandidateObservation(
                        accumulator.league.leagueId(),
                        accumulator.league.name(),
                        accumulator.league.season(),
                        accumulator.league.status(),
                        List.copyOf(accumulator.ownerIds),
                        lineageIds,
                        matches));
                } catch (IOException | InterruptedException e) {
                    throw new SourceFailure(e);
                }
            });

        List<CandidateObservation> matching = candidates.stream()
            .filter(CandidateObservation::lineageContainsLinkedLeague)
            .toList();
        DiscoveryState state = matching.isEmpty()
            ? DiscoveryState.NO_SUCCESSOR
            : matching.size() == 1 ? DiscoveryState.UNIQUE_SUCCESSOR : DiscoveryState.AMBIGUOUS_SUCCESSORS;

        return new DiscoveryReport(
            POLICY_ID,
            normalizedLeagueId,
            league.getName(),
            linkedSleeperLeagueId,
            linked.name(),
            linked.season(),
            linked.status(),
            TARGET_SEASON,
            ownerFrame.ownerIds().size(),
            ownerFrame.ownerlessRosters(),
            List.copyOf(candidates),
            state);
    }

    private LeagueObservation parseLeagueObject(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "league payload"));
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("Sleeper league payload must be an object");
        }
        return parseLeagueNode(root);
    }

    private List<LeagueObservation> parseLeagueArray(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "user leagues payload"));
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("Sleeper user leagues payload must be an array");
        }
        List<LeagueObservation> result = new ArrayList<>();
        for (JsonNode node : root) result.add(parseLeagueNode(node));
        return List.copyOf(result);
    }

    private static LeagueObservation parseLeagueNode(JsonNode node) {
        String leagueId = requireText(text(node.get("league_id")), "provider league_id");
        String name = requireText(text(node.get("name")), "provider league name");
        int season = parseSeason(node.get("season"));
        String status = normalizeOptional(text(node.get("status")));
        return new LeagueObservation(leagueId, name, season, status);
    }

    private RosterOwnerFrame parseRosterOwners(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "rosters payload"));
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("Sleeper rosters payload must be an array");
        }
        Set<String> ownerIds = new TreeSet<>();
        int ownerless = 0;
        for (JsonNode roster : root) {
            String ownerId = normalizeOptional(text(roster.get("owner_id")));
            if (ownerId == null) ownerless++;
            else ownerIds.add(ownerId);
        }
        return new RosterOwnerFrame(List.copyOf(ownerIds), ownerless);
    }

    private static int parseSeason(JsonNode node) {
        String raw = node == null || node.isNull() ? null : node.asText(null);
        if (raw == null || raw.isBlank()) throw new IllegalStateException("Sleeper league season is missing");
        try {
            int season = Integer.parseInt(raw.trim());
            if (season < 1999 || season > 2100) throw new NumberFormatException();
            return season;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid Sleeper league season: " + raw);
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }

    interface Source {
        String league(String sleeperLeagueId) throws IOException, InterruptedException;
        String rosters(String sleeperLeagueId) throws IOException, InterruptedException;
        String userLeagues(String ownerId, int season) throws IOException, InterruptedException;
        SleeperLeagueLineageResolver.Lineage lineage(String sleeperLeagueId) throws IOException, InterruptedException;
    }

    private static final class LiveSource implements Source {
        private final SleeperClient client = new SleeperClient();
        private final SleeperLeagueLineageResolver resolver = new SleeperLeagueLineageResolver();

        @Override
        public String league(String sleeperLeagueId) throws IOException, InterruptedException {
            return client.getLeague(sleeperLeagueId);
        }

        @Override
        public String rosters(String sleeperLeagueId) throws IOException, InterruptedException {
            return client.getLeagueRosters(sleeperLeagueId);
        }

        @Override
        public String userLeagues(String ownerId, int season) throws IOException, InterruptedException {
            return client.getUserLeagues(ownerId, Integer.toString(season));
        }

        @Override
        public SleeperLeagueLineageResolver.Lineage lineage(String sleeperLeagueId)
            throws IOException, InterruptedException {
            return resolver.resolve(sleeperLeagueId);
        }
    }

    public enum DiscoveryState {
        UNIQUE_SUCCESSOR,
        NO_SUCCESSOR,
        AMBIGUOUS_SUCCESSORS
    }

    public record CandidateObservation(
        String sleeperLeagueId,
        String name,
        int season,
        String status,
        List<String> surfacedByOwnerIds,
        List<String> lineageNewestToOldest,
        boolean lineageContainsLinkedLeague) {
        public CandidateObservation {
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            name = requireText(name, "name");
            if (season != TARGET_SEASON) throw new IllegalArgumentException("candidate season must be " + TARGET_SEASON);
            status = normalizeOptional(status);
            surfacedByOwnerIds = List.copyOf(Objects.requireNonNull(surfacedByOwnerIds, "surfacedByOwnerIds must not be null"));
            lineageNewestToOldest = List.copyOf(Objects.requireNonNull(lineageNewestToOldest, "lineageNewestToOldest must not be null"));
            if (surfacedByOwnerIds.isEmpty()) throw new IllegalArgumentException("candidate must be surfaced by at least one owner");
            if (lineageNewestToOldest.isEmpty() || !sleeperLeagueId.equals(lineageNewestToOldest.get(0))) {
                throw new IllegalArgumentException("candidate lineage must start with candidate league id");
            }
        }
    }

    public record DiscoveryReport(
        String policyId,
        String butlerLeagueId,
        String butlerLeagueName,
        String linkedSleeperLeagueId,
        String linkedSleeperLeagueName,
        int linkedProviderSeason,
        String linkedProviderStatus,
        int targetSeason,
        int distinctOwnerIds,
        int ownerlessRosters,
        List<CandidateObservation> candidates,
        DiscoveryState state) {
        public DiscoveryReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            butlerLeagueId = requireText(butlerLeagueId, "butlerLeagueId");
            butlerLeagueName = requireText(butlerLeagueName, "butlerLeagueName");
            linkedSleeperLeagueId = requireText(linkedSleeperLeagueId, "linkedSleeperLeagueId");
            linkedSleeperLeagueName = requireText(linkedSleeperLeagueName, "linkedSleeperLeagueName");
            if (linkedProviderSeason != LINKED_SEASON) throw new IllegalArgumentException("linkedProviderSeason must be " + LINKED_SEASON);
            if (targetSeason != TARGET_SEASON) throw new IllegalArgumentException("targetSeason must be " + TARGET_SEASON);
            if (distinctOwnerIds < 0 || ownerlessRosters < 0) throw new IllegalArgumentException("owner counts must not be negative");
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
            Objects.requireNonNull(state, "state must not be null");
        }

        public List<CandidateObservation> matchingCandidates() {
            return candidates.stream().filter(CandidateObservation::lineageContainsLinkedLeague).toList();
        }

        public String uniqueSuccessorSleeperLeagueId() {
            List<CandidateObservation> matching = matchingCandidates();
            return state == DiscoveryState.UNIQUE_SUCCESSOR && matching.size() == 1
                ? matching.get(0).sleeperLeagueId()
                : null;
        }
    }

    private static final class CandidateAccumulator {
        private final LeagueObservation league;
        private final Set<String> ownerIds = new TreeSet<>();

        private CandidateAccumulator(LeagueObservation league) {
            this.league = league;
        }

        private void observe(LeagueObservation observation, String ownerId) {
            if (!league.equals(observation)) {
                throw new IllegalStateException(
                    "Conflicting provider observations for Sleeper league " + league.leagueId());
            }
            ownerIds.add(requireText(ownerId, "ownerId"));
        }
    }

    private record LeagueObservation(String leagueId, String name, int season, String status) {
        private LeagueObservation {
            leagueId = requireText(leagueId, "leagueId");
            name = requireText(name, "name");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("season out of range");
            status = normalizeOptional(status);
        }
    }

    private record RosterOwnerFrame(List<String> ownerIds, int ownerlessRosters) {
        private RosterOwnerFrame {
            ownerIds = List.copyOf(ownerIds);
            if (ownerlessRosters < 0) throw new IllegalArgumentException("ownerlessRosters must not be negative");
        }
    }

    private static final class SourceFailure extends RuntimeException {
        private SourceFailure(Exception cause) {
            super(cause);
        }
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
