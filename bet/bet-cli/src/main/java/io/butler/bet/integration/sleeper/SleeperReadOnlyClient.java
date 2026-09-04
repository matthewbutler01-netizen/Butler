package io.butler.bet.integration.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** GET-only client for documented Sleeper public read endpoints. */
public final class SleeperReadOnlyClient {
    public static final URI OFFICIAL_API_BASE = URI.create("https://api.sleeper.app/v1/");
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final GetTransport transport;
    private final ObjectMapper objectMapper;

    public SleeperReadOnlyClient(GetTransport transport) {
        this(transport, new ObjectMapper());
    }

    SleeperReadOnlyClient(GetTransport transport, ObjectMapper objectMapper) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public static SleeperReadOnlyClient official() {
        return new SleeperReadOnlyClient(new JavaHttpGetTransport(HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()));
    }

    /**
     * Reads Sleeper league transactions for one week/round using the documented GET endpoint.
     * This client has no write transport surface.
     */
    public List<SleeperTransaction> transactions(String leagueId, int round)
        throws IOException, InterruptedException {
        leagueId = requireSleeperLeagueId(leagueId);
        if (round < 1 || round > 30) {
            throw new IllegalArgumentException("round must be between 1 and 30");
        }
        URI uri = OFFICIAL_API_BASE.resolve("league/" + leagueId + "/transactions/" + round);
        Response response = transport.get(uri);
        if (response.statusCode() != 200) {
            throw new IOException("Sleeper transactions GET failed with HTTP " + response.statusCode());
        }
        return parseTransactions(response.body());
    }

    private List<SleeperTransaction> parseTransactions(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) throw new IOException("Sleeper transactions response must be a JSON array");

        List<SleeperTransaction> result = new ArrayList<>();
        for (JsonNode node : root) {
            if (!node.isObject()) throw new IOException("Sleeper transaction entry must be a JSON object");
            result.add(new SleeperTransaction(
                text(node, "transaction_id"),
                text(node, "type"),
                text(node, "status"),
                nullableText(node, "creator"),
                nullableLong(node, "created"),
                nullableLong(node, "status_updated"),
                nullableInt(node, "leg"),
                intList(node.get("roster_ids")),
                intList(node.get("consenter_ids")),
                playerMap(node.get("adds")),
                playerMap(node.get("drops")),
                draftPicks(node.get("draft_picks"))));
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode node, String field) throws IOException {
        String value = nullableText(node, field);
        if (value == null || value.isBlank()) throw new IOException("Sleeper transaction missing " + field);
        return value;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Long nullableLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static List<Integer> intList(JsonNode node) throws IOException {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IOException("Sleeper transaction integer-list field must be an array");
        List<Integer> result = new ArrayList<>();
        for (JsonNode value : node) result.add(value.asInt());
        return List.copyOf(result);
    }

    private static Map<String, Integer> playerMap(JsonNode node) throws IOException {
        if (node == null || node.isNull()) return Map.of();
        if (!node.isObject()) throw new IOException("Sleeper transaction player-map field must be an object");
        Map<String, Integer> result = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!entry.getValue().canConvertToInt()) {
                throw new IOException("Sleeper transaction roster id must be an integer");
            }
            result.put(entry.getKey(), entry.getValue().asInt());
        }
        return Map.copyOf(result);
    }

    private static List<DraftPick> draftPicks(JsonNode node) throws IOException {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IOException("Sleeper draft_picks field must be an array");
        List<DraftPick> result = new ArrayList<>();
        for (JsonNode pick : node) {
            if (!pick.isObject()) throw new IOException("Sleeper draft pick must be an object");
            result.add(new DraftPick(
                text(pick, "season"),
                requiredInt(pick, "round"),
                requiredInt(pick, "roster_id"),
                requiredInt(pick, "previous_owner_id"),
                requiredInt(pick, "owner_id")));
        }
        return List.copyOf(result);
    }

    private static int requiredInt(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.canConvertToInt()) {
            throw new IOException("Sleeper object missing integer " + field);
        }
        return value.asInt();
    }

    private static String requireSleeperLeagueId(String value) {
        if (value == null || !value.matches("[0-9]+")) {
            throw new IllegalArgumentException("Sleeper leagueId must contain digits only");
        }
        return value;
    }

    /** GET is the only transport operation intentionally exposed. */
    @FunctionalInterface
    public interface GetTransport {
        Response get(URI uri) throws IOException, InterruptedException;
    }

    public record Response(int statusCode, String body) {
        public Response {
            if (statusCode < 100 || statusCode > 599) throw new IllegalArgumentException("invalid HTTP status");
            Objects.requireNonNull(body, "body must not be null");
        }
    }

    public static final class JavaHttpGetTransport implements GetTransport {
        private final HttpClient httpClient;

        public JavaHttpGetTransport(HttpClient httpClient) {
            this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        }

        @Override
        public Response get(URI uri) throws IOException, InterruptedException {
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Sleeper transport requires HTTPS");
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "Butler-FF/1.0")
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        }
    }

    public record SleeperTransaction(
        String transactionId,
        String type,
        String status,
        String creatorUserId,
        Long createdEpochMillis,
        Long statusUpdatedEpochMillis,
        Integer leg,
        List<Integer> rosterIds,
        List<Integer> consenterIds,
        Map<String, Integer> adds,
        Map<String, Integer> drops,
        List<DraftPick> draftPicks) {
        public SleeperTransaction {
            if (transactionId == null || transactionId.isBlank()) throw new IllegalArgumentException("transactionId must not be blank");
            if (type == null || type.isBlank()) throw new IllegalArgumentException("type must not be blank");
            if (status == null || status.isBlank()) throw new IllegalArgumentException("status must not be blank");
            rosterIds = List.copyOf(Objects.requireNonNull(rosterIds, "rosterIds must not be null"));
            consenterIds = List.copyOf(Objects.requireNonNull(consenterIds, "consenterIds must not be null"));
            adds = Map.copyOf(Objects.requireNonNull(adds, "adds must not be null"));
            drops = Map.copyOf(Objects.requireNonNull(drops, "drops must not be null"));
            draftPicks = List.copyOf(Objects.requireNonNull(draftPicks, "draftPicks must not be null"));
        }

        public boolean trade() {
            return "trade".equals(type);
        }
    }

    public record DraftPick(
        String season,
        int round,
        int originalRosterId,
        int previousOwnerId,
        int ownerId) {
        public DraftPick {
            if (season == null || season.isBlank()) throw new IllegalArgumentException("season must not be blank");
            if (round < 1) throw new IllegalArgumentException("round must be positive");
            if (originalRosterId < 1 || previousOwnerId < 1 || ownerId < 1) {
                throw new IllegalArgumentException("Sleeper roster ids must be positive");
            }
        }
    }
}
