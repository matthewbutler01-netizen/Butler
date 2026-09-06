package io.butler.bet.sleeper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

public final class SleeperClient {
    private static final URI DEFAULT_BASE_URI = URI.create("https://api.sleeper.app/v1/");

    private final HttpClient httpClient;
    private final URI baseUri;

    public SleeperClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build(), DEFAULT_BASE_URI);
    }

    SleeperClient(HttpClient httpClient, URI baseUri) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri must not be null");
    }

    public String getLeague(String leagueId) throws IOException, InterruptedException {
        return get("league/" + encodePath(leagueId));
    }

    public String getLeagueRosters(String leagueId) throws IOException, InterruptedException {
        return get("league/" + encodePath(leagueId) + "/rosters");
    }

    public String getLeagueMatchups(String leagueId, int week) throws IOException, InterruptedException {
        if (week <= 0) throw new IllegalArgumentException("week must be positive");
        return get("league/" + encodePath(leagueId) + "/matchups/" + week);
    }

    /** Returns Sleeper's read-only historical raw NFL stat map for one regular-season week. */
    public String getNflWeeklyStats(int season, int week) throws IOException, InterruptedException {
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        if (week <= 0 || week > 25) throw new IllegalArgumentException("week must be between 1 and 25");
        return get("stats/nfl/regular/" + season + "/" + week);
    }

    public String getLeagueUsers(String leagueId) throws IOException, InterruptedException {
        return get("league/" + encodePath(leagueId) + "/users");
    }

    public String getLeagueTradedPicks(String leagueId) throws IOException, InterruptedException {
        return get("league/" + encodePath(leagueId) + "/traded_picks");
    }

    public String getUser(String usernameOrUserId) throws IOException, InterruptedException {
        return get("user/" + encodePath(usernameOrUserId));
    }

    public String getUserLeagues(String userId, String season) throws IOException, InterruptedException {
        return get("user/" + encodePath(userId) + "/leagues/nfl/" + encodePath(season));
    }

    public String getNflPlayers() throws IOException, InterruptedException {
        return get("players/nfl");
    }

    private String get(String relativePath) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(relativePath))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SleeperApiException(response.statusCode(), request.uri(), response.body());
        }
        return response.body();
    }

    private static String encodePath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Sleeper identifier must not be blank");
        }
        return URLEncoder.encode(value.trim(), StandardCharsets.UTF_8).replace("+", "%20");
    }
}
