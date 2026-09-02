package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.DraftPick;
import io.butler.bet.domain.Team;
import io.butler.bet.intelligence.DynastyProcessDraftPickCatalog;

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
import java.util.UUID;

/**
 * Synchronizes future rookie-pick assets from Sleeper ownership records, but only for
 * year/round coordinates that DynastyProcess currently exposes as generic values.
 * This deliberately avoids inventing unsupported seasons or early/mid/late pick slots.
 */
public final class SleeperDraftPickImporter {
    private final SleeperGateway gateway;
    private final LeagueRepository leagues;
    private final TeamRepository teams;
    private final DraftPickRepository picks;

    public SleeperDraftPickImporter(Database database) {
        this(new SleeperApiGateway(), database);
    }

    SleeperDraftPickImporter(SleeperGateway gateway, Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.leagues = new LeagueRepository(database);
        this.teams = new TeamRepository(database);
        this.picks = new DraftPickRepository(database);
    }

    public ImportResult importLeague(String sleeperLeagueId)
        throws IOException, InterruptedException, SQLException {
        String normalizedLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
        var sourceLeague = gateway.fetchLeague(normalizedLeagueId);
        var tradedPicks = gateway.fetchTradedPicks(normalizedLeagueId);
        var catalog = new DynastyProcessDraftPickCatalog().fetch();
        return importLeague(sourceLeague, tradedPicks, catalog);
    }

    ImportResult importLeague(
        SleeperJsonParser.SleeperLeague sourceLeague,
        List<SleeperJsonParser.SleeperTradedPick> tradedPicks,
        DynastyProcessDraftPickCatalog.Catalog catalog) throws SQLException {

        Objects.requireNonNull(sourceLeague, "sourceLeague must not be null");
        Objects.requireNonNull(tradedPicks, "tradedPicks must not be null");
        Objects.requireNonNull(catalog, "catalog must not be null");
        if (sourceLeague.season() <= 0) {
            throw new IllegalArgumentException("Sleeper league season is unavailable");
        }
        if (sourceLeague.draftRounds() <= 0) {
            throw new IllegalArgumentException("Sleeper league draft-round count is unavailable");
        }

        var league = leagues.findByExternalId(sourceLeague.id())
            .orElseThrow(() -> new IllegalArgumentException(
                "Sleeper league is not imported locally: " + sourceLeague.id()));

        List<Team> leagueTeams = teams.findByLeagueId(league.getId());
        if (leagueTeams.isEmpty()) {
            throw new IllegalArgumentException("Sleeper league has no imported teams: " + sourceLeague.id());
        }

        Map<Integer, Team> teamByRosterId = new HashMap<>();
        for (Team team : leagueTeams) {
            int rosterId = parseRosterId(team);
            Team duplicate = teamByRosterId.putIfAbsent(rosterId, team);
            if (duplicate != null) {
                throw new IllegalArgumentException("duplicate Sleeper roster id in league: " + rosterId);
            }
        }

        Map<Coordinate, DynastyProcessDraftPickCatalog.PickValue> supported = new LinkedHashMap<>();
        for (var value : catalog.values()) {
            if (value.season() < sourceLeague.season()) continue;
            if (value.round() > sourceLeague.draftRounds()) continue;
            supported.put(new Coordinate(value.season(), value.round()), value);
        }

        Map<AssetKey, Integer> ownerOverrideByAsset = new HashMap<>();
        int unsupportedTradedPicks = 0;
        for (var traded : tradedPicks) {
            Coordinate coordinate = new Coordinate(traded.season(), traded.round());
            if (!supported.containsKey(coordinate)) {
                unsupportedTradedPicks++;
                continue;
            }
            Team original = teamByRosterId.get(traded.originalRosterId());
            Team previousOwner = teamByRosterId.get(traded.previousOwnerRosterId());
            Team owner = teamByRosterId.get(traded.ownerRosterId());
            if (original == null) {
                throw new IllegalArgumentException("traded pick references unknown original Sleeper roster: "
                    + traded.originalRosterId());
            }
            if (previousOwner == null) {
                throw new IllegalArgumentException("traded pick references unknown previous-owner Sleeper roster: "
                    + traded.previousOwnerRosterId());
            }
            if (owner == null) {
                throw new IllegalArgumentException("traded pick references unknown owner Sleeper roster: "
                    + traded.ownerRosterId());
            }
            AssetKey key = new AssetKey(coordinate, traded.originalRosterId());
            Integer duplicateOwner = ownerOverrideByAsset.putIfAbsent(key, traded.ownerRosterId());
            if (duplicateOwner != null) {
                throw new IllegalArgumentException("duplicate Sleeper traded-pick record for "
                    + coordinate.season() + " round " + coordinate.round()
                    + " original roster " + traded.originalRosterId());
            }
        }

        Map<ExistingKey, DraftPick> existingByKey = new HashMap<>();
        for (DraftPick existing : picks.findByLeagueId(league.getId())) {
            existingByKey.put(new ExistingKey(existing.getSeason(), existing.getRound(), existing.getOriginalTeamId()), existing);
        }

        List<DraftPick> desired = new ArrayList<>();
        Set<String> desiredIds = new HashSet<>();
        int tradedOwnershipApplied = 0;
        for (Coordinate coordinate : supported.keySet()) {
            for (Map.Entry<Integer, Team> teamEntry : teamByRosterId.entrySet()) {
                int originalRosterId = teamEntry.getKey();
                Team originalTeam = teamEntry.getValue();
                Integer ownerRosterId = ownerOverrideByAsset.get(new AssetKey(coordinate, originalRosterId));
                Team ownerTeam = ownerRosterId == null ? originalTeam : teamByRosterId.get(ownerRosterId);
                if (ownerRosterId != null) tradedOwnershipApplied++;

                ExistingKey existingKey = new ExistingKey(
                    coordinate.season(), coordinate.round(), originalTeam.getId());
                DraftPick existing = existingByKey.get(existingKey);
                DraftPick desiredPick = new DraftPick(
                    existing == null ? UUID.randomUUID().toString() : existing.getId(),
                    league.getId(),
                    coordinate.season(),
                    coordinate.round(),
                    originalTeam.getId(),
                    ownerTeam.getId(),
                    existing == null ? null : existing.getPickNumber());
                desired.add(desiredPick);
                desiredIds.add(desiredPick.getId());
            }
        }

        // Validation above is complete before writes begin. Reconcile stale coordinates first,
        // then upsert the desired ownership state while preserving stable asset IDs.
        int removed = 0;
        for (DraftPick existing : existingByKey.values()) {
            if (!desiredIds.contains(existing.getId())) {
                if (picks.deleteById(existing.getId())) removed++;
            }
        }
        for (DraftPick desiredPick : desired) picks.save(desiredPick);

        return new ImportResult(
            league.getId(),
            supported.size(),
            leagueTeams.size(),
            desired.size(),
            tradedOwnershipApplied,
            unsupportedTradedPicks,
            removed);
    }

    private static int parseRosterId(Team team) {
        String externalId = team.getExternalId();
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("team has no Sleeper roster id: " + team.getId());
        }
        try {
            int value = Integer.parseInt(externalId.trim());
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid Sleeper roster id on team " + team.getId() + ": " + externalId, e);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record ImportResult(
        String leagueId,
        int supportedCoordinates,
        int teams,
        int picksImported,
        int tradedOwnershipApplied,
        int unsupportedTradedPicks,
        int stalePicksRemoved) {}

    private record Coordinate(int season, int round) {}
    private record AssetKey(Coordinate coordinate, int originalRosterId) {}
    private record ExistingKey(int season, int round, String originalTeamId) {}
}
