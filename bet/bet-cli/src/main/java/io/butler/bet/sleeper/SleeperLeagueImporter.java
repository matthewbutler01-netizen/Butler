package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueValueFormatRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueValueFormat;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class SleeperLeagueImporter {
    private final SleeperGateway gateway;
    private final LeagueRepository leagues;
    private final LeagueValueFormatRepository leagueFormats;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final RosterRepository rosters;

    public SleeperLeagueImporter(Database database) { this(new SleeperApiGateway(), database); }

    SleeperLeagueImporter(SleeperGateway gateway, Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.leagues = new LeagueRepository(database);
        this.leagueFormats = new LeagueValueFormatRepository(database);
        this.teams = new TeamRepository(database);
        this.players = new PlayerRepository(database);
        this.rosters = new RosterRepository(database);
    }

    public ImportResult importLeague(String sleeperLeagueId) throws IOException, InterruptedException, SQLException {
        SleeperJsonParser.SleeperLeague sourceLeague = gateway.fetchLeague(sleeperLeagueId);
        var sourceUsers = gateway.fetchUsers(sleeperLeagueId);
        var sourceRosters = gateway.fetchRosters(sleeperLeagueId);
        var sourcePlayers = gateway.fetchPlayers();

        League league = leagues.findByExternalId(sourceLeague.id())
                .map(existing -> new League(existing.getId(), sourceLeague.id(), sourceLeague.name()))
                .orElseGet(() -> new League(UUID.randomUUID().toString(), sourceLeague.id(), sourceLeague.name()));
        leagues.save(league);
        LeagueValueFormat valueFormat = LeagueValueFormat.fromRosterPositions(sourceLeague.rosterPositions());
        leagueFormats.save(league.getId(), valueFormat);

        Map<String, SleeperJsonParser.SleeperUser> owners = new HashMap<>();
        sourceUsers.forEach(user -> owners.put(user.id(), user));
        int teamCount = 0;
        Set<String> importedPlayerIds = new HashSet<>();
        int rosterCount = 0;

        for (SleeperJsonParser.SleeperRoster sourceRoster : sourceRosters) {
            String rosterExternalId = Integer.toString(sourceRoster.rosterId());
            SleeperJsonParser.SleeperUser owner = sourceRoster.ownerId() == null ? null : owners.get(sourceRoster.ownerId());
            String teamName = chooseTeamName(owner, rosterExternalId);
            Team team = teams.findByExternalId(league.getId(), rosterExternalId)
                    .map(existing -> new Team(existing.getId(), rosterExternalId, league.getId(), teamName))
                    .orElseGet(() -> new Team(UUID.randomUUID().toString(), rosterExternalId, league.getId(), teamName));
            teams.save(team);
            teamCount++;

            Set<String> desiredInternalPlayerIds = new HashSet<>();
            for (String sleeperPlayerId : sourceRoster.playerIds()) {
                SleeperJsonParser.SleeperPlayer sourcePlayer = sourcePlayers.get(sleeperPlayerId);
                Player player = resolvePlayer(sleeperPlayerId, sourcePlayer);
                players.save(player);
                importedPlayerIds.add(player.getId());
                desiredInternalPlayerIds.add(player.getId());

                String slot = slotFor(sourceRoster, sleeperPlayerId);
                Roster roster = rosters.findByTeamAndPlayer(team.getId(), player.getId())
                        .map(existing -> new Roster(existing.getId(), existing.getExternalId(), team.getId(), player.getId(), slot))
                        .orElseGet(() -> new Roster(UUID.randomUUID().toString(), null, team.getId(), player.getId(), slot));
                rosters.save(roster);
                rosterCount++;
            }

            for (Roster existing : rosters.findByTeamId(team.getId())) {
                if (!desiredInternalPlayerIds.contains(existing.getPlayerId())) rosters.deleteById(existing.getId());
            }
        }

        Set<String> activeRosterExternalIds = new HashSet<>();
        sourceRosters.forEach(roster -> activeRosterExternalIds.add(Integer.toString(roster.rosterId())));
        for (Team existing : teams.findByLeagueId(league.getId())) {
            if (existing.getExternalId() != null && !activeRosterExternalIds.contains(existing.getExternalId())) teams.deleteById(existing.getId());
        }
        return new ImportResult(league.getId(), teamCount, importedPlayerIds.size(), rosterCount, valueFormat);
    }

    private Player resolvePlayer(String sleeperPlayerId, SleeperJsonParser.SleeperPlayer sourcePlayer) throws SQLException {
        Player existing = players.findByExternalId(sleeperPlayerId).orElse(null);
        if (sourcePlayer == null) {
            if (existing != null) return existing;
            return new Player(UUID.randomUUID().toString(), sleeperPlayerId, "Sleeper Player " + sleeperPlayerId, "UNKNOWN", null);
        }

        String displayName = usable(sourcePlayer.displayName())
                ? sourcePlayer.displayName().trim()
                : existing != null ? existing.getDisplayName() : "Sleeper Player " + sleeperPlayerId;
        String position = usable(sourcePlayer.position())
                ? sourcePlayer.position().trim()
                : existing != null ? existing.getPosition() : "UNKNOWN";
        String nflTeam = usable(sourcePlayer.nflTeam())
                ? sourcePlayer.nflTeam().trim()
                : existing != null ? existing.getNflTeam() : null;
        String id = existing == null ? UUID.randomUUID().toString() : existing.getId();
        return new Player(id, sleeperPlayerId, displayName, position, nflTeam);
    }

    private static boolean usable(String value) { return value != null && !value.isBlank(); }

    private static String chooseTeamName(SleeperJsonParser.SleeperUser owner, String rosterExternalId) {
        if (owner != null && owner.teamName() != null && !owner.teamName().isBlank()) return owner.teamName();
        if (owner != null && owner.displayName() != null && !owner.displayName().isBlank()) return owner.displayName();
        return "Roster " + rosterExternalId;
    }

    private static String slotFor(SleeperJsonParser.SleeperRoster roster, String playerId) {
        if (roster.taxiIds().contains(playerId)) return "TAXI";
        if (roster.reserveIds().contains(playerId)) return "RESERVE";
        if (roster.starterIds().contains(playerId)) return "STARTER";
        return "BENCH";
    }

    public record ImportResult(String leagueId, int teamsImported, int playersImported,
                               int rosterEntriesImported, LeagueValueFormat valueFormat) {}
}
