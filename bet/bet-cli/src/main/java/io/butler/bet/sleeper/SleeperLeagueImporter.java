package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueLineupConfigurationRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.LeagueValueFormatRepository;
import io.butler.bet.data.PlayerFantasyPositionObservationRepository;
import io.butler.bet.data.PlayerFantasyPositionRepository;
import io.butler.bet.data.PlayerProfileSnapshotRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamSeasonPerformanceRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueValueFormat;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerFantasyPositionObservation;
import io.butler.bet.domain.PlayerProfileSnapshot;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import io.butler.bet.domain.TeamSeasonPerformance;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class SleeperLeagueImporter {
    private static final String PROFILE_SOURCE = "sleeper";
    private static final String PERFORMANCE_SOURCE = "sleeper";
    private static final String FANTASY_POSITION_SOURCE = "sleeper";

    private final SleeperGateway gateway;
    private final LeagueRepository leagues;
    private final LeagueValueFormatRepository leagueFormats;
    private final LeagueLineupConfigurationRepository lineupConfiguration;
    private final LeagueScoringSettingsRepository scoringSettings;
    private final TeamRepository teams;
    private final PlayerRepository players;
    private final PlayerFantasyPositionRepository playerFantasyPositions;
    private final PlayerFantasyPositionObservationRepository playerFantasyPositionObservations;
    private final PlayerProfileSnapshotRepository playerProfiles;
    private final RosterRepository rosters;
    private final TeamSeasonPerformanceRepository performance;

    public SleeperLeagueImporter(Database database) { this(new SleeperApiGateway(), database); }

    SleeperLeagueImporter(SleeperGateway gateway, Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
        this.leagues = new LeagueRepository(database);
        this.leagueFormats = new LeagueValueFormatRepository(database);
        this.lineupConfiguration = new LeagueLineupConfigurationRepository(database);
        this.scoringSettings = new LeagueScoringSettingsRepository(database);
        this.teams = new TeamRepository(database);
        this.players = new PlayerRepository(database);
        this.playerFantasyPositions = new PlayerFantasyPositionRepository(database);
        this.playerFantasyPositionObservations = new PlayerFantasyPositionObservationRepository(database);
        this.playerProfiles = new PlayerProfileSnapshotRepository(database);
        this.rosters = new RosterRepository(database);
        this.performance = new TeamSeasonPerformanceRepository(database);
    }

    public ImportResult importLeague(String sleeperLeagueId) throws IOException, InterruptedException, SQLException {
        SleeperJsonParser.SleeperLeague sourceLeague = gateway.fetchLeague(sleeperLeagueId);
        var sourceUsers = gateway.fetchUsers(sleeperLeagueId);
        var sourceRosters = gateway.fetchRosters(sleeperLeagueId);
        var sourcePlayers = gateway.fetchPlayers();
        LocalDate importAsOfDate = LocalDate.now(ZoneOffset.UTC);
        Integer season = sourceLeague.season() > 0 ? sourceLeague.season() : null;

        League league = leagues.findByExternalId(sourceLeague.id())
                .map(existing -> new League(existing.getId(), sourceLeague.id(), sourceLeague.name(), season))
                .orElseGet(() -> new League(UUID.randomUUID().toString(), sourceLeague.id(), sourceLeague.name(), season));
        leagues.save(league);
        LeagueValueFormat valueFormat = LeagueValueFormat.fromRosterPositions(sourceLeague.rosterPositions());
        leagueFormats.save(league.getId(), valueFormat);
        lineupConfiguration.replace(league.getId(), sourceLeague.rosterPositions());
        scoringSettings.replace(league.getId(), sourceLeague.scoringSettings());

        Map<String, SleeperJsonParser.SleeperUser> owners = new HashMap<>();
        sourceUsers.forEach(user -> owners.put(user.id(), user));
        int teamCount = 0;
        Set<String> importedPlayerIds = new HashSet<>();
        int rosterCount = 0;
        int performanceCount = 0;

        for (SleeperJsonParser.SleeperRoster sourceRoster : sourceRosters) {
            String rosterExternalId = Integer.toString(sourceRoster.rosterId());
            SleeperJsonParser.SleeperUser owner = sourceRoster.ownerId() == null ? null : owners.get(sourceRoster.ownerId());
            String teamName = chooseTeamName(owner, rosterExternalId);
            Team team = teams.findByExternalId(league.getId(), rosterExternalId)
                    .map(existing -> new Team(existing.getId(), rosterExternalId, league.getId(), teamName))
                    .orElseGet(() -> new Team(UUID.randomUUID().toString(), rosterExternalId, league.getId(), teamName));
            teams.save(team);
            teamCount++;

            if (season != null) {
                performance.save(new TeamSeasonPerformance(
                    league.getId(), team.getId(), season,
                    sourceRoster.wins(), sourceRoster.losses(), sourceRoster.ties(),
                    sourceRoster.pointsFor(), sourceRoster.pointsAgainst(),
                    PERFORMANCE_SOURCE, importAsOfDate));
                performanceCount++;
            }

            Set<String> desiredInternalPlayerIds = new HashSet<>();
            for (String sleeperPlayerId : sourceRoster.playerIds()) {
                SleeperJsonParser.SleeperPlayer sourcePlayer = sourcePlayers.get(sleeperPlayerId);
                Player player = resolvePlayer(sleeperPlayerId, sourcePlayer);
                players.save(player);
                playerFantasyPositions.replace(player.getId(),
                    sourcePlayer == null ? List.of() : sourcePlayer.fantasyPositions());
                if (sourcePlayer != null) {
                    playerFantasyPositionObservations.replace(new PlayerFantasyPositionObservation(
                        player.getId(), FANTASY_POSITION_SOURCE, importAsOfDate,
                        sourcePlayer.fantasyPositions()));
                }
                saveProfileSnapshot(player, sourcePlayer, importAsOfDate);
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
        return new ImportResult(league.getId(), teamCount, importedPlayerIds.size(), rosterCount, performanceCount, valueFormat);
    }

    private void saveProfileSnapshot(Player player, SleeperJsonParser.SleeperPlayer sourcePlayer,
                                     LocalDate asOfDate) throws SQLException {
        if (sourcePlayer == null) return;
        PlayerProfileSnapshot snapshot = PlayerProfileSnapshot.create(player.getId(),
            sourcePlayer.reportedAge(), sourcePlayer.yearsExperience(), PROFILE_SOURCE, asOfDate);
        playerProfiles.save(snapshot);
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
                               int rosterEntriesImported, int performanceSnapshotsImported,
                               LeagueValueFormat valueFormat) {
        public ImportResult(String leagueId, int teamsImported, int playersImported,
                            int rosterEntriesImported, LeagueValueFormat valueFormat) {
            this(leagueId, teamsImported, playersImported, rosterEntriesImported, 0, valueFormat);
        }
    }
}
