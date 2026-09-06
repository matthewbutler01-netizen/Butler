package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only BF-559 audit of roster-wide Sleeper matchup players_points coverage. */
public final class SleeperSeasonProviderPointsCoverageAudit {
    public static final String POLICY_ID =
        "sleeper-season-provider-points-coverage-v1-roster-wide-read-only";
    public static final int FIRST_WEEK = 1;
    public static final int LAST_WEEK = 18;

    private final Database database;
    private final Source source;
    private final ObjectMapper mapper = new ObjectMapper();

    public SleeperSeasonProviderPointsCoverageAudit(Database database) {
        this(database, new LiveSource());
    }

    SleeperSeasonProviderPointsCoverageAudit(Database database, Source source) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
    }

    public AuditReport audit(String leagueId, int season)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }
        var league = new LeagueRepository(database).findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + normalizedLeagueId));
        String currentSleeperLeagueId = requireText(league.getExternalId(), "league external Sleeper id");
        var lineage = source.resolveLineage(currentSleeperLeagueId);
        String historicalSleeperLeagueId = lineage.linksNewestToOldest().stream()
            .filter(link -> link.season() == season)
            .map(SleeperLeagueLineageResolver.LeagueLink::leagueId)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Sleeper lineage from " + currentSleeperLeagueId + " does not contain season " + season));

        List<WeekAudit> weeks = new ArrayList<>();
        Set<String> defenseIdentities = new LinkedHashSet<>();
        int populatedWeeks = 0;
        int rosterPlayerObservations = 0;
        int rosterPlayerPointsPresent = 0;
        int starterObservations = 0;
        int starterPointsPresent = 0;
        int missingRosterPoints = 0;
        int missingStarterPoints = 0;
        int starterNotInPlayers = 0;
        int duplicateRosterIdentities = 0;

        for (int week = FIRST_WEEK; week <= LAST_WEEK; week++) {
            WeekAudit audit = auditWeek(week, source.matchups(historicalSleeperLeagueId, week));
            weeks.add(audit);
            if (!audit.populated()) continue;
            populatedWeeks++;
            rosterPlayerObservations += audit.rosterPlayerObservations();
            rosterPlayerPointsPresent += audit.rosterPlayerPointsPresent();
            starterObservations += audit.starterObservations();
            starterPointsPresent += audit.starterPointsPresent();
            missingRosterPoints += audit.missingRosterPointIdentities().size();
            missingStarterPoints += audit.missingStarterPointIdentities().size();
            starterNotInPlayers += audit.starterNotInPlayersIdentities().size();
            duplicateRosterIdentities += audit.duplicateRosterIdentities().size();
            defenseIdentities.addAll(audit.defenseIdentities());
        }

        List<String> blockers = new ArrayList<>();
        if (populatedWeeks == 0) blockers.add("No populated Sleeper matchup weeks were returned for weeks 1-18");
        if (missingRosterPoints > 0) blockers.add(missingRosterPoints + " roster-player observation(s) have no numeric players_points value");
        if (missingStarterPoints > 0) blockers.add(missingStarterPoints + " starter observation(s) have no numeric players_points value");
        if (starterNotInPlayers > 0) blockers.add(starterNotInPlayers + " starter observation(s) are not present in the matchup players array");
        if (duplicateRosterIdentities > 0) blockers.add(duplicateRosterIdentities + " roster identity observation(s) are duplicated across matchup rows in the same week");

        AuditState state = blockers.isEmpty()
            ? AuditState.PROOF_READY_ROSTER_WIDE_PROVIDER_POINTS
            : AuditState.PROOF_BLOCKED;
        return new AuditReport(
            POLICY_ID,
            normalizedLeagueId,
            league.getName(),
            season,
            historicalSleeperLeagueId,
            FIRST_WEEK,
            LAST_WEEK,
            List.copyOf(weeks),
            populatedWeeks,
            rosterPlayerObservations,
            rosterPlayerPointsPresent,
            starterObservations,
            starterPointsPresent,
            List.copyOf(defenseIdentities),
            state,
            List.copyOf(blockers));
    }

    private WeekAudit auditWeek(int week, String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "matchup payload"));
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("Sleeper matchup payload must be a JSON array for week " + week);
        }
        if (root.isEmpty()) {
            return new WeekAudit(week, false, 0, 0, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of());
        }

        Set<Integer> rosterIds = new LinkedHashSet<>();
        Set<String> allPlayers = new LinkedHashSet<>();
        Set<String> defenseIdentities = new LinkedHashSet<>();
        List<String> missingRosterPointIdentities = new ArrayList<>();
        List<String> missingStarterPointIdentities = new ArrayList<>();
        List<String> starterNotInPlayersIdentities = new ArrayList<>();
        List<String> duplicateRosterIdentities = new ArrayList<>();
        int rosterPlayerObservations = 0;
        int rosterPlayerPointsPresent = 0;
        int starterObservations = 0;
        int starterPointsPresent = 0;

        for (JsonNode matchup : root) {
            int rosterId = matchup.path("roster_id").asInt(0);
            if (rosterId <= 0 || !rosterIds.add(rosterId)) {
                throw new IllegalStateException("Missing, invalid, or duplicate Sleeper roster_id in week " + week);
            }
            List<String> players = identities(matchup.get("players"), "players", week);
            List<String> starters = identities(matchup.get("starters"), "starters", week);
            JsonNode points = matchup.get("players_points");
            if (points != null && !points.isNull() && !points.isObject()) {
                throw new IllegalStateException("Sleeper players_points must be an object in week " + week);
            }
            Set<String> playerSet = new LinkedHashSet<>();
            for (String playerId : players) {
                if (!playerSet.add(playerId)) {
                    duplicateRosterIdentities.add(playerId);
                    continue;
                }
                if (!allPlayers.add(playerId)) duplicateRosterIdentities.add(playerId);
                rosterPlayerObservations++;
                if (isDefenseIdentity(playerId)) defenseIdentities.add(playerId);
                if (numericPoint(points, playerId) != null) rosterPlayerPointsPresent++;
                else missingRosterPointIdentities.add(playerId);
            }
            for (String starterId : starters) {
                starterObservations++;
                if (isDefenseIdentity(starterId)) defenseIdentities.add(starterId);
                if (!playerSet.contains(starterId)) starterNotInPlayersIdentities.add(starterId);
                if (numericPoint(points, starterId) != null) starterPointsPresent++;
                else missingStarterPointIdentities.add(starterId);
            }
        }

        return new WeekAudit(
            week,
            true,
            rosterPlayerObservations,
            rosterPlayerPointsPresent,
            starterObservations,
            starterPointsPresent,
            List.copyOf(defenseIdentities),
            List.copyOf(missingRosterPointIdentities),
            List.copyOf(missingStarterPointIdentities),
            List.copyOf(starterNotInPlayersIdentities),
            List.copyOf(duplicateRosterIdentities));
    }

    private static List<String> identities(JsonNode node, String field, int week) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalStateException("Sleeper " + field + " must be an array in week " + week);
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            String id = value == null || value.isNull() ? null : value.asText(null);
            if (id == null || id.isBlank() || "0".equals(id.trim())) continue;
            result.add(id.trim());
        }
        return List.copyOf(result);
    }

    private static BigDecimal numericPoint(JsonNode points, String playerId) {
        if (points == null || points.isNull() || !points.isObject()) return null;
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
        public String matchups(String sleeperLeagueId, int week) throws IOException, InterruptedException {
            return client.getLeagueMatchups(sleeperLeagueId, week);
        }
    }

    public enum AuditState {
        PROOF_READY_ROSTER_WIDE_PROVIDER_POINTS,
        PROOF_BLOCKED
    }

    public record WeekAudit(
        int week,
        boolean populated,
        int rosterPlayerObservations,
        int rosterPlayerPointsPresent,
        int starterObservations,
        int starterPointsPresent,
        List<String> defenseIdentities,
        List<String> missingRosterPointIdentities,
        List<String> missingStarterPointIdentities,
        List<String> starterNotInPlayersIdentities,
        List<String> duplicateRosterIdentities) {
        public WeekAudit {
            if (week < FIRST_WEEK || week > LAST_WEEK) throw new IllegalArgumentException("week out of range");
            defenseIdentities = copy(defenseIdentities, "defenseIdentities");
            missingRosterPointIdentities = copy(missingRosterPointIdentities, "missingRosterPointIdentities");
            missingStarterPointIdentities = copy(missingStarterPointIdentities, "missingStarterPointIdentities");
            starterNotInPlayersIdentities = copy(starterNotInPlayersIdentities, "starterNotInPlayersIdentities");
            duplicateRosterIdentities = copy(duplicateRosterIdentities, "duplicateRosterIdentities");
        }

        public boolean complete() {
            return populated
                && rosterPlayerObservations == rosterPlayerPointsPresent
                && starterObservations == starterPointsPresent
                && missingRosterPointIdentities.isEmpty()
                && missingStarterPointIdentities.isEmpty()
                && starterNotInPlayersIdentities.isEmpty()
                && duplicateRosterIdentities.isEmpty();
        }
    }

    public record AuditReport(
        String policyId,
        String leagueId,
        String leagueName,
        int season,
        String sleeperLeagueId,
        int firstWeek,
        int lastWeek,
        List<WeekAudit> weeks,
        int populatedWeeks,
        int rosterPlayerObservations,
        int rosterPlayerPointsPresent,
        int starterObservations,
        int starterPointsPresent,
        List<String> defenseIdentities,
        AuditState state,
        List<String> blockers) {
        public AuditReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            leagueId = requireText(leagueId, "leagueId");
            leagueName = requireText(leagueName, "leagueName");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            weeks = List.copyOf(Objects.requireNonNull(weeks, "weeks must not be null"));
            defenseIdentities = copy(defenseIdentities, "defenseIdentities");
            Objects.requireNonNull(state, "state must not be null");
            blockers = copy(blockers, "blockers");
            if ((state == AuditState.PROOF_READY_ROSTER_WIDE_PROVIDER_POINTS) != blockers.isEmpty()) {
                throw new IllegalArgumentException("proof-ready state must have no blockers and blocked state must have blockers");
            }
        }
    }

    private static List<String> copy(List<String> values, String field) {
        return List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
