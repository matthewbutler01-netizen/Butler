package io.butler.bet.integration;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * BF-822 read-only weekly projection evidence from Sleeper's projection surface.
 *
 * <p>No credential is required. Butler accepts exact Sleeper player identities from either the
 * list-shaped or player-id-keyed payload envelopes observed on the projection surface. The
 * requested scoring basis must be present as its exact points field; Butler never substitutes a
 * standard-scoring value for half-PPR or PPR. Duplicate or contradictory identities, malformed
 * payloads, HTTP failures, or incomplete roster coverage remain evidence gaps.</p>
 */
public final class SleeperWeeklyProjectionProvider {
    public static final String SOURCE_NAME = "Sleeper weekly projections";
    public static final URI DEFAULT_BASE_URI = URI.create("https://api.sleeper.com/projections/nfl/");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final Source source;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper(
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    public SleeperWeeklyProjectionProvider() {
        this(new HttpSource(DEFAULT_BASE_URI), Clock.systemUTC());
    }

    SleeperWeeklyProjectionProvider(Source source) {
        this(source, Clock.systemUTC());
    }

    SleeperWeeklyProjectionProvider(Source source, Clock clock) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public ProjectionSnapshot load(int season, int week, ScoringBasis scoring)
        throws IOException, InterruptedException {
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        if (week <= 0 || week > 25) {
            throw new IllegalArgumentException("week must be between 1 and 25");
        }
        Objects.requireNonNull(scoring, "scoring must not be null");
        return parse(source.fetch(season, week), season, week, scoring, clock.instant());
    }

    ProjectionSnapshot parse(String json, int expectedSeason, int expectedWeek, ScoringBasis scoring)
        throws IOException {
        return parse(json, expectedSeason, expectedWeek, scoring, clock.instant());
    }

    private ProjectionSnapshot parse(
        String json,
        int expectedSeason,
        int expectedWeek,
        ScoringBasis scoring,
        Instant observedAt) throws IOException {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("weekly projection response is empty");
        }
        JsonNode root = mapper.readTree(json);
        if (root == null || (!root.isArray() && !root.isObject())) {
            throw new IllegalStateException("weekly projection response must be a JSON array or player-id object");
        }

        String pointsField = pointsField(scoring);
        List<Projection> projections = new ArrayList<>();
        Set<String> playerIds = new HashSet<>();

        if (root.isArray()) {
            for (JsonNode row : root) {
                if (row == null || !row.isObject()) continue;
                String playerId = clean(text(row.get("player_id")));
                if (playerId == null) continue;
                addProjection(row, playerId, expectedSeason, expectedWeek, pointsField, playerIds, projections);
            }
        } else {
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String playerId = clean(entry.getKey());
                JsonNode row = entry.getValue();
                if (playerId == null || row == null || !row.isObject()) continue;

                String embeddedPlayerId = clean(text(row.get("player_id")));
                if (embeddedPlayerId != null && !playerId.equals(embeddedPlayerId)) {
                    throw new IllegalStateException(
                        "weekly projection response has contradictory player identity " + playerId
                            + " vs " + embeddedPlayerId);
                }
                addProjection(row, playerId, expectedSeason, expectedWeek, pointsField, playerIds, projections);
            }
        }

        if (projections.isEmpty()) {
            throw new IllegalStateException("weekly projection response has no usable " + pointsField + " evidence");
        }
        return new ProjectionSnapshot(
            SOURCE_NAME,
            "projections/nfl/" + expectedSeason + "/" + expectedWeek + "?season_type=regular",
            expectedSeason,
            expectedWeek,
            scoring,
            observedAt,
            List.copyOf(projections));
    }

    private static void addProjection(
        JsonNode row,
        String playerId,
        int expectedSeason,
        int expectedWeek,
        String pointsField,
        Set<String> playerIds,
        List<Projection> projections) {
        if (!matchesIntegerField(row, "week", expectedWeek)
            || !matchesIntegerField(row, "season", expectedSeason)) {
            return;
        }
        String seasonType = clean(text(row.get("season_type")));
        if (seasonType != null && !"regular".equalsIgnoreCase(seasonType)) {
            return;
        }

        JsonNode nestedStats = row.get("stats");
        JsonNode stats = nestedStats != null && nestedStats.isObject() ? nestedStats : row;
        JsonNode points = stats.get(pointsField);
        if (points == null || !points.isNumber()) {
            return;
        }
        if (!playerIds.add(playerId)) {
            throw new IllegalStateException("weekly projection response contains duplicate player_id " + playerId);
        }
        projections.add(new Projection(playerId, points.decimalValue()));
    }

    private static boolean matchesIntegerField(JsonNode row, String fieldName, int expected) {
        JsonNode node = row.get(fieldName);
        if (node == null || node.isNull()) return true;
        Integer parsed = integer(node);
        return parsed != null && parsed == expected;
    }

    private static String pointsField(ScoringBasis scoring) {
        return switch (scoring) {
            case STD -> "pts_std";
            case HALF -> "pts_half_ppr";
            case PPR -> "pts_ppr";
        };
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static Integer integer(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.canConvertToInt()) return node.intValue();
        try {
            String value = node.asText();
            return value == null || value.isBlank() ? null : Integer.parseInt(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    public enum ScoringBasis {
        STD,
        HALF,
        PPR;

        public static ScoringBasis fromReceptionPoints(Double receptionPoints) {
            if (receptionPoints == null || !Double.isFinite(receptionPoints)) {
                throw new IllegalStateException("AutoFill requires a finite persisted Sleeper reception scoring rule");
            }
            if (Double.compare(receptionPoints, 0.0d) == 0) return STD;
            if (Double.compare(receptionPoints, 0.5d) == 0) return HALF;
            if (Double.compare(receptionPoints, 1.0d) == 0) return PPR;
            throw new IllegalStateException(
                "AutoFill supports STD/HALF/PPR weekly projection bases only; Sleeper reception scoring is "
                    + receptionPoints);
        }
    }

    public record Projection(String sleeperPlayerId, BigDecimal projectedPoints) {
        public Projection {
            if (sleeperPlayerId == null || sleeperPlayerId.isBlank()) {
                throw new IllegalArgumentException("sleeperPlayerId must not be blank");
            }
            sleeperPlayerId = sleeperPlayerId.trim();
            Objects.requireNonNull(projectedPoints, "projectedPoints must not be null");
        }
    }

    public record ProjectionSnapshot(
        String sourceName,
        String sourceSurface,
        int season,
        int week,
        ScoringBasis scoring,
        Instant observedAt,
        List<Projection> projections) {
        public ProjectionSnapshot {
            if (sourceName == null || sourceName.isBlank()) throw new IllegalArgumentException("sourceName must not be blank");
            if (sourceSurface == null || sourceSurface.isBlank()) throw new IllegalArgumentException("sourceSurface must not be blank");
            sourceName = sourceName.trim();
            sourceSurface = sourceSurface.trim();
            if (season <= 0) throw new IllegalArgumentException("season must be positive");
            if (week <= 0) throw new IllegalArgumentException("week must be positive");
            Objects.requireNonNull(scoring, "scoring must not be null");
            Objects.requireNonNull(observedAt, "observedAt must not be null");
            projections = List.copyOf(Objects.requireNonNull(projections, "projections must not be null"));
        }
    }

    @FunctionalInterface
    interface Source {
        String fetch(int season, int week) throws IOException, InterruptedException;
    }

    private static final class HttpSource implements Source {
        private final URI baseUri;
        private final HttpClient client;

        private HttpSource(URI baseUri) {
            this.baseUri = Objects.requireNonNull(baseUri, "baseUri must not be null");
            this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        }

        @Override
        public String fetch(int season, int week) throws IOException, InterruptedException {
            URI uri = baseUri.resolve(season + "/" + week + "?season_type=regular");
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("weekly projection request failed with HTTP " + response.statusCode());
            }
            return response.body();
        }
    }
}
