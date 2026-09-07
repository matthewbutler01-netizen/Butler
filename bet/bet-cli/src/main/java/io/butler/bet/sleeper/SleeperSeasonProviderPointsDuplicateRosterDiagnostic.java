package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only BF-593 diagnostic of duplicate Sleeper roster identity observations. */
public final class SleeperSeasonProviderPointsDuplicateRosterDiagnostic {
    public static final String POLICY_ID =
        "sleeper-season-provider-points-duplicate-roster-diagnostic-v1-read-only-no-dedup";
    public static final int FIRST_WEEK = 1;
    public static final int LAST_WEEK = 18;

    private final Database database;
    private final Source source;
    private final ObjectMapper mapper = new ObjectMapper();

    public SleeperSeasonProviderPointsDuplicateRosterDiagnostic(Database database) {
        this(database, new LiveSource());
    }

    SleeperSeasonProviderPointsDuplicateRosterDiagnostic(Database database, Source source) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
    }

    public DiagnosticReport diagnose(String leagueId, int season)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        var league = new LeagueRepository(database).findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + normalizedLeagueId));
        String currentSleeperLeagueId = requireText(league.getExternalId(), "league external Sleeper id");
        var lineage = source.resolveLineage(currentSleeperLeagueId);
        String historicalSleeperLeagueId = lineage.linksNewestToOldest().stream()
            .filter(link -> link.season() == season)
            .map(SleeperLeagueLineageResolver.LeagueLink::leagueId)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Sleeper lineage from " + currentSleeperLeagueId + " does not contain season " + season));

        List<DuplicateObservation> duplicates = new ArrayList<>();
        for (int week = FIRST_WEEK; week <= LAST_WEEK; week++) {
            duplicates.addAll(diagnoseWeek(week, source.matchups(historicalSleeperLeagueId, week)));
        }
        duplicates.sort(Comparator
            .comparingInt(DuplicateObservation::week)
            .thenComparing(DuplicateObservation::playerId));

        return new DiagnosticReport(
            POLICY_ID,
            normalizedLeagueId,
            league.getName(),
            season,
            historicalSleeperLeagueId,
            FIRST_WEEK,
            LAST_WEEK,
            List.copyOf(duplicates));
    }

    private List<DuplicateObservation> diagnoseWeek(int week, String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "matchup payload"));
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("Sleeper matchup payload must be a JSON array for week " + week);
        }
        if (root.isEmpty()) return List.of();

        Map<String, List<RowObservation>> rowsByPlayer = new LinkedHashMap<>();
        for (JsonNode matchup : root) {
            int rosterId = matchup.path("roster_id").asInt(0);
            if (rosterId <= 0) {
                throw new IllegalStateException("Missing or invalid Sleeper roster_id in week " + week);
            }
            Integer matchupId = matchup.hasNonNull("matchup_id") && matchup.get("matchup_id").canConvertToInt()
                ? matchup.get("matchup_id").intValue()
                : null;
            List<String> players = identities(matchup.get("players"), "players", week);
            List<String> starters = identities(matchup.get("starters"), "starters", week);
            JsonNode points = matchup.get("players_points");
            if (points != null && !points.isNull() && !points.isObject()) {
                throw new IllegalStateException("Sleeper players_points must be an object in week " + week);
            }

            Map<String, Integer> playerCounts = counts(players);
            Map<String, Integer> starterCounts = counts(starters);
            for (var entry : playerCounts.entrySet()) {
                String playerId = entry.getKey();
                rowsByPlayer.computeIfAbsent(playerId, ignored -> new ArrayList<>())
                    .add(new RowObservation(
                        rosterId,
                        matchupId,
                        entry.getValue(),
                        starterCounts.getOrDefault(playerId, 0),
                        numericPoint(points, playerId)));
            }
        }

        List<DuplicateObservation> result = new ArrayList<>();
        for (var entry : rowsByPlayer.entrySet()) {
            String playerId = entry.getKey();
            List<RowObservation> rows = entry.getValue();
            Set<Integer> rosterIds = new LinkedHashSet<>();
            boolean intraRoster = false;
            for (RowObservation row : rows) {
                rosterIds.add(row.rosterId());
                if (row.playerOccurrences() > 1) intraRoster = true;
            }
            boolean crossRoster = rosterIds.size() > 1;
            if (!intraRoster && !crossRoster) continue;

            List<RowObservation> sortedRows = rows.stream()
                .sorted(Comparator
                    .comparingInt(RowObservation::rosterId)
                    .thenComparing(RowObservation::matchupId, Comparator.nullsLast(Integer::compareTo)))
                .toList();
            DuplicateKind kind = crossRoster && intraRoster
                ? DuplicateKind.CROSS_AND_INTRA_ROSTER
                : crossRoster ? DuplicateKind.CROSS_ROSTER : DuplicateKind.INTRA_ROSTER;
            result.add(new DuplicateObservation(week, playerId, kind, sortedRows));
        }
        return List.copyOf(result);
    }

    private static Map<String, Integer> counts(List<String> values) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String value : values) result.merge(value, 1, Integer::sum);
        return result;
    }

    private static List<String> identities(JsonNode node, String field, int week) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) {
            throw new IllegalStateException("Sleeper " + field + " must be an array in week " + week);
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            String id = value == null || value.isNull() ? null : value.asText(null);
            if (id == null || id.isBlank() || "0".equals(id.trim())) continue;
            result.add(id.trim());
        }
        return List.copyOf(result);
    }

    private static BigDecimal numericPoint(JsonNode points, String playerId) {
        if (points == null || points.isNull() || !points.isObject()) return null;
        JsonNode value = points.get(playerId);
        return value != null && value.isNumber() ? value.decimalValue() : null;
    }

    interface Source {
        SleeperLeagueLineageResolver.Lineage resolveLineage(String currentSleeperLeagueId)
            throws IOException, InterruptedException;
        String matchups(String sleeperLeagueId, int week) throws IOException, InterruptedException;
    }

    private static final class LiveSource implements Source {
        private final SleeperLeagueLineageResolver resolver = new SleeperLeagueLineageResolver();
        private final SleeperClient client = new SleeperClient();

        @Override
        public SleeperLeagueLineageResolver.Lineage resolveLineage(String currentSleeperLeagueId)
            throws IOException, InterruptedException {
            return resolver.resolve(currentSleeperLeagueId);
        }

        @Override
        public String matchups(String sleeperLeagueId, int week) throws IOException, InterruptedException {
            return client.getLeagueMatchups(sleeperLeagueId, week);
        }
    }

    public enum DuplicateKind {
        INTRA_ROSTER,
        CROSS_ROSTER,
        CROSS_AND_INTRA_ROSTER
    }

    public record RowObservation(
        int rosterId,
        Integer matchupId,
        int playerOccurrences,
        int starterOccurrences,
        BigDecimal providerPoints) {
        public RowObservation {
            if (rosterId <= 0) throw new IllegalArgumentException("rosterId must be positive");
            if (playerOccurrences <= 0) throw new IllegalArgumentException("playerOccurrences must be positive");
            if (starterOccurrences < 0) throw new IllegalArgumentException("starterOccurrences must not be negative");
        }

        public boolean inStarters() {
            return starterOccurrences > 0;
        }
    }

    public record DuplicateObservation(
        int week,
        String playerId,
        DuplicateKind kind,
        List<RowObservation> rows) {
        public DuplicateObservation {
            if (week < FIRST_WEEK || week > LAST_WEEK) throw new IllegalArgumentException("week out of range");
            playerId = requireText(playerId, "playerId");
            Objects.requireNonNull(kind, "kind must not be null");
            rows = List.copyOf(Objects.requireNonNull(rows, "rows must not be null"));
            if (rows.isEmpty()) throw new IllegalArgumentException("rows must not be empty");
        }
    }

    public record DiagnosticReport(
        String policyId,
        String leagueId,
        String leagueName,
        int season,
        String sleeperLeagueId,
        int firstWeek,
        int lastWeek,
        List<DuplicateObservation> duplicates) {
        public DiagnosticReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            leagueId = requireText(leagueId, "leagueId");
            leagueName = requireText(leagueName, "leagueName");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            duplicates = List.copyOf(Objects.requireNonNull(duplicates, "duplicates must not be null"));
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
