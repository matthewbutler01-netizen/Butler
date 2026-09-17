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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * BF-822/BF-826 read-only weekly projection evidence from Sleeper's projection surface.
 *
 * <p>BF-826 keeps Sleeper's precomputed points as the primary evidence. When an exact current
 * player row is present but the requested precomputed points field is missing, the production
 * path may score that exact row from Sleeper's raw projected stats and the league's persisted
 * Sleeper scoring settings. No historical average, zero projection, fuzzy identity, or stale
 * frame is substituted.</p>
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

    /** BF-822 compatibility path: the requested precomputed points field remains mandatory. */
    public ProjectionSnapshot load(int season, int week, ScoringBasis scoring)
        throws IOException, InterruptedException {
        validateRequest(season, week, scoring);
        return parse(
            source.fetch(season, week), season, week, scoring, Map.of(), clock.instant(), true);
    }

    /** BF-826 production path: exact raw projected stats may be league-scored when needed. */
    public ProjectionSnapshot load(
        int season,
        int week,
        ScoringBasis scoring,
        Map<String, Double> leagueScoringSettings) throws IOException, InterruptedException {
        validateRequest(season, week, scoring);
        Objects.requireNonNull(leagueScoringSettings, "leagueScoringSettings must not be null");
        return parse(
            source.fetch(season, week), season, week, scoring,
            Map.copyOf(leagueScoringSettings), clock.instant(), false);
    }

    ProjectionSnapshot parse(String json, int expectedSeason, int expectedWeek, ScoringBasis scoring)
        throws IOException {
        return parse(json, expectedSeason, expectedWeek, scoring, Map.of(), clock.instant(), true);
    }

    ProjectionSnapshot parse(
        String json,
        int expectedSeason,
        int expectedWeek,
        ScoringBasis scoring,
        Map<String, Double> leagueScoringSettings) throws IOException {
        Objects.requireNonNull(leagueScoringSettings, "leagueScoringSettings must not be null");
        return parse(
            json, expectedSeason, expectedWeek, scoring,
            Map.copyOf(leagueScoringSettings), clock.instant(), false);
    }

    private ProjectionSnapshot parse(
        String json,
        int expectedSeason,
        int expectedWeek,
        ScoringBasis scoring,
        Map<String, Double> leagueScoringSettings,
        Instant observedAt,
        boolean requirePrecomputedPoints) throws IOException {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("weekly projection response is empty");
        }
        JsonNode root = mapper.readTree(json);
        if (root == null || (!root.isArray() && !root.isObject())) {
            throw new IllegalStateException("weekly projection response must be a JSON array or player-id object");
        }

        String pointsField = pointsField(scoring);
        List<Projection> projections = new ArrayList<>();
        List<ProjectionGap> gaps = new ArrayList<>();
        Set<String> playerIds = new HashSet<>();

        if (root.isArray()) {
            for (JsonNode row : root) {
                if (row == null || !row.isObject()) continue;
                String playerId = clean(text(row.get("player_id")));
                if (playerId == null) continue;
                addProjection(
                    row, playerId, expectedSeason, expectedWeek, pointsField,
                    leagueScoringSettings, requirePrecomputedPoints, playerIds, projections, gaps);
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
                addProjection(
                    row, playerId, expectedSeason, expectedWeek, pointsField,
                    leagueScoringSettings, requirePrecomputedPoints, playerIds, projections, gaps);
            }
        }

        if (projections.isEmpty() && (requirePrecomputedPoints || gaps.isEmpty())) {
            throw new IllegalStateException("weekly projection response has no usable " + pointsField + " evidence");
        }
        return new ProjectionSnapshot(
            SOURCE_NAME,
            "projections/nfl/" + expectedSeason + "/" + expectedWeek + "?season_type=regular",
            expectedSeason,
            expectedWeek,
            scoring,
            observedAt,
            List.copyOf(projections),
            List.copyOf(gaps));
    }

    private static void addProjection(
        JsonNode row,
        String playerId,
        int expectedSeason,
        int expectedWeek,
        String pointsField,
        Map<String, Double> leagueScoringSettings,
        boolean requirePrecomputedPoints,
        Set<String> playerIds,
        List<Projection> projections,
        List<ProjectionGap> gaps) {
        if (!matchesIntegerField(row, "week", expectedWeek)
            || !matchesIntegerField(row, "season", expectedSeason)) {
            return;
        }
        String seasonType = clean(text(row.get("season_type")));
        if (seasonType != null && !"regular".equalsIgnoreCase(seasonType)) {
            return;
        }
        if (!playerIds.add(playerId)) {
            throw new IllegalStateException("weekly projection response contains duplicate player_id " + playerId);
        }

        JsonNode nestedStats = row.get("stats");
        JsonNode stats = nestedStats != null && nestedStats.isObject() ? nestedStats : row;
        JsonNode points = stats.get(pointsField);
        if (points != null && points.isNumber()) {
            projections.add(new Projection(
                playerId,
                points.decimalValue(),
                ProjectionProvenance.SLEEPER_PRECOMPUTED,
                List.of(pointsField)));
            return;
        }

        if (requirePrecomputedPoints) return;

        RawScore rawScore = scoreRawProjection(stats, leagueScoringSettings);
        if (rawScore.ready()) {
            projections.add(new Projection(
                playerId,
                rawScore.points(),
                ProjectionProvenance.SLEEPER_RAW_STATS_LEAGUE_SCORED,
                rawScore.scoringKeys()));
            return;
        }

        gaps.add(new ProjectionGap(
            playerId,
            "exact Sleeper projection row is present but " + pointsField
                + " is missing/non-numeric; " + rawScore.reason()));
    }

    private static RawScore scoreRawProjection(JsonNode stats, Map<String, Double> leagueScoringSettings) {
        if (leagueScoringSettings == null || leagueScoringSettings.isEmpty()) {
            return RawScore.unavailable("persisted league scoring rules are unavailable for exact raw-stat scoring");
        }
        if (stats == null || !stats.isObject()) {
            return RawScore.unavailable("raw projected stats are unavailable");
        }

        BigDecimal points = BigDecimal.ZERO;
        Set<String> matchedScoringKeys = new LinkedHashSet<>();
        int nonZeroRules = 0;
        for (Map.Entry<String, Double> entry : leagueScoringSettings.entrySet()) {
            String key = clean(entry.getKey());
            Double multiplier = entry.getValue();
            if (key == null || multiplier == null || !Double.isFinite(multiplier)) {
                return RawScore.unavailable("persisted league scoring contains a malformed rule");
            }
            if (Double.compare(multiplier, 0.0d) == 0) continue;
            nonZeroRules++;

            JsonNode stat = stats.get(key);
            if (stat == null || stat.isNull()) {
                continue; // Sleeper projection rows are sparse; an absent exact stat contributes zero.
            }
            if (!stat.isNumber()) {
                return RawScore.unavailable("raw projected stat " + key + " is non-numeric");
            }
            matchedScoringKeys.add(key);
            points = points.add(stat.decimalValue().multiply(BigDecimal.valueOf(multiplier)));
        }

        if (nonZeroRules == 0) {
            return RawScore.unavailable("persisted league scoring has no non-zero rules");
        }
        if (matchedScoringKeys.isEmpty()) {
            return RawScore.unavailable(
                "raw projected stats contain no numeric fields matching the persisted league scoring rules");
        }
        return RawScore.ready(points, List.copyOf(matchedScoringKeys));
    }

    private static void validateRequest(int season, int week, ScoringBasis scoring) {
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        if (week <= 0 || week > 25) {
            throw new IllegalArgumentException("week must be between 1 and 25");
        }
        Objects.requireNonNull(scoring, "scoring must not be null");
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

    public enum ProjectionProvenance {
        SLEEPER_PRECOMPUTED,
        SLEEPER_RAW_STATS_LEAGUE_SCORED
    }

    public record Projection(
        String sleeperPlayerId,
        BigDecimal projectedPoints,
        ProjectionProvenance provenance,
        List<String> scoringKeys) {
        public Projection(String sleeperPlayerId, BigDecimal projectedPoints) {
            this(
                sleeperPlayerId,
                projectedPoints,
                ProjectionProvenance.SLEEPER_PRECOMPUTED,
                List.of());
        }

        public Projection {
            if (sleeperPlayerId == null || sleeperPlayerId.isBlank()) {
                throw new IllegalArgumentException("sleeperPlayerId must not be blank");
            }
            sleeperPlayerId = sleeperPlayerId.trim();
            Objects.requireNonNull(projectedPoints, "projectedPoints must not be null");
            Objects.requireNonNull(provenance, "provenance must not be null");
            scoringKeys = List.copyOf(Objects.requireNonNull(scoringKeys, "scoringKeys must not be null"));
        }
    }

    public record ProjectionGap(String sleeperPlayerId, String reason) {
        public ProjectionGap {
            if (sleeperPlayerId == null || sleeperPlayerId.isBlank()) {
                throw new IllegalArgumentException("sleeperPlayerId must not be blank");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
            sleeperPlayerId = sleeperPlayerId.trim();
            reason = reason.trim();
        }
    }

    public record ProjectionSnapshot(
        String sourceName,
        String sourceSurface,
        int season,
        int week,
        ScoringBasis scoring,
        Instant observedAt,
        List<Projection> projections,
        List<ProjectionGap> gaps) {
        public ProjectionSnapshot(
            String sourceName,
            String sourceSurface,
            int season,
            int week,
            ScoringBasis scoring,
            Instant observedAt,
            List<Projection> projections) {
            this(sourceName, sourceSurface, season, week, scoring, observedAt, projections, List.of());
        }

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
            gaps = List.copyOf(Objects.requireNonNull(gaps, "gaps must not be null"));
        }
    }

    private record RawScore(boolean ready, BigDecimal points, List<String> scoringKeys, String reason) {
        private static RawScore ready(BigDecimal points, List<String> scoringKeys) {
            return new RawScore(true, points, scoringKeys, null);
        }

        private static RawScore unavailable(String reason) {
            return new RawScore(false, null, List.of(), reason);
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
