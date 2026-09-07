package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Read-only BF-594 provenance diagnostic for BF-593 cross-roster duplicate identities. */
public final class SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic {
    public static final String POLICY_ID =
        "sleeper-season-duplicate-roster-transaction-provenance-v1-bf593-source-read-only-no-canonicalization";
    public static final int FIRST_ROUND = 1;
    public static final int LAST_ROUND = 18;

    private final DuplicateSource duplicateSource;
    private final Source source;
    private final ObjectMapper mapper = new ObjectMapper();

    public SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.duplicateSource = (leagueId, season) ->
            new SleeperSeasonProviderPointsDuplicateRosterDiagnostic(database).diagnose(leagueId, season);
        this.source = new LiveSource();
    }

    SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic(
        DuplicateSource duplicateSource,
        Source source) {
        this.duplicateSource = Objects.requireNonNull(duplicateSource, "duplicateSource must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
    }

    public DiagnosticReport diagnose(String leagueId, int season)
        throws SQLException, IOException, InterruptedException {
        var duplicates = duplicateSource.diagnose(leagueId, season);

        Map<String, Set<Integer>> weeksByPlayer = new TreeMap<>();
        for (var duplicate : duplicates.duplicates()) {
            if (duplicate.kind() == SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DuplicateKind.INTRA_ROSTER) {
                continue;
            }
            weeksByPlayer.computeIfAbsent(duplicate.playerId(), ignored -> new TreeSet<>())
                .add(duplicate.week());
        }

        Set<String> targetPlayers = new LinkedHashSet<>(weeksByPlayer.keySet());
        Map<String, List<TransactionObservation>> transactionsByPlayer = new LinkedHashMap<>();
        for (String playerId : targetPlayers) transactionsByPlayer.put(playerId, new ArrayList<>());

        for (int round = FIRST_ROUND; round <= LAST_ROUND; round++) {
            collectTransactions(
                round,
                source.transactions(duplicates.sleeperLeagueId(), round),
                targetPlayers,
                transactionsByPlayer);
        }

        Map<String, List<Integer>> finalRostersByPlayer = finalRosterMemberships(
            source.rosters(duplicates.sleeperLeagueId()), targetPlayers);

        List<PlayerProvenance> players = new ArrayList<>();
        for (String playerId : targetPlayers) {
            List<TransactionObservation> transactions = transactionsByPlayer.get(playerId).stream()
                .sorted(Comparator
                    .comparingInt(TransactionObservation::round)
                    .thenComparing(TransactionObservation::transactionId))
                .toList();
            EvidenceState state = transactions.isEmpty()
                ? EvidenceState.NO_MATCHING_COMPLETE_TRANSACTION
                : EvidenceState.MATCHING_COMPLETE_TRANSACTION_EVIDENCE;
            players.add(new PlayerProvenance(
                playerId,
                List.copyOf(weeksByPlayer.get(playerId)),
                transactions,
                finalRostersByPlayer.getOrDefault(playerId, List.of()),
                state));
        }

        return new DiagnosticReport(
            POLICY_ID,
            duplicates.leagueId(),
            duplicates.leagueName(),
            duplicates.season(),
            duplicates.sleeperLeagueId(),
            FIRST_ROUND,
            LAST_ROUND,
            List.copyOf(players));
    }

    private void collectTransactions(
        int round,
        String json,
        Set<String> targetPlayers,
        Map<String, List<TransactionObservation>> transactionsByPlayer) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "transaction payload"));
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("Sleeper transaction payload must be a JSON array for round " + round);
        }

        for (JsonNode transaction : root) {
            String status = text(transaction.get("status"));
            if (!"complete".equals(status)) continue;

            JsonNode adds = objectOrNull(transaction.get("adds"), "adds", round);
            JsonNode drops = objectOrNull(transaction.get("drops"), "drops", round);
            for (String playerId : targetPlayers) {
                Integer addedRosterId = rosterMapping(adds, playerId);
                Integer droppedRosterId = rosterMapping(drops, playerId);
                if (addedRosterId == null && droppedRosterId == null) continue;

                transactionsByPlayer.get(playerId).add(new TransactionObservation(
                    round,
                    requireText(text(transaction.get("transaction_id")), "transaction_id"),
                    textOr(transaction.get("type"), "unknown"),
                    nullableInt(transaction.get("leg")),
                    nullableLong(transaction.get("created")),
                    nullableLong(transaction.get("status_updated")),
                    integerArray(transaction.get("roster_ids"), "roster_ids", round),
                    addedRosterId,
                    droppedRosterId));
            }
        }
    }

    private Map<String, List<Integer>> finalRosterMemberships(String json, Set<String> targetPlayers)
        throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "rosters payload"));
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("Sleeper rosters payload must be a JSON array");
        }
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        for (String playerId : targetPlayers) result.put(playerId, new ArrayList<>());

        for (JsonNode roster : root) {
            int rosterId = roster.path("roster_id").asInt(0);
            if (rosterId <= 0) throw new IllegalStateException("Missing or invalid Sleeper roster_id in rosters payload");
            JsonNode players = roster.get("players");
            if (players == null || players.isNull()) continue;
            if (!players.isArray()) throw new IllegalStateException("Sleeper roster players must be an array");
            Set<String> rosterPlayers = new LinkedHashSet<>();
            for (JsonNode player : players) {
                String id = text(player);
                if (id != null && !id.isBlank()) rosterPlayers.add(id.trim());
            }
            for (String playerId : targetPlayers) {
                if (rosterPlayers.contains(playerId)) result.get(playerId).add(rosterId);
            }
        }
        result.replaceAll((ignored, rosterIds) -> rosterIds.stream().sorted().toList());
        return result;
    }

    private static JsonNode objectOrNull(JsonNode node, String field, int round) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) {
            throw new IllegalStateException("Sleeper transaction " + field + " must be an object in round " + round);
        }
        return node;
    }

    private static Integer rosterMapping(JsonNode object, String playerId) {
        if (object == null) return null;
        JsonNode value = object.get(playerId);
        if (value == null || value.isNull()) return null;
        if (!value.canConvertToInt() || value.intValue() <= 0) {
            throw new IllegalStateException("Sleeper transaction roster mapping must be a positive integer for player " + playerId);
        }
        return value.intValue();
    }

    private static List<Integer> integerArray(JsonNode node, String field, int round) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) {
            throw new IllegalStateException("Sleeper transaction " + field + " must be an array in round " + round);
        }
        List<Integer> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.canConvertToInt() || value.intValue() <= 0) {
                throw new IllegalStateException("Sleeper transaction " + field + " must contain positive roster ids in round " + round);
            }
            result.add(value.intValue());
        }
        return result.stream().sorted().toList();
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return node.asText(null);
    }

    private static String textOr(JsonNode node, String fallback) {
        String value = text(node);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Integer nullableInt(JsonNode node) {
        return node != null && !node.isNull() && node.canConvertToInt() ? node.intValue() : null;
    }

    private static Long nullableLong(JsonNode node) {
        return node != null && !node.isNull() && node.isIntegralNumber() ? node.longValue() : null;
    }

    interface DuplicateSource {
        SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DiagnosticReport diagnose(String leagueId, int season)
            throws SQLException, IOException, InterruptedException;
    }

    interface Source {
        String transactions(String sleeperLeagueId, int round) throws IOException, InterruptedException;
        String rosters(String sleeperLeagueId) throws IOException, InterruptedException;
    }

    private static final class LiveSource implements Source {
        private final SleeperClient client = new SleeperClient();

        @Override
        public String transactions(String sleeperLeagueId, int round) throws IOException, InterruptedException {
            return client.getLeagueTransactions(sleeperLeagueId, round);
        }

        @Override
        public String rosters(String sleeperLeagueId) throws IOException, InterruptedException {
            return client.getLeagueRosters(sleeperLeagueId);
        }
    }

    public enum EvidenceState {
        MATCHING_COMPLETE_TRANSACTION_EVIDENCE,
        NO_MATCHING_COMPLETE_TRANSACTION
    }

    public record TransactionObservation(
        int round,
        String transactionId,
        String type,
        Integer leg,
        Long created,
        Long statusUpdated,
        List<Integer> rosterIds,
        Integer addedRosterId,
        Integer droppedRosterId) {
        public TransactionObservation {
            if (round < FIRST_ROUND || round > LAST_ROUND) throw new IllegalArgumentException("round out of range");
            transactionId = requireText(transactionId, "transactionId");
            type = requireText(type, "type");
            rosterIds = List.copyOf(Objects.requireNonNull(rosterIds, "rosterIds must not be null"));
            if (addedRosterId == null && droppedRosterId == null) {
                throw new IllegalArgumentException("transaction must add or drop the target player");
            }
        }
    }

    public record PlayerProvenance(
        String playerId,
        List<Integer> duplicateWeeks,
        List<TransactionObservation> transactions,
        List<Integer> finalRosterIds,
        EvidenceState state) {
        public PlayerProvenance {
            playerId = requireText(playerId, "playerId");
            duplicateWeeks = List.copyOf(Objects.requireNonNull(duplicateWeeks, "duplicateWeeks must not be null"));
            transactions = List.copyOf(Objects.requireNonNull(transactions, "transactions must not be null"));
            finalRosterIds = List.copyOf(Objects.requireNonNull(finalRosterIds, "finalRosterIds must not be null"));
            Objects.requireNonNull(state, "state must not be null");
            if ((state == EvidenceState.NO_MATCHING_COMPLETE_TRANSACTION) != transactions.isEmpty()) {
                throw new IllegalArgumentException("transaction evidence state must match transaction observations");
            }
        }
    }

    public record DiagnosticReport(
        String policyId,
        String leagueId,
        String leagueName,
        int season,
        String sleeperLeagueId,
        int firstRound,
        int lastRound,
        List<PlayerProvenance> players) {
        public DiagnosticReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            leagueId = requireText(leagueId, "leagueId");
            leagueName = requireText(leagueName, "leagueName");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            players = List.copyOf(Objects.requireNonNull(players, "players must not be null"));
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
