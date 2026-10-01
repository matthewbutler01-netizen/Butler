package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import io.butler.bet.sleeper.SleeperLiveAutoFillLineupRecommendation.ExpertPick;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** One publisher's explicit weekly selections; no article prose or inferred consensus. */
final class NflExpertPickProvider {
    private static final Map<String, String> SLUGS = Map.of("QB", "quarterbacks", "RB", "running-backs",
        "WR", "wide-receivers", "TE", "tight-ends");
    private final NflverseRosterUsageProvider downloads = new NflverseRosterUsageProvider();

    List<ExpertPick> load(int season, int week, List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players)
        throws InterruptedException {
        List<ExpertPick> result = new ArrayList<>();
        for (String position : List.of("QB", "RB", "WR", "TE")) {
            var group = players.stream().filter(p -> position.equals(p.position())).toList();
            if (group.isEmpty()) continue;
            URI uri = source(season, week, position);
            try {
                result.addAll(parse(downloads.download(uri), uri, season, week, position, players, Instant.now()));
            } catch (IOException | IllegalStateException e) {
                for (var player : group) result.add(gap(player, uri, Instant.now(), "Weekly source unavailable or could not be validated."));
            }
        }
        return List.copyOf(result);
    }

    static URI source(int season, int week, String position) {
        return URI.create("https://www.nfl.com/news/nfl-fantasy-" + season + "-start-em-sit-em-"
            + SLUGS.get(position) + "-for-week-" + week);
    }

    static List<ExpertPick> parse(String html, URI uri, int season, int week, String position,
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players, Instant now) throws IOException {
        if (!source(season, week, position).equals(uri) || html == null || html.length() > 2_000_000)
            throw new IllegalStateException("Invalid expert source frame or size");
        var scripts = Pattern.compile("(?is)<script\\b[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>").matcher(html);
        JsonNode article = null;
        while (scripts.find()) {
            var node = new ObjectMapper().readTree(scripts.group(1));
            if ("NewsArticle".equals(node.path("@type").asText())) {
                if (article != null) throw new IllegalStateException("Ambiguous expert article metadata");
                article = node;
            }
        }
        if (article == null || !uri.toString().equals(article.path("url").asText())
            || !("NFL Fantasy " + season + " Start 'Em, Sit 'Em: " + label(position)
                + " for Week " + week).equals(article.path("headline").asText()))
            throw new IllegalStateException("Expert article does not match requested season/week/position");
        var authors = article.path("author");
        if (!authors.isArray() || authors.size() != 1 || !"Person".equals(authors.get(0).path("@type").asText()))
            throw new IllegalStateException("Expert author unverified");
        String author = authors.get(0).path("name").asText().trim();
        if (author.isEmpty() || author.length() > 100) throw new IllegalStateException("Invalid expert author");
        Instant published = timestamp(article.path("datePublished").asText());
        Instant modified = timestamp(article.path("dateModified").asText());
        if (published.isAfter(now) || modified.isAfter(now) || modified.isBefore(published)
            || published.isBefore(now.minus(Duration.ofDays(7))))
            throw new IllegalStateException("Expert article is stale or has invalid timestamps");
        // Only visible named player cards under explicit h2 sections qualify; body mentions do not.
        String visible = html.replaceAll("(?is)<script\\b[^>]*>.*?</script>", "")
            .replaceAll("(?is)<style\\b[^>]*>.*?</style>", "");
        var tokens = Pattern.compile("(?is)<h2\\b[^>]*>(.*?)</h2>|<a\\b([^>]*aria-label=[\"']View details for [^>]+)>(.*?)</a>").matcher(visible);
        Map<String, String> selections = new LinkedHashMap<>();
        String section = null; boolean start = false, sit = false;
        while (tokens.find()) {
            if (tokens.group(1) != null) {
                String heading = plain(tokens.group(1));
                section = "Start 'Em".equals(heading) ? "START" : "Sit 'Em".equals(heading) ? "SIT" : null;
                if ("START".equals(section)) start = true;
                if ("SIT".equals(section)) sit = true;
                continue;
            }
            if (section == null) continue;
            String attrs = tokens.group(2);
            var nameMatch = Pattern.compile("aria-label=[\"']View details for ([^\"']+)[\"']").matcher(attrs);
            var linkMatch = Pattern.compile("href=[\"']([^\"']+)[\"']").matcher(attrs);
            if (!nameMatch.find() || !linkMatch.find()) continue;
            String link = linkMatch.group(1);
            if (!(link.startsWith("/players/") || link.startsWith("https://www.nfl.com/players/"))) continue;
            String name = plain(nameMatch.group(1));
            if (selections.putIfAbsent(name.toLowerCase(Locale.ROOT), section) != null)
                throw new IllegalStateException("Duplicate or conflicting expert selection");
        }
        if (!start || !sit || selections.isEmpty()) throw new IllegalStateException("Explicit expert selection structure missing");
        List<ExpertPick> result = new ArrayList<>();
        for (var player : players) {
            if (!position.equals(player.position())) continue;
            String name = player.displayName();
            boolean unique = name != null && players.stream().filter(p -> name.equalsIgnoreCase(p.displayName())).count() == 1;
            String selection = unique ? selections.get(name.toLowerCase(Locale.ROOT)) : null;
            result.add(selection == null ? gap(player, uri, now, unique ? "No explicit selection for this player in the validated weekly column."
                : "Roster name identity missing or ambiguous; selection not matched.")
                : new ExpertPick(player.sleeperPlayerId(), name, position, selection, author, published.toString(),
                    modified.toString(), now.toString(), uri.toString(), "Exact unique roster-name match to an explicit player card. One author's view; scoring/roster fit requires review. Existing holds remain."));
        }
        return List.copyOf(result);
    }
    private static ExpertPick gap(SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer p, URI uri, Instant now, String reason) {
        return new ExpertPick(p.sleeperPlayerId(), p.displayName(), p.position(), "UNVERIFIED", "", "", "",
            now.toString(), uri.toString(), reason + " No start/sit conclusion inferred.");
    }
    private static Instant timestamp(String text) {
        try { return Instant.parse(text); } catch (RuntimeException e) { throw new IllegalStateException("Expert date unverified", e); }
    }
    private static String label(String position) {
        return switch (position) { case "QB" -> "Quarterbacks"; case "RB" -> "Running backs";
            case "WR" -> "Wide receivers"; case "TE" -> "Tight ends"; default -> "unsupported"; };
    }
    private static String plain(String text) {
        return text.replaceAll("<[^>]+>", "").replace("&#x27;", "'").replace("&#39;", "'")
            .replace("&apos;", "'").replace("&amp;", "&").replace("&nbsp;", " ").trim();
    }
}
