package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * BF-825 bounded read-only cache of Sleeper current player availability evidence.
 *
 * <p>The full players payload is intentionally fetched only when the cache expires. Callers still
 * receive only the exact Sleeper ids they requested. No name/team fallback exists.</p>
 */
final class SleeperPlayerAvailabilityProvider {
    static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(5);

    private static final Set<String> EXPLICITLY_UNAVAILABLE = Set.of(
        "inactive",
        "out",
        "ir",
        "pup",
        "nfi",
        "suspended",
        "exempt",
        "commissioner exempt",
        "commissioner's exempt",
        "commissioners exempt");

    private final PayloadSource payloadSource;
    private final Clock clock;
    private final Duration cacheTtl;
    private final ObjectMapper mapper;

    private Map<String, PlayerAvailability> cachedByPlayerId = Map.of();
    private Instant expiresAt = Instant.EPOCH;

    SleeperPlayerAvailabilityProvider() {
        this(new SleeperClient()::getNflPlayers, Clock.systemUTC(), DEFAULT_CACHE_TTL, new ObjectMapper());
    }

    SleeperPlayerAvailabilityProvider(
        PayloadSource payloadSource,
        Clock clock,
        Duration cacheTtl,
        ObjectMapper mapper) {
        this.payloadSource = Objects.requireNonNull(payloadSource, "payloadSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        if (cacheTtl.isZero() || cacheTtl.isNegative()) {
            throw new IllegalArgumentException("cacheTtl must be positive");
        }
    }

    synchronized Map<String, PlayerAvailability> load(Set<String> sleeperPlayerIds)
        throws IOException, InterruptedException {
        Objects.requireNonNull(sleeperPlayerIds, "sleeperPlayerIds must not be null");
        if (sleeperPlayerIds.isEmpty()) return Map.of();

        Set<String> exactIds = sleeperPlayerIds.stream()
            .map(id -> requireText(id, "sleeperPlayerId"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

        Instant now = clock.instant();
        if (!now.isBefore(expiresAt)) {
            cachedByPlayerId = parse(payloadSource.load());
            expiresAt = now.plus(cacheTtl);
        }

        Map<String, PlayerAvailability> requested = new LinkedHashMap<>();
        for (String id : exactIds.stream().sorted().toList()) {
            PlayerAvailability availability = cachedByPlayerId.get(id);
            if (availability != null) requested.put(id, availability);
        }
        return Map.copyOf(requested);
    }

    private Map<String, PlayerAvailability> parse(String json) throws IOException {
        if (json == null || json.isBlank()) {
            throw new IOException("Sleeper player availability payload was blank");
        }
        JsonNode root = mapper.readTree(json);
        if (root == null || !root.isObject()) {
            throw new IOException("Sleeper player availability payload must be an object");
        }

        Map<String, PlayerAvailability> parsed = new LinkedHashMap<>();
        root.fields().forEachRemaining(entry -> {
            String id = entry.getKey();
            JsonNode node = entry.getValue();
            if (id == null || id.isBlank() || node == null || !node.isObject()) return;
            parsed.put(id, new PlayerAvailability(
                id,
                optionalText(node, "status"),
                optionalText(node, "injury_status")));
        });
        return Map.copyOf(parsed);
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : text.trim();
    }

    record PlayerAvailability(String sleeperPlayerId, String status, String injuryStatus) {
        PlayerAvailability {
            sleeperPlayerId = requireText(sleeperPlayerId, "sleeperPlayerId");
            status = clean(status);
            injuryStatus = clean(injuryStatus);
        }

        boolean explicitlyUnavailable() {
            String normalizedStatus = normalize(status);
            String normalizedInjury = normalize(injuryStatus);

            // Any supplied value that is not on the narrow explicit-unavailable allowlist is
            // ambiguous/contradictory evidence and therefore cannot unlock a missing projection.
            if (normalizedStatus != null && !EXPLICITLY_UNAVAILABLE.contains(normalizedStatus)) return false;
            if (normalizedInjury != null && !EXPLICITLY_UNAVAILABLE.contains(normalizedInjury)) return false;
            return (normalizedStatus != null && EXPLICITLY_UNAVAILABLE.contains(normalizedStatus))
                || (normalizedInjury != null && EXPLICITLY_UNAVAILABLE.contains(normalizedInjury));
        }

        String evidenceDescription() {
            return "status=" + value(status) + ", injury_status=" + value(injuryStatus);
        }

        private static String value(String value) {
            return value == null ? "<missing>" : '"' + value + '"';
        }
    }

    @FunctionalInterface
    interface PayloadSource {
        String load() throws IOException, InterruptedException;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalize(String value) {
        if (value == null) return null;
        return value.trim()
            .toLowerCase(Locale.ROOT)
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceAll("\\s+", " ");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
