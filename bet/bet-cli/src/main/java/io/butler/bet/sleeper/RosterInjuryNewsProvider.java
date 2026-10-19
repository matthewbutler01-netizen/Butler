package io.butler.bet.sleeper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Bounded public RSS headlines; review evidence only, never an official injury designation. */
final class RosterInjuryNewsProvider {
    static final String FEED = "https://www.espn.com/espn/rss/nfl/news";
    private static final Pattern INJURY = Pattern.compile(
        "(?i)\\b(injur(?:y|ies|ed)|questionable|doubtful|ruled out|sidelined|concussion|practice|surgery)\\b");
    private static final Pattern ANALYSIS = Pattern.compile(
        "(?i)\\b(fantasy|start|sit|rankings?|outlook|breakout|backfield|snaps?|targets?|touches|role)\\b");
    private final FeedLoader loader;
    private final Clock clock;
    private String cached;
    private Instant expires = Instant.EPOCH;

    RosterInjuryNewsProvider() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        loader = () -> {
            var response = client.send(HttpRequest.newBuilder(URI.create(FEED))
                .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("NFL news feed unavailable");
            return response.body();
        };
        clock = Clock.systemUTC();
    }

    RosterInjuryNewsProvider(FeedLoader loader, Clock clock) {
        this.loader = Objects.requireNonNull(loader);
        this.clock = Objects.requireNonNull(clock);
    }

    @FunctionalInterface
    interface FeedLoader { String load() throws IOException, InterruptedException; }

    private synchronized String feed() throws IOException, InterruptedException {
        Instant now = clock.instant();
        if (cached == null || !now.isBefore(expires)) {
            String next = loader.load();
            if (next == null || next.length() > 1_000_000) throw new IOException("NFL news feed unavailable or oversized");
            cached = next;
            expires = now.plus(Duration.ofMinutes(5));
        }
        return cached;
    }

    Review newReview() { return new Review(); }

    /** One recommendation shares one feed outcome, including failure, without a cross-request failure cache. */
    final class Review {
        private boolean attempted;
        private String xml;
        private IOException unavailable;
        private InterruptedException interrupted;

        private synchronized String feedOnce() throws IOException, InterruptedException {
            if (!attempted) {
                attempted = true;
                try { xml = feed(); }
                catch (IOException e) { unavailable = e; }
                catch (InterruptedException e) { interrupted = e; }
            }
            if (interrupted != null) throw interrupted;
            if (unavailable != null) throw unavailable;
            return xml;
        }

        Map<String, String> load(List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players)
            throws IOException, InterruptedException {
            return parse(feedOnce(), players, clock.instant());
        }

        Map<String, String> loadAnalysis(List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players)
            throws IOException, InterruptedException {
            return parseAnalysis(feedOnce(), players, clock.instant());
        }
    }

    Map<String, String> load(List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players)
        throws IOException, InterruptedException {
        return newReview().load(players);
    }

    Map<String, String> loadAnalysis(List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players)
        throws IOException, InterruptedException {
        return newReview().loadAnalysis(players);
    }

    static Map<String, String> parseAnalysis(String xml,
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players, Instant now) throws IOException {
        return parse(xml, players, now, true);
    }

    static Map<String, String> parse(String xml,
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players, Instant now) throws IOException {
        return parse(xml, players, now, false);
    }

    private static Map<String, String> parse(String xml,
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players, Instant now, boolean analysis)
        throws IOException {
        if (xml == null || xml.length() > 1_000_000) throw new IOException("Invalid news feed size");
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            var items = document.getElementsByTagName("item");
            Map<String, String> result = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(items.getLength(), 100); i++) {
                Element item = (Element) items.item(i);
                String title = text(item, "title");
                String link = text(item, "link");
                String date = text(item, "pubDate");
                Instant published;
                try { published = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
                catch (RuntimeException e) { continue; }
                if (published.isAfter(now) || published.isBefore(now.minus(Duration.ofHours(72)))) continue;
                URI uri;
                try { uri = URI.create(link); } catch (RuntimeException e) { continue; }
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || !(uri.getHost().equals("espn.com") || uri.getHost().endsWith(".espn.com"))) continue;
                if (!(analysis ? ANALYSIS : INJURY).matcher(title).find()) continue;
                for (var player : players) {
                    String name = player.displayName();
                    if (name == null || name.isBlank() || !name.contains(" ")) continue;
                    if (players.stream().filter(p -> name.equalsIgnoreCase(p.displayName())).count() != 1) continue;
                    if (!Pattern.compile("(?i)(?<![\\p{L}])" + Pattern.quote(name) + "(?![\\p{L}])")
                        .matcher(title).find()) continue;
                    // Display only a short headline; article text is never imported.
                    String[] words = title.split("\\s+");
                    String shortTitle = String.join(" ", java.util.Arrays.copyOf(words, Math.min(words.length, 20)));
                    String author = text(item, "dc:creator");
                    if (author.isBlank()) author = text(item, "author");
                    author = author.replaceAll("\\s+", " ");
                    if (author.length() > 100) author = author.substring(0, 100);
                    result.putIfAbsent(player.sleeperPlayerId(),
                        (analysis ? "Public ESPN analysis headline: " : "Recent ESPN injury/practice headline: ")
                        + shortTitle + "; published=" + published + "; source=" + link
                        + (analysis ? "; author=" + (author.isBlank() ? "not supplied" : author)
                            + ". Source-linked context only; no expert start/sit pick has been extracted."
                            : ". Headline requires review; it does not establish an official Out designation."));
                }
            }
            return Map.copyOf(result);
        } catch (Exception e) {
            throw new IOException("Cannot validate injury news feed", e);
        }
    }

    private static String text(Element element, String tag) {
        var nodes = element.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
