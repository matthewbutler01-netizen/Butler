package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamSeasonPerformanceRepository;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamSeasonPerformance;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Imports historical Sleeper team-season performance into an existing Butler league.
 *
 * <p>The importer follows Sleeper's dynasty previous-league chain and deliberately requires the
 * historical roster-id set to match the current Butler team's Sleeper roster-id set exactly before
 * writing anything. This prevents old performance from being attached to the wrong current team.
 */
public final class SleeperHistoricalTeamPerformanceImporter {
    private static final String SOURCE = "sleeper";
    private static final int MAX_HISTORY_HOPS = 30;

    private final HistoricalSource source;
    private final LeagueRepository leagues;
    private final TeamRepository teams;
    private final TeamSeasonPerformanceRepository performance;

    public SleeperHistoricalTeamPerformanceImporter(Database database) {
        this(new SleeperApiHistoricalSource(), database);
    }

    SleeperHistoricalTeamPerformanceImporter(HistoricalSource source, Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.leagues = new LeagueRepository(database);
        this.teams = new TeamRepository(database);
        this.performance = new TeamSeasonPerformanceRepository(database);
    }

    public ImportResult syncSeason(String butlerLeagueId, int targetSeason)
        throws IOException, InterruptedException, SQLException {
        butlerLeagueId = requireText(butlerLeagueId, "butlerLeagueId");
        if (targetSeason < 1999 || targetSeason > 2100) {
            throw new IllegalArgumentException("targetSeason must be between 1999 and 2100");
        }

        var league = leagues.findById(butlerLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("league not found: " + butlerLeagueId));
        String currentSleeperLeagueId = requireText(league.getExternalId(), "league external Sleeper id");

        ResolvedSeason resolved = resolveSeason(currentSleeperLeagueId, targetSeason);
        List<SleeperJsonParser.SleeperRoster> historicalRosters = source.fetchRosters(resolved.sleeperLeagueId());
        List<Team> currentTeams = teams.findByLeagueId(butlerLeagueId);
        if (currentTeams.isEmpty()) {
            throw new IllegalStateException("league has no Butler teams: " + butlerLeagueId);
        }

        Map<String, Team> teamsByRosterId = currentTeamsByRosterId(currentTeams);
        Map<String, SleeperJsonParser.SleeperRoster> rostersById = historicalRostersById(historicalRosters);
        requireExactRosterIdentity(teamsByRosterId.keySet(), rostersById.keySet(), targetSeason);

        LocalDate asOfDate = LocalDate.now(ZoneOffset.UTC);
        for (Map.Entry<String, Team> entry : teamsByRosterId.entrySet()) {
            var roster = rostersById.get(entry.getKey());
            performance.save(new TeamSeasonPerformance(
                butlerLeagueId,
                entry.getValue().getId(),
                targetSeason,
                roster.wins(),
                roster.losses(),
                roster.ties(),
                roster.pointsFor(),
                roster.pointsAgainst(),
                SOURCE,
                asOfDate));
        }

        return new ImportResult(
            butlerLeagueId,
            targetSeason,
            resolved.sleeperLeagueId(),
            teamsByRosterId.size(),
            resolved.historyHops(),
            SOURCE,
            asOfDate);
    }

    private ResolvedSeason resolveSeason(String currentSleeperLeagueId, int targetSeason)
        throws IOException, InterruptedException {
        Set<String> visited = new HashSet<>();
        String sleeperLeagueId = currentSleeperLeagueId;

        for (int hops = 0; hops <= MAX_HISTORY_HOPS; hops++) {
            if (!visited.add(sleeperLeagueId)) {
                throw new IllegalStateException("Sleeper previous-league history contains a cycle at: " + sleeperLeagueId);
            }

            LeagueLink link = source.fetchLeague(sleeperLeagueId);
            if (!sleeperLeagueId.equals(link.leagueId())) {
                throw new IllegalStateException(
                    "Sleeper league identity mismatch: requested=" + sleeperLeagueId + " returned=" + link.leagueId());
            }
            if (link.season() == targetSeason) {
                return new ResolvedSeason(sleeperLeagueId, hops);
            }
            if (link.season() < targetSeason) {
                throw new IllegalStateException(
                    "requested season " + targetSeason + " is newer than resolved Sleeper season " + link.season());
            }
            if (link.previousLeagueId() == null) {
                throw new IllegalStateException(
                    "Sleeper history ended at season " + link.season() + " before requested season " + targetSeason);
            }
            sleeperLeagueId = link.previousLeagueId();
        }

        throw new IllegalStateException(
            "Sleeper history exceeded " + MAX_HISTORY_HOPS + " links before requested season " + targetSeason);
    }

    private static Map<String, Team> currentTeamsByRosterId(List<Team> currentTeams) {
        Map<String, Team> result = new LinkedHashMap<>();
        for (Team team : currentTeams) {
            String rosterId = requireText(team.getExternalId(), "team external Sleeper roster id");
            Team previous = result.putIfAbsent(rosterId, team);
            if (previous != null) {
                throw new IllegalStateException("duplicate current Butler team Sleeper roster id: " + rosterId);
            }
        }
        return result;
    }

    private static Map<String, SleeperJsonParser.SleeperRoster> historicalRostersById(
        List<SleeperJsonParser.SleeperRoster> historicalRosters) {
        if (historicalRosters == null || historicalRosters.isEmpty()) {
            throw new IllegalStateException("historical Sleeper league has no rosters");
        }
        Map<String, SleeperJsonParser.SleeperRoster> result = new LinkedHashMap<>();
        for (var roster : historicalRosters) {
            String rosterId = Integer.toString(roster.rosterId());
            var previous = result.putIfAbsent(rosterId, roster);
            if (previous != null) {
                throw new IllegalStateException("duplicate historical Sleeper roster id: " + rosterId);
            }
        }
        return result;
    }

    private static void requireExactRosterIdentity(Set<String> current, Set<String> historical, int season) {
        if (current.equals(historical)) return;
        Set<String> missingHistorical = new HashSet<>(current);
        missingHistorical.removeAll(historical);
        Set<String> extraHistorical = new HashSet<>(historical);
        extraHistorical.removeAll(current);
        throw new IllegalStateException(
            "historical Sleeper roster identities do not match current Butler teams for season " + season
                + ": missing=" + missingHistorical + " extra=" + extraHistorical);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    interface HistoricalSource {
        LeagueLink fetchLeague(String sleeperLeagueId) throws IOException, InterruptedException;
        List<SleeperJsonParser.SleeperRoster> fetchRosters(String sleeperLeagueId)
            throws IOException, InterruptedException;
    }

    record LeagueLink(String leagueId, int season, String previousLeagueId) {
        LeagueLink {
            leagueId = requireText(leagueId, "leagueId");
            if (season < 1999 || season > 2100) {
                throw new IllegalArgumentException("Sleeper season must be between 1999 and 2100");
            }
            previousLeagueId = previousLeagueId == null || previousLeagueId.isBlank()
                ? null : previousLeagueId.trim();
        }
    }

    private record ResolvedSeason(String sleeperLeagueId, int historyHops) {}

    public record ImportResult(
        String butlerLeagueId,
        int season,
        String sleeperLeagueId,
        int teamsImported,
        int historyHops,
        String source,
        LocalDate asOfDate) {}

    private static final class SleeperApiHistoricalSource implements HistoricalSource {
        private final SleeperClient client = new SleeperClient();
        private final SleeperJsonParser parser = new SleeperJsonParser();
        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public LeagueLink fetchLeague(String sleeperLeagueId) throws IOException, InterruptedException {
            JsonNode root = objectMapper.readTree(client.getLeague(sleeperLeagueId));
            String returnedId = text(root, "league_id");
            int season = parseSeason(root.get("season"));
            JsonNode previous = root.get("previous_league_id");
            String previousLeagueId = previous == null || previous.isNull() ? null : previous.asText();
            return new LeagueLink(returnedId, season, previousLeagueId);
        }

        @Override
        public List<SleeperJsonParser.SleeperRoster> fetchRosters(String sleeperLeagueId)
            throws IOException, InterruptedException {
            return parser.parseRosters(client.getLeagueRosters(sleeperLeagueId));
        }

        private static String text(JsonNode node, String field) throws IOException {
            JsonNode value = node.get(field);
            if (value == null || value.isNull() || value.asText().isBlank()) {
                throw new IOException("Sleeper league response missing " + field);
            }
            return value.asText().trim();
        }

        private static int parseSeason(JsonNode value) throws IOException {
            if (value == null || value.isNull()) throw new IOException("Sleeper league response missing season");
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
