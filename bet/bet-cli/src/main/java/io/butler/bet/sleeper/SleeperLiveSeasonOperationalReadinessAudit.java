package io.butler.bet.sleeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.TeamRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Read-only BF-595 audit of minimum 2026 live-season Sleeper operational evidence. */
public final class SleeperLiveSeasonOperationalReadinessAudit {
    public static final String POLICY_ID =
        "sleeper-live-season-operational-readiness-v2-2026-in-season-populated-rosters-fail-closed";
    public static final int TARGET_SEASON = 2026;
    private static final int UNMAPPED_EXAMPLE_LIMIT = 20;

    private final Database database;
    private final Source source;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public SleeperLiveSeasonOperationalReadinessAudit(Database database) {
        this(database, new LiveSource(), Clock.systemUTC());
    }

    SleeperLiveSeasonOperationalReadinessAudit(Database database, Source source, Clock clock) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public AuditReport audit(String leagueId) throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        var league = new LeagueRepository(database).findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + normalizedLeagueId));
        String sleeperLeagueId = requireText(league.getExternalId(), "league external Sleeper id");

        LeagueObservation providerLeague = parseLeague(source.league(sleeperLeagueId));
        if (!sleeperLeagueId.equals(providerLeague.leagueId())) {
            throw new IllegalStateException("Linked Sleeper league id does not match provider league payload");
        }
        List<RosterObservation> providerRosters = parseRosters(source.rosters(sleeperLeagueId));
        Set<String> providerUserIds = parseUserIds(source.users(sleeperLeagueId));

        var persistedTeams = new TeamRepository(database).findByLeagueId(normalizedLeagueId);
        Set<String> persistedRosterIds = new TreeSet<>();
        persistedTeams.forEach(team -> {
            if (team.getExternalId() != null && !team.getExternalId().isBlank()) {
                persistedRosterIds.add(team.getExternalId().trim());
            }
        });
        Set<String> providerRosterIds = new TreeSet<>();
        providerRosters.forEach(roster -> providerRosterIds.add(Integer.toString(roster.rosterId())));
        Set<String> providerWithoutPersistedTeam = difference(providerRosterIds, persistedRosterIds);
        Set<String> persistedWithoutProviderRoster = difference(persistedRosterIds, providerRosterIds);

        Set<String> persistedPlayerExternalIds = new LinkedHashSet<>();
        new PlayerRepository(database).findAll().forEach(player -> {
            if (player.getExternalId() != null && !player.getExternalId().isBlank()) {
                persistedPlayerExternalIds.add(player.getExternalId().trim());
            }
        });
        Set<String> currentPlayerIds = new TreeSet<>();
        int rosterEntries = 0;
        for (RosterObservation roster : providerRosters) {
            rosterEntries += roster.playerIds().size();
            currentPlayerIds.addAll(roster.playerIds());
        }
        Set<String> unmappedPlayerIds = difference(currentPlayerIds, persistedPlayerExternalIds);
        List<String> unmappedExamples = unmappedPlayerIds.stream().limit(UNMAPPED_EXAMPLE_LIMIT).toList();

        int ownerlessRosters = 0;
        Set<String> unknownOwnerIds = new TreeSet<>();
        for (RosterObservation roster : providerRosters) {
            if (roster.ownerId() == null || roster.ownerId().isBlank()) {
                ownerlessRosters++;
            } else if (!providerUserIds.contains(roster.ownerId())) {
                unknownOwnerIds.add(roster.ownerId());
            }
        }

        List<String> rosterBlockers = new ArrayList<>();
        if (providerLeague.season() != TARGET_SEASON) {
            rosterBlockers.add("Provider season is " + providerLeague.season() + "; BF-595 live target is " + TARGET_SEASON);
        }
        if (providerLeague.status() == null || providerLeague.status().isBlank()) {
            rosterBlockers.add("Provider league status is missing");
        } else if (!"in_season".equals(providerLeague.status())) {
            rosterBlockers.add("Provider league status is " + providerLeague.status()
                + "; live operational readiness requires in_season");
        }
        if (providerLeague.rosterPositions().isEmpty()) {
            rosterBlockers.add("Provider roster_positions are missing");
        }
        if (providerLeague.scoringSettingCount() == 0) {
            rosterBlockers.add("Provider scoring_settings are missing");
        }
        if (providerRosters.isEmpty()) {
            rosterBlockers.add("Provider returned no current rosters");
        }
        if (providerLeague.totalRosters() > 0 && providerLeague.totalRosters() != providerRosters.size()) {
            rosterBlockers.add("Provider declared roster count " + providerLeague.totalRosters()
                + " does not match returned roster count " + providerRosters.size());
        }
        if (persistedTeams.size() != providerRosters.size()) {
            rosterBlockers.add("Persisted Butler team count " + persistedTeams.size()
                + " does not match provider roster count " + providerRosters.size());
        }
        if (!providerWithoutPersistedTeam.isEmpty()) {
            rosterBlockers.add("Provider roster ids missing persisted Butler teams: " + providerWithoutPersistedTeam);
        }
        if (!persistedWithoutProviderRoster.isEmpty()) {
            rosterBlockers.add("Persisted Butler team roster ids absent from provider: " + persistedWithoutProviderRoster);
        }
        long rostersWithoutPlayers = providerRosters.stream()
            .filter(roster -> roster.playerIds().isEmpty())
            .count();
        if (rostersWithoutPlayers > 0) {
            rosterBlockers.add(rostersWithoutPlayers + " provider roster(s) contain no current player identities");
        }
        if (!unmappedPlayerIds.isEmpty()) {
            rosterBlockers.add(unmappedPlayerIds.size() + " current roster player identity/identities have no exact Butler mapping");
        }

        List<String> ownerBlockers = new ArrayList<>();
        if (ownerlessRosters > 0) ownerBlockers.add(ownerlessRosters + " provider roster(s) have no owner_id");
        if (!unknownOwnerIds.isEmpty()) ownerBlockers.add("Roster owner ids absent from provider user list: " + unknownOwnerIds);

        List<String> lineupBlockers = new ArrayList<>(rosterBlockers);
        lineupBlockers.addAll(ownerBlockers);
        long rostersWithoutStarterSurface = providerRosters.stream()
            .filter(roster -> !roster.startersFieldPresent())
            .count();
        if (rostersWithoutStarterSurface > 0) {
            lineupBlockers.add(rostersWithoutStarterSurface + " provider roster(s) have no starters field");
        }
        long rostersWithoutStarters = providerRosters.stream()
            .filter(roster -> roster.starterIds().isEmpty())
            .count();
        if (rostersWithoutStarters > 0) {
            lineupBlockers.add(rostersWithoutStarters + " provider roster(s) contain no starter identities");
        }

        List<String> tradeBlockers = new ArrayList<>(rosterBlockers);
        tradeBlockers.addAll(ownerBlockers);

        CapabilityReadiness currentRosterReadiness = CapabilityReadiness.of(rosterBlockers);
        CapabilityReadiness lineupReadiness = CapabilityReadiness.of(lineupBlockers);
        CapabilityReadiness tradeReadiness = CapabilityReadiness.of(tradeBlockers);
        CapabilityReadiness waiverReadiness = new CapabilityReadiness(
            CapabilityState.NOT_YET_AUDITED,
            List.of("BF-595 does not yet prove a fresh full-player/free-agent universe beyond current roster identities"));

        List<RosterOwnerObservation> rosterOwners = providerRosters.stream()
            .map(roster -> new RosterOwnerObservation(
                roster.rosterId(),
                roster.ownerId(),
                roster.ownerId() != null && providerUserIds.contains(roster.ownerId()),
                roster.playerIds().size(),
                roster.starterIds().size()))
            .sorted(Comparator.comparingInt(RosterOwnerObservation::rosterId))
            .toList();

        return new AuditReport(
            POLICY_ID,
            normalizedLeagueId,
            league.getName(),
            sleeperLeagueId,
            TARGET_SEASON,
            providerLeague.season(),
            providerLeague.status(),
            providerLeague.leg(),
            providerLeague.totalRosters(),
            providerLeague.rosterPositions().size(),
            providerLeague.scoringSettingCount(),
            persistedTeams.size(),
            providerRosters.size(),
            rosterEntries,
            currentPlayerIds.size(),
            currentPlayerIds.size() - unmappedPlayerIds.size(),
            unmappedPlayerIds.size(),
            unmappedExamples,
            List.copyOf(providerWithoutPersistedTeam),
            List.copyOf(persistedWithoutProviderRoster),
            ownerlessRosters,
            List.copyOf(unknownOwnerIds),
            rosterOwners,
            currentRosterReadiness,
            lineupReadiness,
            waiverReadiness,
            tradeReadiness,
            Instant.now(clock));
    }

    private LeagueObservation parseLeague(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "league payload"));
        if (root == null || !root.isObject()) throw new IllegalStateException("Sleeper league payload must be an object");
        String id = requireText(text(root.get("league_id")), "provider league_id");
        int season = parseSeason(root.get("season"));
        String status = text(root.get("status"));
        JsonNode settings = root.path("settings");
        Integer leg = settings.has("leg") && settings.get("leg").canConvertToInt()
            ? settings.get("leg").intValue()
            : null;
        int totalRosters = root.path("total_rosters").asInt(0);
        List<String> rosterPositions = stringArray(root.get("roster_positions"), "roster_positions");
        JsonNode scoring = root.get("scoring_settings");
        if (scoring != null && !scoring.isNull() && !scoring.isObject()) {
            throw new IllegalStateException("Sleeper scoring_settings must be an object");
        }
        int scoringCount = scoring == null || scoring.isNull() ? 0 : scoring.size();
        return new LeagueObservation(id, season, status, leg, totalRosters, rosterPositions, scoringCount);
    }

    private List<RosterObservation> parseRosters(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "rosters payload"));
        if (root == null || !root.isArray()) throw new IllegalStateException("Sleeper rosters payload must be an array");
        List<RosterObservation> result = new ArrayList<>();
        Set<Integer> rosterIds = new LinkedHashSet<>();
        for (JsonNode roster : root) {
            int rosterId = roster.path("roster_id").asInt(0);
            if (rosterId <= 0 || !rosterIds.add(rosterId)) {
                throw new IllegalStateException("Missing, invalid, or duplicate Sleeper roster_id in current rosters payload");
            }
            boolean startersPresent = roster.has("starters") && roster.get("starters").isArray();
            result.add(new RosterObservation(
                rosterId,
                text(roster.get("owner_id")),
                stringArray(roster.get("players"), "players"),
                stringArray(roster.get("starters"), "starters"),
                startersPresent));
        }
        return result.stream().sorted(Comparator.comparingInt(RosterObservation::rosterId)).toList();
    }

    private Set<String> parseUserIds(String json) throws IOException {
        JsonNode root = mapper.readTree(requireText(json, "users payload"));
        if (root == null || !root.isArray()) throw new IllegalStateException("Sleeper users payload must be an array");
        Set<String> result = new TreeSet<>();
        for (JsonNode user : root) {
            String id = text(user.get("user_id"));
            if (id != null && !id.isBlank()) result.add(id.trim());
        }
        return result;
    }

    private static int parseSeason(JsonNode value) {
        if (value == null || value.isNull()) return 0;
        if (value.canConvertToInt()) return value.intValue();
        try {
            return Integer.parseInt(value.asText("0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> stringArray(JsonNode node, String field) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalStateException("Sleeper " + field + " must be an array");
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            String text = text(value);
            if (text != null && !text.isBlank() && !"0".equals(text.trim())) result.add(text.trim());
        }
        return List.copyOf(result);
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText(null);
    }

    interface Source {
        String league(String sleeperLeagueId) throws IOException, InterruptedException;
        String rosters(String sleeperLeagueId) throws IOException, InterruptedException;
        String users(String sleeperLeagueId) throws IOException, InterruptedException;
    }

    private static final class LiveSource implements Source {
        private final SleeperClient client = new SleeperClient();
        @Override public String league(String sleeperLeagueId) throws IOException, InterruptedException { return client.getLeague(sleeperLeagueId); }
        @Override public String rosters(String sleeperLeagueId) throws IOException, InterruptedException { return client.getLeagueRosters(sleeperLeagueId); }
        @Override public String users(String sleeperLeagueId) throws IOException, InterruptedException { return client.getLeagueUsers(sleeperLeagueId); }
    }

    public enum CapabilityState { READY, BLOCKED, NOT_YET_AUDITED }

    public record CapabilityReadiness(CapabilityState state, List<String> blockers) {
        public CapabilityReadiness {
            Objects.requireNonNull(state, "state must not be null");
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers must not be null"));
            if (state == CapabilityState.READY && !blockers.isEmpty()) {
                throw new IllegalArgumentException("READY capability must have no blockers");
            }
            if (state != CapabilityState.READY && blockers.isEmpty()) {
                throw new IllegalArgumentException("non-READY capability must retain explicit blockers");
            }
        }
        static CapabilityReadiness of(List<String> blockers) {
            List<String> copy = List.copyOf(blockers);
            return copy.isEmpty()
                ? new CapabilityReadiness(CapabilityState.READY, List.of())
                : new CapabilityReadiness(CapabilityState.BLOCKED, copy);
        }
    }

    public record RosterOwnerObservation(
        int rosterId,
        String ownerId,
        boolean ownerPresentInUserList,
        int playerCount,
        int starterCount) {}

    public record AuditReport(
        String policyId,
        String leagueId,
        String leagueName,
        String sleeperLeagueId,
        int targetSeason,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        int providerDeclaredRosterCount,
        int rosterPositionCount,
        int scoringSettingCount,
        int persistedTeamCount,
        int providerRosterCount,
        int providerRosterEntries,
        int distinctCurrentPlayerIds,
        int exactMappedCurrentPlayerIds,
        int unmappedCurrentPlayerIds,
        List<String> unmappedPlayerExamples,
        List<String> providerRosterIdsMissingPersistedTeam,
        List<String> persistedTeamRosterIdsMissingProvider,
        int ownerlessRosters,
        List<String> unknownOwnerIds,
        List<RosterOwnerObservation> rosterOwners,
        CapabilityReadiness currentRosterContext,
        CapabilityReadiness lineupContextPrerequisites,
        CapabilityReadiness waiverFreeAgentInventoryPrerequisites,
        CapabilityReadiness tradeContextPrerequisites,
        Instant observedAtUtc) {
        public AuditReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            leagueId = requireText(leagueId, "leagueId");
            leagueName = requireText(leagueName, "leagueName");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            unmappedPlayerExamples = List.copyOf(Objects.requireNonNull(unmappedPlayerExamples, "unmappedPlayerExamples must not be null"));
            providerRosterIdsMissingPersistedTeam = List.copyOf(Objects.requireNonNull(
                providerRosterIdsMissingPersistedTeam, "providerRosterIdsMissingPersistedTeam must not be null"));
            persistedTeamRosterIdsMissingProvider = List.copyOf(Objects.requireNonNull(
                persistedTeamRosterIdsMissingProvider, "persistedTeamRosterIdsMissingProvider must not be null"));
            unknownOwnerIds = List.copyOf(Objects.requireNonNull(unknownOwnerIds, "unknownOwnerIds must not be null"));
            rosterOwners = List.copyOf(Objects.requireNonNull(rosterOwners, "rosterOwners must not be null"));
            Objects.requireNonNull(currentRosterContext, "currentRosterContext must not be null");
            Objects.requireNonNull(lineupContextPrerequisites, "lineupContextPrerequisites must not be null");
            Objects.requireNonNull(waiverFreeAgentInventoryPrerequisites, "waiverFreeAgentInventoryPrerequisites must not be null");
            Objects.requireNonNull(tradeContextPrerequisites, "tradeContextPrerequisites must not be null");
            Objects.requireNonNull(observedAtUtc, "observedAtUtc must not be null");
        }
    }

    private record LeagueObservation(
        String leagueId,
        int season,
        String status,
        Integer leg,
        int totalRosters,
        List<String> rosterPositions,
        int scoringSettingCount) {}

    private record RosterObservation(
        int rosterId,
        String ownerId,
        List<String> playerIds,
        List<String> starterIds,
        boolean startersFieldPresent) {}

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
