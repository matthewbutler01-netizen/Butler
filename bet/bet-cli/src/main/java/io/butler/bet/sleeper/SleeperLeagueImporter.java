package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class SleeperLeagueImporter {
    private final SleeperClient client;
    private final SleeperJsonParser parser;
    private final LeagueRepository leagues;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final RosterRepository rosters;

    public SleeperLeagueImporter(Database database) {
        this(new SleeperClient(), new SleeperJsonParser(), database);
    }

    SleeperLeagueImporter(SleeperClient client, SleeperJsonParser parser, Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
        this.leagues = new LeagueRepository(database);
        this.teams = new TeamRepository(database);
        this.players = new PlayerRepository(database);
        this.rosters = new RosterRepository(database);
    }

    public ImportResult importLeague(String sleeperLeagueId) throws IOException, InterruptedException, SQLException {
        SleeperJsonParser.SleeperLeague sourceLeague = parser.parseLeague(client.getLeague(sleeperLeagueId));
        var sourceUsers = parser.parseUsers(client.getLeagueUsers(sleeperLeagueId));
        var sourceRosters = parser.parseRosters(client.getLeagueRosters(sleeperLeagueId));
        var sourcePlayers = parser.parsePlayers(client.getNflPlayers());

        String leagueId = "sleeper-league-" + sourceLeague.id();
        League league = new League(leagueId, sourceLeague.id(), sourceLeague.name());
        leagues.save(league);

        Map<String, String> ownerNames = new HashMap<>();
        sourceUsers.forEach(user -> ownerNames.put(user.id(), user.displayName()));

        int teamCount = 0;
        int playerCount = 0;
        int rosterCount = 0;

        for (SleeperJsonParser.SleeperRoster sourceRoster : sourceRosters) {
            String rosterExternalId = Integer.toString(sourceRoster.rosterId());
            String teamId = "sleeper-team-" + sourceLeague.id() + "-" + rosterExternalId;
            String ownerName = sourceRoster.ownerId() == null ? null : ownerNames.get(sourceRoster.ownerId());
            String teamName = ownerName == null || ownerName.isBlank()
                    ? "Roster " + rosterExternalId
                    : ownerName;

            teams.save(new Team(teamId, rosterExternalId, leagueId, teamName));
            teamCount++;

            for (String sleeperPlayerId : sourceRoster.playerIds()) {
                SleeperJsonParser.SleeperPlayer sourcePlayer = sourcePlayers.get(sleeperPlayerId);
                if (sourcePlayer == null || sourcePlayer.position() == null || sourcePlayer.position().isBlank()) {
                    continue;
                }

                String playerId = "sleeper-player-" + sleeperPlayerId;
                players.save(new Player(
                        playerId,
                        sleeperPlayerId,
                        sourcePlayer.displayName(),
                        sourcePlayer.position(),
                        sourcePlayer.nflTeam()));
                playerCount++;

                String rosterId = "sleeper-roster-" + sourceLeague.id() + "-" + rosterExternalId + "-" + sleeperPlayerId;
                rosters.save(new Roster(rosterId, null, teamId, playerId, "ROSTER"));
                rosterCount++;
            }
        }

        return new ImportResult(leagueId, teamCount, playerCount, rosterCount);
    }

    public record ImportResult(String leagueId, int teamsImported, int playerReferencesImported, int rosterEntriesImported) {}
}
