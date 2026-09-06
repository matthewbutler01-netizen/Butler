package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.LeagueConfigurationObservation;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamWeekRosterEvidence;

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

/** Hydrates the provider-observed historical configuration and team-week roster evidence required by lineup analysis. */
public final class SleeperHistoricalLineupEvidenceImporter {
    private static final String SOURCE = "sleeper";
    private static final int MAX_HISTORY_HOPS = 30;

    private final HistoricalSource source;
    private final LeagueRepository leagues;
    private final TeamRepository teams;
    private final LeagueConfigurationObservationRepository configurations;
    private final TeamWeekRosterEvidenceRepository rosterEvidence;

    public SleeperHistoricalLineupEvidenceImporter(Database database) {
        this(new SleeperApiHistoricalSource(), database);
    }

    SleeperHistoricalLineupEvidenceImporter(HistoricalSource source, Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.leagues = new LeagueRepository(database);
        this.teams = new TeamRepository(database);
        this.configurations = new LeagueConfigurationObservationRepository(database);
        this.rosterEvidence = new TeamWeekRosterEvidenceRepository(database);
    }

    public ImportResult syncWeek(String butlerLeagueId, int targetSeason, int week)
        throws IOException, InterruptedException, SQLException {
        String leagueId = requireText(butlerLeagueId, "butlerLeagueId");
        if (targetSeason < 1999 || targetSeason > 2100) {
            throw new IllegalArgumentException("targetSeason must be between 1999 and 2100");
        }
        if (week <= 0) throw new IllegalArgumentException("week must be positive");

        var league = leagues.findById(leagueId)
            .orElseThrow(() -> new IllegalArgumentException("league not found: " + leagueId));
        String currentSleeperLeagueId = requireText(league.getExternalId(), "league external Sleeper id");
        ResolvedSeason resolved = resolveSeason(currentSleeperLeagueId, targetSeason);

        var historicalLeague = source.fetchLeague(resolved.sleeperLeagueId());
        if (!resolved.sleeperLeagueId().equals(historicalLeague.id())) {
            throw new IllegalStateException(
                "Sleeper league identity mismatch: requested=" + resolved.sleeperLeagueId()
                    + " returned=" + historicalLeague.id());
        }
        if (historicalLeague.season() != targetSeason) {
            throw new IllegalStateException(
                "resolved Sleeper league season mismatch: requested=" + targetSeason
                    + " returned=" + historicalLeague.season());
        }
        if (historicalLeague.rosterPositions().isEmpty()) {
            throw new IllegalStateException("historical Sleeper league has no roster_positions");
        }
        if (historicalLeague.scoringSettings().isEmpty()) {
            throw new IllegalStateException("historical Sleeper league has no scoring_settings");
        }

        Map<String, Team> currentTeams = currentTeamsByRosterId(teams.findByLeagueId(leagueId));
        Map<String, SleeperJsonParser.SleeperRoster> historicalRosters =
            historicalRostersById(source.fetchRosters(resolved.sleeperLeagueId()));
        requireExactRosterIdentity(currentTeams.keySet(), historicalRosters.keySet(), targetSeason, "rosters");

        Map<String, SleeperMatchupParser.SleeperMatchup> matchups =
            matchupsByRosterId(source.fetchMatchups(resolved.sleeperLeagueId(), week));
        requireExactRosterIdentity(historicalRosters.keySet(), matchups.keySet(), targetSeason,
            "week " + week + " matchups");

        LocalDate asOfDate = LocalDate.now(ZoneOffset.UTC);
        configurations.replace(new LeagueConfigurationObservation(
            leagueId,
            SOURCE,
            asOfDate,
            targetSeason,
            historicalLeague.rosterPositions(),
            historicalLeague.scoringSettings()));

        int teamsImported = 0;
        for (Map.Entry<String, Team> entry : currentTeams.entrySet()) {
            var matchup = matchups.get(entry.getKey());
            rosterEvidence.save(TeamWeekRosterEvidence.create(
                leagueId,
                entry.getValue().getId(),
                targetSeason,
                week,
                matchup.playerIds(),
                matchup.starterIds(),
                SOURCE,
                asOfDate));
            teamsImported++;
        }

        return new ImportResult(
            leagueId,
            targetSeason,
            week,
            resolved.sleeperLeagueId(),
            resolved.historyHops(),
            teamsImported,
            SOURCE,
            asOfDate);
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
            LeagueLink link = source.fetchLeagueLink(sleeperLeagueId);
            if (!sleeperLeagueId.equals(link.leagueId())) {
                throw new IllegalStateException(
                    "Sleeper league identity mismatch: requested=" + sleeperLeagueId
                        + " returned=" + link.leagueId());
            }
            if (link.season() == targetSeason) return new ResolvedSeason(sleeperLeagueId, hops);
            if (link.season() < targetSeason) {
                throw new IllegalStateException(
                    "requested season " + targetSeason + " is newer than resolved Sleeper season " + link.season());
            }
            if (link.previousLeagueId() == null) {
                throw new IllegalStateException(
                    "Sleeper history ended at season " + link.season()
                        + " before requested season " + targetSeason);
            }
            sleeperLeagueId = link.previousLeagueId();
        }
        throw new IllegalStateException(
            "Sleeper history exceeded " + MAX_HISTORY_HOPS + " links before requested season " + targetSeason);
    }

    private static Map<String, Team> currentTeamsByRosterId(List<Team> currentTeams) {
        if (currentTeams == null || currentTeams.isEmpty()) {
            throw new IllegalStateException("league has no Butler teams");
        }
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
        List<SleeperJsonParser.SleeperRoster> rosters) {
        if (rosters == null || rosters.isEmpty()) {
            throw new IllegalStateException("historical Sleeper league has no rosters");
        }
        Map<String, SleeperJsonParser.SleeperRoster> result = new LinkedHashMap<>();
        for (var roster : rosters) {
            String rosterId = Integer.toString(roster.rosterId());
            if (result.putIfAbsent(rosterId, roster) != null) {
                throw new IllegalStateException("duplicate historical Sleeper roster id: " + rosterId);
            }
        }
        return result;
    }

    private static Map<String, SleeperMatchupParser.SleeperMatchup> matchupsByRosterId(
        List<SleeperMatchupParser.SleeperMatchup> matchups) {
        if (matchups == null || matchups.isEmpty()) {
            throw new IllegalStateException("historical Sleeper week has no matchup evidence");
        }
        Map<String, SleeperMatchupParser.SleeperMatchup> result = new LinkedHashMap<>();
        for (var matchup : matchups) {
            String rosterId = Integer.toString(matchup.rosterId());
            if (result.putIfAbsent(rosterId, matchup) != null) {
                throw new IllegalStateException("duplicate historical Sleeper matchup roster id: " + rosterId);
            }
        }
        return result;
    }

    private static void requireExactRosterIdentity(Set<String> expected, Set<String> actual,
                                                   int season, String evidenceName) {
        if (expected.equals(actual)) return;
        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        Set<String> extra = new HashSet<>(actual);
        extra.removeAll(expected);
        throw new IllegalStateException(
            "historical Sleeper " + evidenceName + " identities do not match Butler franchise identities for season "
                + season + ": missing=" + missing + " extra=" + extra);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    interface HistoricalSource {
        LeagueLink fetchLeagueLink(String sleeperLeagueId) throws IOException, InterruptedException;
        SleeperJsonParser.SleeperLeague fetchLeague(String sleeperLeagueId)
            throws IOException, InterruptedException;
        List<SleeperJsonParser.SleeperRoster> fetchRosters(String sleeperLeagueId)
            throws IOException, InterruptedException;
        List<SleeperMatchupParser.SleeperMatchup> fetchMatchups(String sleeperLeagueId, int week)
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
        int week,
        String sleeperLeagueId,
        int historyHops,
        int teamsImported,
        String source,
        LocalDate asOfDate) {}

    private static final class SleeperApiHistoricalSource implements HistoricalSource {
        private final SleeperClient client = new SleeperClient();
        private final SleeperJsonParser parser = new SleeperJsonParser();
        private final SleeperMatchupParser matchupParser = new SleeperMatchupParser();
        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public LeagueLink fetchLeagueLink(String sleeperLeagueId) throws IOException, InterruptedException {
            JsonNode root = objectMapper.readTree(client.getLeague(sleeperLeagueId));
            String returnedId = text(root, "league_id");
            int season = parseSeason(root.get("season"));
            JsonNode previous = root.get("previous_league_id");
            String previousLeagueId = previous == null || previous.isNull() ? null : previous.asText();
            return new LeagueLink(returnedId, season, previousLeagueId);
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
