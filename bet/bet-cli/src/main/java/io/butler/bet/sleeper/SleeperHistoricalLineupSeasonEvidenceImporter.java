package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Hydrates all provider-observed historical Sleeper team-week lineup prerequisites within the
 * supported NFL week universe while reusing the governed BF-550/BF-551 week importer.
 */
public final class SleeperHistoricalLineupSeasonEvidenceImporter {
    public static final int FIRST_WEEK = 1;
    public static final int LAST_WEEK = 18;
    private static final int MAX_HISTORY_HOPS = 30;

    private final Database database;
    private final MemoizingHistoricalSource source;
    private final LeagueRepository leagues;
    private final int lastWeek;

    public SleeperHistoricalLineupSeasonEvidenceImporter(Database database) {
        this(new SleeperApiHistoricalSource(), database, LAST_WEEK);
    }

    SleeperHistoricalLineupSeasonEvidenceImporter(
        SleeperHistoricalLineupEvidenceImporter.HistoricalSource source,
        Database database,
        int lastWeek) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.source = new MemoizingHistoricalSource(
            Objects.requireNonNull(source, "source must not be null"));
        this.leagues = new LeagueRepository(database);
        if (lastWeek < FIRST_WEEK || lastWeek > LAST_WEEK) {
            throw new IllegalArgumentException(
                "lastWeek must be between " + FIRST_WEEK + " and " + LAST_WEEK);
        }
        this.lastWeek = lastWeek;
    }

    public SeasonImportResult syncSeason(String butlerLeagueId, int targetSeason)
        throws IOException, InterruptedException, SQLException {
        String leagueId = requireText(butlerLeagueId, "butlerLeagueId");
        if (targetSeason < 1999 || targetSeason > 2100) {
            throw new IllegalArgumentException("targetSeason must be between 1999 and 2100");
        }

        var league = leagues.findById(leagueId)
            .orElseThrow(() -> new IllegalArgumentException("league not found: " + leagueId));
        String currentSleeperLeagueId = requireText(
            league.getExternalId(), "league external Sleeper id");
        ResolvedSeason resolved = resolveSeason(currentSleeperLeagueId, targetSeason);

        var weeklyImporter = new SleeperHistoricalLineupEvidenceImporter(source, database);
        List<Integer> weeksImported = new ArrayList<>();
        int teamWeekSnapshots = 0;
        int newPlayersCreated = 0;
        String sourceName = null;
        java.time.LocalDate latestAsOfDate = null;

        for (int week = FIRST_WEEK; week <= lastWeek; week++) {
            List<SleeperMatchupParser.SleeperMatchup> matchups =
                source.fetchMatchups(resolved.sleeperLeagueId(), week);
            if (matchups == null || matchups.isEmpty()) continue;

            var result = weeklyImporter.syncWeek(leagueId, targetSeason, week);
            if (!resolved.sleeperLeagueId().equals(result.sleeperLeagueId())
                || resolved.historyHops() != result.historyHops()) {
                throw new IllegalStateException(
                    "historical Sleeper season identity moved during season hydration");
            }
            weeksImported.add(week);
            teamWeekSnapshots += result.teamsImported();
            newPlayersCreated += result.newPlayersCreated();
            sourceName = result.source();
            latestAsOfDate = result.asOfDate();
        }

        if (weeksImported.isEmpty()) {
            throw new IllegalStateException(
                "No historical Sleeper matchup evidence found for season " + targetSeason
                    + " in weeks " + FIRST_WEEK + "-" + lastWeek);
        }

        return new SeasonImportResult(
            leagueId,
            targetSeason,
            resolved.sleeperLeagueId(),
            resolved.historyHops(),
            List.copyOf(weeksImported),
            teamWeekSnapshots,
            newPlayersCreated,
            requireText(sourceName, "source"),
            Objects.requireNonNull(latestAsOfDate, "latestAsOfDate must not be null"));
    }

    private ResolvedSeason resolveSeason(String currentSleeperLeagueId, int targetSeason)
        throws IOException, InterruptedException {
        Set<String> visited = new HashSet<>();
        String sleeperLeagueId = currentSleeperLeagueId;
        for (int hops = 0; hops <= MAX_HISTORY_HOPS; hops++) {
            if (!visited.add(sleeperLeagueId)) {
                throw new IllegalStateException(
                    "Sleeper previous-league history contains a cycle at: " + sleeperLeagueId);
            }
            var link = source.fetchLeagueLink(sleeperLeagueId);
            if (!sleeperLeagueId.equals(link.leagueId())) {
                throw new IllegalStateException(
                    "Sleeper league identity mismatch: requested=" + sleeperLeagueId
                        + " returned=" + link.leagueId());
            }
            if (link.season() == targetSeason) return new ResolvedSeason(sleeperLeagueId, hops);
            if (link.season() < targetSeason) {
                throw new IllegalStateException(
                    "requested season " + targetSeason
                        + " is newer than resolved Sleeper season " + link.season());
            }
            if (link.previousLeagueId() == null) {
                throw new IllegalStateException(
                    "Sleeper history ended at season " + link.season()
                        + " before requested season " + targetSeason);
            }
            sleeperLeagueId = link.previousLeagueId();
        }
        throw new IllegalStateException(
            "Sleeper history exceeded " + MAX_HISTORY_HOPS
                + " links before requested season " + targetSeason);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private record ResolvedSeason(String sleeperLeagueId, int historyHops) {}

    public record SeasonImportResult(
        String butlerLeagueId,
        int season,
        String sleeperLeagueId,
        int historyHops,
        List<Integer> weeksImported,
        int teamWeekSnapshots,
        int newPlayersCreated,
        String source,
        java.time.LocalDate asOfDate) {
        public SeasonImportResult {
            butlerLeagueId = requireText(butlerLeagueId, "butlerLeagueId");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            if (season < 1999 || season > 2100) {
                throw new IllegalArgumentException("season must be between 1999 and 2100");
            }
            if (historyHops < 0) throw new IllegalArgumentException("historyHops must not be negative");
            weeksImported = List.copyOf(Objects.requireNonNull(
                weeksImported, "weeksImported must not be null"));
            if (weeksImported.isEmpty()) throw new IllegalArgumentException("weeksImported must not be empty");
            if (teamWeekSnapshots <= 0) {
                throw new IllegalArgumentException("teamWeekSnapshots must be positive");
            }
            if (newPlayersCreated < 0) {
                throw new IllegalArgumentException("newPlayersCreated must not be negative");
            }
            source = requireText(source, "source");
            Objects.requireNonNull(asOfDate, "asOfDate must not be null");
        }
    }

    /** Memoizes every provider endpoint used by repeated week importer calls in one season pass. */
    private static final class MemoizingHistoricalSource
        implements SleeperHistoricalLineupEvidenceImporter.HistoricalSource {
        private final SleeperHistoricalLineupEvidenceImporter.HistoricalSource delegate;
        private final Map<String, SleeperHistoricalLineupEvidenceImporter.LeagueLink> links =
            new HashMap<>();
        private final Map<String, SleeperJsonParser.SleeperLeague> leagueDetails = new HashMap<>();
        private final Map<String, List<SleeperJsonParser.SleeperRoster>> rosters = new HashMap<>();
        private final Map<String, List<SleeperMatchupParser.SleeperMatchup>> matchups = new HashMap<>();
        private Map<String, SleeperJsonParser.SleeperPlayer> players;

        private MemoizingHistoricalSource(
            SleeperHistoricalLineupEvidenceImporter.HistoricalSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public SleeperHistoricalLineupEvidenceImporter.LeagueLink fetchLeagueLink(String sleeperLeagueId)
            throws IOException, InterruptedException {
            var cached = links.get(sleeperLeagueId);
            if (cached != null) return cached;
            var loaded = delegate.fetchLeagueLink(sleeperLeagueId);
            links.put(sleeperLeagueId, loaded);
            return loaded;
        }

        @Override
        public SleeperJsonParser.SleeperLeague fetchLeague(String sleeperLeagueId)
            throws IOException, InterruptedException {
            var cached = leagueDetails.get(sleeperLeagueId);
            if (cached != null) return cached;
            var loaded = delegate.fetchLeague(sleeperLeagueId);
            leagueDetails.put(sleeperLeagueId, loaded);
            return loaded;
        }

        @Override
        public List<SleeperJsonParser.SleeperRoster> fetchRosters(String sleeperLeagueId)
            throws IOException, InterruptedException {
            var cached = rosters.get(sleeperLeagueId);
            if (cached != null) return cached;
            var loaded = List.copyOf(delegate.fetchRosters(sleeperLeagueId));
            rosters.put(sleeperLeagueId, loaded);
            return loaded;
        }

        @Override
        public List<SleeperMatchupParser.SleeperMatchup> fetchMatchups(String sleeperLeagueId, int week)
            throws IOException, InterruptedException {
            String key = sleeperLeagueId + "#" + week;
            var cached = matchups.get(key);
            if (cached != null) return cached;
            var loaded = List.copyOf(delegate.fetchMatchups(sleeperLeagueId, week));
            matchups.put(key, loaded);
            return loaded;
        }

        @Override
        public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers()
            throws IOException, InterruptedException {
            if (players == null) players = Map.copyOf(delegate.fetchPlayers());
            return players;
        }
    }

    private static final class SleeperApiHistoricalSource
        implements SleeperHistoricalLineupEvidenceImporter.HistoricalSource {
        private final SleeperClient client = new SleeperClient();
        private final SleeperJsonParser parser = new SleeperJsonParser();
        private final SleeperMatchupParser matchupParser = new SleeperMatchupParser();
        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public SleeperHistoricalLineupEvidenceImporter.LeagueLink fetchLeagueLink(String sleeperLeagueId)
            throws IOException, InterruptedException {
            JsonNode root = objectMapper.readTree(client.getLeague(sleeperLeagueId));
            String returnedId = text(root, "league_id");
            int season = parseSeason(root.get("season"));
            JsonNode previous = root.get("previous_league_id");
            String previousLeagueId = previous == null || previous.isNull() ? null : previous.asText();
            return new SleeperHistoricalLineupEvidenceImporter.LeagueLink(
                returnedId, season, previousLeagueId);
        }

        @Override
        public SleeperJsonParser.SleeperLeague fetchLeague(String sleeperLeagueId)
            throws IOException, InterruptedException {
            return parser.parseLeague(client.getLeague(sleeperLeagueId));
        }

        @Override
        public List<SleeperJsonParser.SleeperRoster> fetchRosters(String sleeperLeagueId)
            throws IOException, InterruptedException {
            return parser.parseRosters(client.getLeagueRosters(sleeperLeagueId));
        }

        @Override
        public List<SleeperMatchupParser.SleeperMatchup> fetchMatchups(String sleeperLeagueId, int week)
            throws IOException, InterruptedException {
            return matchupParser.parse(client.getLeagueMatchups(sleeperLeagueId, week));
        }

        @Override
        public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers()
            throws IOException, InterruptedException {
            return parser.parsePlayers(client.getNflPlayers());
        }

        private static String text(JsonNode node, String field) throws IOException {
            JsonNode value = node.get(field);
            if (value == null || value.isNull() || value.asText().isBlank()) {
                throw new IOException("Sleeper league response missing " + field);
            }
            return value.asText().trim();
        }

        private static int parseSeason(JsonNode value) throws IOException {
            if (value == null || value.isNull()) {
                throw new IOException("Sleeper league response missing season");
            }
            String text = value.asText();
            try {
                int season = Integer.parseInt(text);
                if (season < 1999 || season > 2100) throw new NumberFormatException();
                return season;
            } catch (NumberFormatException e) {
                throw new IOException("Sleeper league response has invalid season: " + text, e);
            }
        }
    }
}
