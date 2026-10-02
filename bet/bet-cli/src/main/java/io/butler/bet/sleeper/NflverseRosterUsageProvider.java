package io.butler.bet.sleeper;

import io.butler.bet.intelligence.NflversePlayerWeekProductionImporter;
import io.butler.bet.intelligence.NflversePlayerSeasonProductionImporter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only exact-ID usage evidence. A conservative review policy, not a calibrated points model. */
final class NflverseRosterUsageProvider {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<URI, Cached> cache = new LinkedHashMap<>();

    Map<String, UsageEvidence> load(int season, int week, Set<String> ids) throws IOException, InterruptedException {
        if (week < 3) return Map.of();
        return parse(download(NflversePlayerSeasonProductionImporter.PLAYER_IDS_URI),
            download(NflversePlayerWeekProductionImporter.statsUri(season)),
            download(snapsUri(season)), season, week, ids, Instant.now());
    }

    static URI snapsUri(int season) {
        return URI.create("https://github.com/nflverse/nflverse-data/releases/download/snap_counts/snap_counts_"
            + season + ".csv");
    }

    synchronized String download(URI uri) throws IOException, InterruptedException {
        Instant now = Instant.now();
        Cached saved = cache.get(uri);
        if (saved != null && now.isBefore(saved.expires())) return saved.body();
        var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
            .header("User-Agent", "Butler-FF/0.1").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() != 200) throw new IOException("Usage source HTTP " + response.statusCode());
            byte[] bytes = body.readNBytes(8_000_001);
            if (bytes.length > 8_000_000) throw new IOException("Usage source exceeds size limit");
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            cache.put(uri, new Cached(text, now.plus(Duration.ofMinutes(15))));
            return text;
        }
    }

    static Map<String, UsageEvidence> parse(String idsCsv, String statsCsv, String snapsCsv,
        int season, int week, Set<String> requested, Instant checkedAt) {
        if (season <= 0 || week < 3) return Map.of();
        Map<String, Set<String>> gsisCandidates = new LinkedHashMap<>();
        Map<String, Set<String>> pfrCandidates = new LinkedHashMap<>();
        for (var row : csv(idsCsv)) {
            String sleeper = value(row, "sleeper_id");
            if (sleeper.isEmpty()) continue;
            bind(gsisCandidates, value(row, "gsis_id"), sleeper);
            bind(pfrCandidates, value(row, "pfr_id"), sleeper);
        }
        Map<String, String> sleeperByGsis = exactCrosswalk(gsisCandidates, requested);
        Map<String, String> sleeperByPfr = exactCrosswalk(pfrCandidates, requested);
        Map<String, Map<Integer, Workload>> workloads = new LinkedHashMap<>();
        for (var row : csv(statsCsv)) {
            if (!matches(row, season, week, "season_type")) continue;
            String id = sleeperByGsis.get(value(row, "player_id"));
            if (id == null || !requested.contains(id)) continue;
            int rowWeek = integer(row, "week");
            Workload workload = new Workload(integer(row, "carries"), integer(row, "targets"), optionalInteger(row, "attempts"));
            if (workloads.computeIfAbsent(id, key -> new LinkedHashMap<>()).putIfAbsent(rowWeek, workload) != null)
                throw new IllegalStateException("Duplicate player/week usage row");
        }
        Map<String, Map<Integer, Snap>> snaps = new LinkedHashMap<>();
        for (var row : csv(snapsCsv)) {
            if (!matches(row, season, week, "game_type")) continue;
            String id = sleeperByPfr.get(value(row, "pfr_player_id"));
            if (id == null || !requested.contains(id)) continue;
            int rowWeek = integer(row, "week");
            double pct = Double.parseDouble(value(row, "offense_pct"));
            int count = integer(row, "offense_snaps");
            if (!Double.isFinite(pct) || pct < 0 || pct > 1) throw new IllegalStateException("Invalid snap share");
            if (snaps.computeIfAbsent(id, key -> new LinkedHashMap<>())
                .putIfAbsent(rowWeek, new Snap(count, pct)) != null)
                throw new IllegalStateException("Duplicate player/week snap row");
        }
        Map<String, UsageEvidence> result = new LinkedHashMap<>();
        for (String id : requested) {
            var playerWork = workloads.getOrDefault(id, Map.of());
            var playerSnaps = snaps.getOrDefault(id, Map.of());
            Workload previous = playerWork.get(week - 2), recent = playerWork.get(week - 1);
            Snap previousSnap = playerSnaps.get(week - 2), recentSnap = playerSnaps.get(week - 1);
            if (previous == null || recent == null || previousSnap == null || recentSnap == null) continue;
            // Deliberately conservative: both opportunity and participation must fall sharply.
            boolean hold = previous.opportunities() >= 4 && previousSnap.share() >= 0.40
                && recent.opportunities() * 2 <= previous.opportunities()
                && previousSnap.share() - recentSnap.share() >= 0.20 - 0.000001;
            String detail = "nflverse completed-week usage: week " + (week - 2) + " carries=" + previous.carries()
                + ", targets=" + previous.targets() + ", passing attempts=" + passing(previous.attempts()) + ", offensive snaps=" + previousSnap.count()
                + ", snap share=" + percent(previousSnap.share()) + "; week " + (week - 1)
                + " carries=" + recent.carries() + ", targets=" + recent.targets() + ", passing attempts=" + passing(recent.attempts())
                + ", offensive snaps=" + recentSnap.count() + ", snap share=" + percent(recentSnap.share())
                + "; checked=" + checkedAt + "; stats source=" + NflversePlayerWeekProductionImporter.statsUri(season)
                + "; snaps source=" + snapsUri(season) + "; identity source="
                + NflversePlayerSeasonProductionImporter.PLAYER_IDS_URI
                + ". Two-week observations do not establish the cause or confirm a depth-chart change."
                + " Carries plus targets is an opportunity count, not touches or QB passing workload.";
            result.put(id, new UsageEvidence(hold, detail, List.of(
                new WeekUsage(week - 2, previous.carries(), previous.targets(), previousSnap.count(), previousSnap.share(), previous.attempts()),
                new WeekUsage(week - 1, recent.carries(), recent.targets(), recentSnap.count(), recentSnap.share(), recent.attempts())), checkedAt.toString()));
        }
        return Map.copyOf(result);
    }

    private static List<Map<String, String>> csv(String text) {
        return NflversePlayerWeekProductionImporter.parseEvidenceCsv(text);
    }
    private static void bind(Map<String, Set<String>> crosswalk, String key, String sleeper) {
        if (key.isEmpty()) return;
        crosswalk.computeIfAbsent(key, unused -> new java.util.LinkedHashSet<>()).add(sleeper);
    }
    private static Map<String, String> exactCrosswalk(Map<String, Set<String>> candidates, Set<String> requested) {
        Map<String, Set<String>> providerBySleeper = new LinkedHashMap<>();
        candidates.forEach((provider, sleepers) -> sleepers.forEach(sleeper ->
            providerBySleeper.computeIfAbsent(sleeper, unused -> new java.util.LinkedHashSet<>()).add(provider)));
        Map<String, String> exact = new LinkedHashMap<>();
        candidates.forEach((provider, sleepers) -> {
            if (sleepers.size() != 1) return;
            String sleeper = sleepers.iterator().next();
            if (requested.contains(sleeper) && providerBySleeper.get(sleeper).size() == 1) exact.put(provider, sleeper);
        });
        return exact;
    }
    private static boolean matches(Map<String, String> row, int season, int week, String type) {
        return integer(row, "season") == season && "REG".equals(value(row, type))
            && integer(row, "week") >= week - 2 && integer(row, "week") < week;
    }
    private static String value(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null) throw new IllegalStateException("Usage source missing column " + key);
        return value.trim();
    }
    private static int integer(Map<String, String> row, String key) {
        try {
            int number = Integer.parseInt(value(row, key));
            if (number < 0) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException e) { throw new IllegalStateException("Invalid usage value for " + key, e); }
    }
    private static Integer optionalInteger(Map<String, String> row, String key) {
        String raw = row.get(key);
        if (raw == null || raw.isBlank() || Set.of("NA", "N/A", "null").contains(raw.trim())) return null;
        return integer(row, key);
    }
    private static String passing(Integer attempts) { return attempts == null ? "unavailable" : attempts.toString(); }
    private static String percent(double fraction) { return String.format(java.util.Locale.ROOT, "%.1f%%", fraction * 100); }
    record UsageEvidence(boolean reviewHold, String detail, List<WeekUsage> weeks, String checkedAt) {
        UsageEvidence { weeks = List.copyOf(weeks); }
        UsageEvidence(boolean reviewHold, String detail) { this(reviewHold, detail, List.of(), "not verified"); }
        boolean complete() { return weeks.size() == 2; }
        String summary() {
            if (!complete()) return "Usage not verified";
            return weeks.stream().map(w -> "Week " + w.week() + ": carries " + w.carries()
                + ", targets " + w.targets() + ", passing attempts " + passing(w.attempts()) + ", offensive snaps " + w.snaps()
                + " (" + percent(w.share()) + ")").collect(java.util.stream.Collectors.joining("; "))
                + "; checked " + checkedAt;
        }
    }
    record WeekUsage(int week, int carries, int targets, int snaps, double share, Integer attempts) {
        WeekUsage(int week, int carries, int targets, int snaps, double share) {
            this(week, carries, targets, snaps, share, null);
        }
        int opportunities() { return carries + targets; }
    }
    private record Workload(int carries, int targets, Integer attempts) { int opportunities() { return carries + targets; } }
    private record Snap(int count, double share) {}
    private record Cached(String body, Instant expires) {}
}
