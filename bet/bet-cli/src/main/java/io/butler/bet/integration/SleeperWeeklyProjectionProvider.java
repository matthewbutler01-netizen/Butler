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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * BF-822 read-only weekly projection evidence from Sleeper's public projection surface.
 *
 * <p>No credential is required. Butler accepts only exact season/week rows with a Sleeper player
 * id and a numeric points field for the league scoring basis. Duplicate ids, malformed payloads,
 * HTTP failures, or incomplete roster coverage are evidence gaps; Butler does not synthesize or
 * guess projections.</p>
 */
public final class SleeperWeeklyProjectionProvider {
    public static final String SOURCE_NAME = "Sleeper weekly projections";
    public static final URI DEFAULT_BASE_URI = URI.create("https://api.sleeper.com/projections/nfl/");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final Source source;
    private final ObjectMapper mapper = new ObjectMapper(
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    public SleeperWeeklyProjectionProvider() {
        this(new HttpSource(DEFAULT_BASE_URI));
    }

    SleeperWeeklyProjectionProvider(Source source) {
        this.source = Objects.requireNonNull(source, "source must not be null");
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
        return parse(source.fetch(season, week), season, week, scoring);
    }

    ProjectionSnapshot parse(String json, int expectedSeason, int expectedWeek, ScoringBasis scoring)
        throws IOException {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("weekly projection response is empty");
        }
        JsonNode root = mapper.readTree(json);
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("weekly projection response must be a JSON array");
        }

        String pointsField = pointsField(scoring);
        List<Projection> projections = new ArrayList<>();
        Set<String> playerIds = new HashSet<>();
        for (JsonNode row : root) {
            if (row == null || !row.isObject()) continue;
            String playerId = text(row.get("player_id"));
            if (playerId == null || playerId.isBlank()) continue;
            playerId = playerId.trim();

            Integer rowWeek = integer(row.get("week"));
            Integer rowSeason = integer(row.get("season"));
            if (rowWeek != null && rowWeek != expectedWeek) continue;
            if (rowSeason != null && rowSeason != expectedSeason) continue;
            String seasonType = text(row.get("season_type"));
            if (seasonType != null && !seasonType.isBlank() && !"regular".equalsIgnoreCase(seasonType.trim())) continue;

            JsonNode stats = row.get("stats");
            if (stats == null || !stats.isObject()) continue;
            JsonNode points = stats.get(pointsField);
            if (points == null || !points.isNumber()) continue;
            if (!playerIds.add(playerId)) {
                throw new IllegalStateException("weekly projection response contains duplicate player_id " + playerId);
            }
            projections.add(new Projection(playerId, points.decimalValue()));
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
            List.copyOf(projections));
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
        List<Projection> projections) {
        public ProjectionSnapshot {
            if (sourceName == null || sourceName.isBlank()) throw new IllegalArgumentException("sourceName must not be blank");
            if (sourceSurface == null || sourceSurface.isBlank()) throw new IllegalArgumentException("sourceSurface must not be blank");
            sourceName = sourceName.trim();
            sourceSurface = sourceSurface.trim();
            if (season <= 0) throw new IllegalArgumentException("season must be positive");
            if (week <= 0) throw new IllegalArgumentException("week must be positive");
            Objects.requireNonNull(scoring, "scoring must not be null");
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
