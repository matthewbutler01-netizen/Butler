package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves Sleeper league continuity from provider `previous_league_id` links. */
public final class SleeperLeagueLineageResolver {
    public static final int MAX_HISTORY_HOPS = 30;

    private final LinkSource source;
    private final Map<String, LeagueLink> linkCache = new HashMap<>();
    private final Map<String, Lineage> lineageCache = new HashMap<>();

    public SleeperLeagueLineageResolver() {
        this(new SleeperApiLinkSource());
    }

    SleeperLeagueLineageResolver(LinkSource source) {
        this.source = Objects.requireNonNull(source, "source must not be null");
    }

    public Lineage resolve(String sleeperLeagueId) throws IOException, InterruptedException {
        String startId = requireText(sleeperLeagueId, "sleeperLeagueId");
        Lineage cached = lineageCache.get(startId);
        if (cached != null) return cached;

        Set<String> visited = new HashSet<>();
        List<LeagueLink> links = new ArrayList<>();
        String currentId = startId;
        Integer previousSeason = null;
        for (int hops = 0; hops <= MAX_HISTORY_HOPS; hops++) {
            if (!visited.add(currentId)) {
                throw new IllegalStateException("Sleeper previous-league history contains a cycle at: " + currentId);
            }
            LeagueLink link = fetchLink(currentId);
            if (!currentId.equals(link.leagueId())) {
                throw new IllegalStateException(
                    "Sleeper league identity mismatch: requested=" + currentId + " returned=" + link.leagueId());
            }
            if (previousSeason != null && link.season() >= previousSeason) {
                throw new IllegalStateException(
                    "Sleeper previous-league history must move backward in season: "
                        + links.get(links.size() - 1).leagueId() + "=" + previousSeason
                        + " previous=" + link.leagueId() + "=" + link.season());
            }
            links.add(link);
            if (link.previousLeagueId() == null) {
                Lineage lineage = new Lineage(startId, link.leagueId(), links.get(0).season(), List.copyOf(links));
                lineageCache.put(startId, lineage);
                return lineage;
            }
            previousSeason = link.season();
            currentId = link.previousLeagueId();
        }
        throw new IllegalStateException(
            "Sleeper history exceeded " + MAX_HISTORY_HOPS + " links from league " + startId);
    }

    private LeagueLink fetchLink(String sleeperLeagueId) throws IOException, InterruptedException {
        LeagueLink cached = linkCache.get(sleeperLeagueId);
        if (cached != null) return cached;
        LeagueLink fetched = Objects.requireNonNull(source.fetch(sleeperLeagueId), "Sleeper league link must not be null");
        linkCache.put(sleeperLeagueId, fetched);
        return fetched;
    }

    interface LinkSource {
        LeagueLink fetch(String sleeperLeagueId) throws IOException, InterruptedException;
    }

    public record LeagueLink(String leagueId, int season, String previousLeagueId) {
        public LeagueLink {
            leagueId = requireText(leagueId, "leagueId");
            if (season < 1999 || season > 2100) {
                throw new IllegalArgumentException("season must be between 1999 and 2100");
            }
            previousLeagueId = normalizeOptional(previousLeagueId);
        }
    }

    public record Lineage(
        String startingSleeperLeagueId,
        String rootSleeperLeagueId,
        int startingSeason,
        List<LeagueLink> linksNewestToOldest) {

        public Lineage {
            startingSleeperLeagueId = requireText(startingSleeperLeagueId, "startingSleeperLeagueId");
            rootSleeperLeagueId = requireText(rootSleeperLeagueId, "rootSleeperLeagueId");
            if (startingSeason < 1999 || startingSeason > 2100) {
                throw new IllegalArgumentException("startingSeason must be between 1999 and 2100");
            }
            linksNewestToOldest = List.copyOf(Objects.requireNonNull(linksNewestToOldest,
                "linksNewestToOldest must not be null"));
            if (linksNewestToOldest.isEmpty()) throw new IllegalArgumentException("lineage must contain at least one link");
            if (!startingSleeperLeagueId.equals(linksNewestToOldest.get(0).leagueId())) {
                throw new IllegalArgumentException("lineage first link must equal starting Sleeper league id");
            }
            if (startingSeason != linksNewestToOldest.get(0).season()) {
                throw new IllegalArgumentException("lineage startingSeason must equal first link season");
            }
            if (!rootSleeperLeagueId.equals(linksNewestToOldest.get(linksNewestToOldest.size() - 1).leagueId())) {
                throw new IllegalArgumentException("lineage root must equal final link");
            }
        }

        public boolean containsSleeperLeagueId(String sleeperLeagueId) {
            String id = requireText(sleeperLeagueId, "sleeperLeagueId");
            return linksNewestToOldest.stream().anyMatch(link -> link.leagueId().equals(id));
        }

        public Set<String> sleeperLeagueIds() {
            Set<String> ids = new java.util.LinkedHashSet<>();
            linksNewestToOldest.forEach(link -> ids.add(link.leagueId()));
            return Set.copyOf(ids);
        }
    }

    private static final class SleeperApiLinkSource implements LinkSource {
        private final SleeperClient client = new SleeperClient();
        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public LeagueLink fetch(String sleeperLeagueId) throws IOException, InterruptedException {
            JsonNode root = mapper.readTree(client.getLeague(requireText(sleeperLeagueId, "sleeperLeagueId")));
            String returnedId = text(root, "league_id");
            int season = parseSeason(root.get("season"));
            JsonNode previous = root.get("previous_league_id");
            String previousLeagueId = previous == null || previous.isNull() ? null : previous.asText();
            return new LeagueLink(returnedId, season, previousLeagueId);
        }

        private static String text(JsonNode root, String field) {
            JsonNode value = root.get(field);
            String text = value == null || value.isNull() ? null : value.asText(null);
            return requireText(text, field);
        }

        private static int parseSeason(JsonNode value) {
            String text = value == null || value.isNull() ? null : value.asText(null);
            if (text == null || text.isBlank()) throw new IllegalStateException("Sleeper league season is missing");
            try {
                int season = Integer.parseInt(text.trim());
                if (season < 1999 || season > 2100) throw new NumberFormatException();
                return season;
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Invalid Sleeper league season: " + text);
            }
        }
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
