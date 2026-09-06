package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.ProviderPlayerWeekPointsEvidenceRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.ProviderPlayerWeekPointsEvidence;
import io.butler.bet.domain.Team;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * BF-560 persistence lane for historical Sleeper league-scored matchup players_points.
 * Validation of the complete season occurs before any provider-points row is written.
 */
public final class SleeperSeasonProviderPointsEvidenceImporter {
    public static final String POLICY_ID =
        "sleeper-season-provider-points-evidence-v1-complete-before-atomic-persist";
    public static final String SOURCE = "sleeper";
    public static final String SOURCE_SURFACE = "matchup.players_points";
    public static final int FIRST_WEEK = 1;
    public static final int LAST_WEEK = 18;

    private final Database database;
    private final Source source;
    private final LocalDate asOfDate;
    private final ObjectMapper mapper = new ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    public SleeperSeasonProviderPointsEvidenceImporter(Database database) {
        this(database, new LiveSource(), LocalDate.now(ZoneOffset.UTC));
    }

    SleeperSeasonProviderPointsEvidenceImporter(Database database, Source source, LocalDate asOfDate) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.asOfDate = Objects.requireNonNull(asOfDate, "asOfDate must not be null");
    }

    public ImportResult syncSeason(String leagueId, int season)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }

        var league = new LeagueRepository(database).findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + normalizedLeagueId));
        String currentSleeperLeagueId = requireText(league.getExternalId(), "league external Sleeper id");
        String historicalSleeperLeagueId = source.resolveLineage(currentSleeperLeagueId)
            .linksNewestToOldest().stream()
            .filter(link -> link.season() == season)
            .map(SleeperLeagueLineageResolver.LeagueLink::leagueId)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Sleeper lineage from " + currentSleeperLeagueId + " does not contain season " + season));

        Map<String, Team> teamsByRosterId = teamsByRosterId(
            new TeamRepository(database).findByLeagueId(normalizedLeagueId));
        List<ProviderPlayerWeekPointsEvidence> evidence = new ArrayList<>();
        Set<String> defenseIdentities = new LinkedHashSet<>();
        int populatedWeeks = 0;

        for (int week = FIRST_WEEK; week <= LAST_WEEK; week++) {
            WeekRows rows = validateWeek(
                normalizedLeagueId,
                historicalSleeperLeagueId,
                season,
                week,
                source.matchups(historicalSleeperLeagueId, week),
                teamsByRosterId);
            if (!rows.populated()) continue;
            populatedWeeks++;
            evidence.addAll(rows.evidence());
            defenseIdentities.addAll(rows.defenseIdentities());
        }
        if (populatedWeeks == 0) {
            throw new IllegalStateException("No populated Sleeper matchup weeks were returned for weeks 1-18");
        }
        if (evidence.isEmpty()) {
            throw new IllegalStateException("Populated Sleeper season produced no roster-player points evidence");
        }

        evidence.sort(Comparator
            .comparingInt(ProviderPlayerWeekPointsEvidence::week)
            .thenComparing(ProviderPlayerWeekPointsEvidence::teamId)
            .thenComparing(ProviderPlayerWeekPointsEvidence::providerPlayerId));
        List<ProviderPlayerWeekPointsEvidence> validated = List.copyOf(evidence);

        var repository = new ProviderPlayerWeekPointsEvidenceRepository(database);
        repository.replaceSeasonSnapshot(normalizedLeagueId, season, SOURCE, asOfDate, validated);
        List<ProviderPlayerWeekPointsEvidence> readBack =
            repository.findSnapshot(normalizedLeagueId, season, SOURCE, asOfDate);
        ProviderPointsEvidenceReconciler.reconcile(validated, readBack);

        return new ImportResult(
            POLICY_ID,
            normalizedLeagueId,
            league.getName(),
            season,
            historicalSleeperLeagueId,
            populatedWeeks,
            validated.size(),
            readBack.size(),
            List.copyOf(defenseIdentities),
            SOURCE,
            SOURCE_SURFACE,
            asOfDate,
            ImportState.PERSISTED_RECONCILED);
    }

    private WeekRows validateWeek(
        String leagueId,
        String providerLeagueId,
        int season,
        int week,
        String json,
        Map<String, Team> teamsByRosterId) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "matchup payload"));
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("Sleeper matchup payload must be a JSON array for week " + week);
        }
        if (root.isEmpty()) return new WeekRows(false, List.of(), List.of());

        Set<String> observedRosterIds = new LinkedHashSet<>();
        Set<String> observedPlayers = new LinkedHashSet<>();
        Set<String> defenseIdentities = new LinkedHashSet<>();
        List<ProviderPlayerWeekPointsEvidence> evidence = new ArrayList<>();

        for (JsonNode matchup : root) {
            int rosterId = matchup.path("roster_id").asInt(0);
            if (rosterId <= 0) {
                throw new IllegalStateException("Missing or invalid Sleeper roster_id in week " + week);
            }
            String providerRosterId = Integer.toString(rosterId);
            if (!observedRosterIds.add(providerRosterId)) {
                throw new IllegalStateException("Duplicate Sleeper roster_id " + providerRosterId + " in week " + week);
            }
            Team team = teamsByRosterId.get(providerRosterId);
            if (team == null) {
                throw new IllegalStateException(
                    "Sleeper week " + week + " contains unknown roster_id " + providerRosterId);
            }

            List<String> players = identities(matchup.get("players"), "players", week);
            List<String> starters = identities(matchup.get("starters"), "starters", week);
            Set<String> playerSet = new LinkedHashSet<>();
            for (String playerId : players) {
                if (!playerSet.add(playerId)) {
                    throw new IllegalStateException(
                        "Duplicate provider player identity " + playerId + " within roster "
                            + providerRosterId + " in week " + week);
                }
                if (!observedPlayers.add(playerId)) {
                    throw new IllegalStateException(
                        "Provider player identity " + playerId + " appears on multiple rosters in week " + week);
                }
            }
            for (String starterId : starters) {
                if (!playerSet.contains(starterId)) {
                    throw new IllegalStateException(
                        "Starter " + starterId + " is not present in players for roster "
                            + providerRosterId + " in week " + week);
                }
            }

            JsonNode points = matchup.get("players_points");
            if (points == null || points.isNull() || !points.isObject()) {
                throw new IllegalStateException(
                    "Sleeper players_points must be an object for roster " + providerRosterId
                        + " in week " + week);
            }
            for (String playerId : players) {
                BigDecimal providerPoints = numericPoint(points, playerId);
                if (providerPoints == null) {
                    throw new IllegalStateException(
                        "Roster player " + playerId + " has no numeric players_points value in week " + week);
                }
                if (isDefenseIdentity(playerId)) defenseIdentities.add(playerId);
                evidence.add(ProviderPlayerWeekPointsEvidence.create(
                    leagueId,
                    team.getId(),
                    providerRosterId,
                    providerLeagueId,
                    season,
                    week,
                    playerId,
                    providerPoints,
                    SOURCE,
                    SOURCE_SURFACE,
                    asOfDate));
            }
            for (String starterId : starters) {
                if (numericPoint(points, starterId) == null) {
                    throw new IllegalStateException(
                        "Starter " + starterId + " has no numeric players_points value in week " + week);
                }
            }
        }

        if (!observedRosterIds.equals(teamsByRosterId.keySet())) {
            Set<String> missing = new LinkedHashSet<>(teamsByRosterId.keySet());
            missing.removeAll(observedRosterIds);
            Set<String> extra = new LinkedHashSet<>(observedRosterIds);
            extra.removeAll(teamsByRosterId.keySet());
            throw new IllegalStateException(
                "Historical Sleeper week " + week + " roster identities do not match Butler franchise identities: "
                    + "missing=" + missing + " extra=" + extra);
        }
        return new WeekRows(true, List.copyOf(evidence), List.copyOf(defenseIdentities));
    }

    private static Map<String, Team> teamsByRosterId(List<Team> teams) {
        if (teams == null || teams.isEmpty()) throw new IllegalStateException("league has no Butler teams");
        Map<String, Team> result = new LinkedHashMap<>();
        for (Team team : teams) {
            String rosterId = requireText(team.getExternalId(), "team external Sleeper roster id");
            if (result.putIfAbsent(rosterId, team) != null) {
                throw new IllegalStateException("duplicate Butler team Sleeper roster id: " + rosterId);
            }
        }
        return Map.copyOf(result);
    }

    private static List<String> identities(JsonNode node, String field, int week) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) {
            throw new IllegalStateException("Sleeper " + field + " must be an array in week " + week);
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            String id = value == null || value.isNull() ? null : value.asText(null);
            if (id == null || id.isBlank() || "0".equals(id.trim())) continue;
            result.add(id.trim());
        }
        return List.copyOf(result);
    }

    private static BigDecimal numericPoint(JsonNode points, String playerId) {
        if (points == null || !points.isObject()) return null;
        JsonNode value = points.get(playerId);
        return value != null && value.isNumber() ? value.decimalValue() : null;
    }

    private static boolean isDefenseIdentity(String playerId) {
        return playerId.matches("[A-Z]{2,3}");
    }

    interface Source {
        SleeperLeagueLineageResolver.Lineage resolveLineage(String currentSleeperLeagueId)
            throws IOException, InterruptedException;
        String matchups(String sleeperLeagueId, int week) throws IOException, InterruptedException;
    }

    private static final class LiveSource implements Source {
        private final SleeperLeagueLineageResolver resolver = new SleeperLeagueLineageResolver();
        private final SleeperClient client = new SleeperClient();

        @Override
        public SleeperLeagueLineageResolver.Lineage resolveLineage(String currentSleeperLeagueId)
            throws IOException, InterruptedException {
            return resolver.resolve(currentSleeperLeagueId);
        }

        @Override
        public String matchups(String sleeperLeagueId, int week)
            throws IOException, InterruptedException {
            return client.getLeagueMatchups(sleeperLeagueId, week);
        }
    }

    public enum ImportState {
        PERSISTED_RECONCILED
    }

    public record ImportResult(
        String policyId,
        String leagueId,
        String leagueName,
        int season,
        String providerLeagueId,
        int populatedWeeks,
        int rowsPersisted,
        int rowsReadBack,
        List<String> defenseIdentities,
        String source,
        String sourceSurface,
        LocalDate asOfDate,
        ImportState state) {
        public ImportResult {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            leagueId = requireText(leagueId, "leagueId");
            leagueName = requireText(leagueName, "leagueName");
            providerLeagueId = requireText(providerLeagueId, "providerLeagueId");
            if (populatedWeeks <= 0) throw new IllegalArgumentException("populatedWeeks must be positive");
            if (rowsPersisted <= 0 || rowsReadBack != rowsPersisted) {
                throw new IllegalArgumentException("persisted/read-back rows must reconcile and be positive");
            }
            defenseIdentities = List.copyOf(Objects.requireNonNull(
                defenseIdentities, "defenseIdentities must not be null"));
            source = requireText(source, "source");
            sourceSurface = requireText(sourceSurface, "sourceSurface");
            Objects.requireNonNull(asOfDate, "asOfDate must not be null");
            Objects.requireNonNull(state, "state must not be null");
        }
    }

    private record WeekRows(
        boolean populated,
        List<ProviderPlayerWeekPointsEvidence> evidence,
        List<String> defenseIdentities) {}

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
