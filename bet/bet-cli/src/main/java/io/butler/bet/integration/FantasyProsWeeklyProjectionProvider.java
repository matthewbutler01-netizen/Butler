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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * BF-800 read-only FantasyPros weekly projection provider.
 *
 * <p>The API key is supplied only through {@value #API_KEY_ENV}. It is never stored in Butler,
 * included in a URI, or copied into an exception message.</p>
 */
public final class FantasyProsWeeklyProjectionProvider {
    public static final String API_KEY_ENV = "BUTLER_FANTASYPROS_API_KEY";
    public static final String SOURCE_NAME = "FantasyPros weekly consensus projections";
    public static final URI DEFAULT_BASE_URI = URI.create("https://api.fantasypros.com/public/v2/json/");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String POSITIONS = "QB:RB:WR:TE:DST:K";

    private final Source source;
    private final ObjectMapper mapper = new ObjectMapper(
        JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    public FantasyProsWeeklyProjectionProvider() {
        this(new HttpSource(DEFAULT_BASE_URI, System.getenv(API_KEY_ENV)));
    }

    FantasyProsWeeklyProjectionProvider(Source source) {
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
        return parse(source.fetch(season, week, scoring), season, week, scoring);
    }

    ProjectionSnapshot parse(String json, int expectedSeason, int expectedWeek, ScoringBasis scoring)
        throws IOException {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("FantasyPros projection response is empty");
        }
        JsonNode root = mapper.readTree(json);
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("FantasyPros projection response must be a JSON object");
        }

        int season = parseInt(root.get("season"), "season");
        int week = parseInt(root.get("week"), "week");
        if (season != expectedSeason) {
            throw new IllegalStateException("FantasyPros projection season does not match requested season");
        }
        if (week != expectedWeek) {
            throw new IllegalStateException("FantasyPros projection week does not match requested week");
        }

        String returnedScoring = text(root.get("scoring"));
        if (returnedScoring != null && !returnedScoring.isBlank()
            && !scoring.name().equalsIgnoreCase(returnedScoring.trim())) {
            throw new IllegalStateException("FantasyPros projection scoring basis does not match requested basis");
        }

        JsonNode players = root.get("players");
        if (players == null || !players.isArray()) {
            throw new IllegalStateException("FantasyPros projection response has no players array");
        }

        List<Projection> projections = new ArrayList<>();
        Set<String> fantasyProsIds = new HashSet<>();
        for (JsonNode player : players) {
            if (player == null || !player.isObject()) {
                throw new IllegalStateException("FantasyPros projection player row must be an object");
            }
            String fpid = identifier(player.get("fpid"), "fpid");
            if (!fantasyProsIds.add(fpid)) {
                throw new IllegalStateException("FantasyPros projection response contains duplicate fpid " + fpid);
            }
            String name = requireText(text(player.get("name")), "name");
            String position = requireText(text(player.get("position_id")), "position_id").toUpperCase(Locale.ROOT);
            String team = normalizeOptional(text(player.get("team_id")));
            JsonNode stats = player.get("stats");
            if (stats == null || !stats.isObject()) {
                throw new IllegalStateException("FantasyPros projection row has no stats object for " + name);
            }
            String pointsField = pointsField(scoring, position);
            JsonNode pointsNode = stats.get(pointsField);
            if ((pointsNode == null || !pointsNode.isNumber()) && !"points".equals(pointsField)) {
                // Reception scoring does not alter QB/K/DST scoring and FantasyPros may expose only points.
                if (!isReceptionScoredPosition(position)) pointsNode = stats.get("points");
            }
            if (pointsNode == null || !pointsNode.isNumber()) {
                throw new IllegalStateException(
                    "FantasyPros projection row is missing " + pointsField + " for " + name + " (" + position + ")");
            }
            projections.add(new Projection(fpid, name, position, team, pointsNode.decimalValue()));
        }
        projections.sort(Comparator.comparing(Projection::fantasyProsPlayerId));
        return new ProjectionSnapshot(
            SOURCE_NAME,
            "nfl/" + season + "/projections?week=" + week + "&positions=" + POSITIONS + "&scoring=" + scoring.name(),
            season,
            week,
            scoring,
            List.copyOf(projections));
    }

    private static String pointsField(ScoringBasis scoring, String position) {
        if (!isReceptionScoredPosition(position)) return "points";
        return switch (scoring) {
            case STD -> "points";
            case HALF -> "points_half";
            case PPR -> "points_ppr";
        };
    }

    private static boolean isReceptionScoredPosition(String position) {
        return "RB".equals(position) || "WR".equals(position) || "TE".equals(position);
    }

    private static int parseInt(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            throw new IllegalStateException("FantasyPros projection response is missing " + field);
        }
        if (node.canConvertToInt()) return node.intValue();
        try {
            return Integer.parseInt(node.asText().trim());
        } catch (RuntimeException e) {
            throw new IllegalStateException("FantasyPros projection response has invalid " + field, e);
        }
    }

    private static String identifier(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            throw new IllegalStateException("FantasyPros projection row is missing " + field);
        }
        return requireText(node.asText(), field);
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
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
                "AutoFill supports FantasyPros STD/HALF/PPR projection bases only; Sleeper reception scoring is "
                    + receptionPoints);
        }
    }

    public record Projection(
        String fantasyProsPlayerId,
        String name,
        String position,
        String team,
        BigDecimal projectedPoints) {
        public Projection {
            fantasyProsPlayerId = requireText(fantasyProsPlayerId, "fantasyProsPlayerId");
            name = requireText(name, "name");
            position = requireText(position, "position").toUpperCase(Locale.ROOT);
            team = normalizeOptional(team);
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
            sourceName = requireText(sourceName, "sourceName");
            sourceSurface = requireText(sourceSurface, "sourceSurface");
            if (season <= 0) throw new IllegalArgumentException("season must be positive");
            if (week <= 0) throw new IllegalArgumentException("week must be positive");
            Objects.requireNonNull(scoring, "scoring must not be null");
            projections = List.copyOf(Objects.requireNonNull(projections, "projections must not be null"));
        }
    }

    @FunctionalInterface
    interface Source {
        String fetch(int season, int week, ScoringBasis scoring) throws IOException, InterruptedException;
    }

    private static final class HttpSource implements Source {
        private final URI baseUri;
        private final String apiKey;
        private final HttpClient client;

        private HttpSource(URI baseUri, String apiKey) {
            this.baseUri = Objects.requireNonNull(baseUri, "baseUri must not be null");
            this.apiKey = apiKey == null ? null : apiKey.trim();
            this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        }

        @Override
        public String fetch(int season, int week, ScoringBasis scoring) throws IOException, InterruptedException {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException(
                    "FantasyPros API key is not configured; set " + API_KEY_ENV + " outside the repository");
            }
            String path = "nfl/" + season + "/projections?week=" + week
                + "&positions=QB:RB:WR:TE:DST:K&scoring=" + scoring.name();
            URI uri = baseUri.resolve(path);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("x-api-key", apiKey)
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("FantasyPros projection request failed with HTTP " + response.statusCode());
            }
            return response.body();
        }
    }
}
