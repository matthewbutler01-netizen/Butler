package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerValue;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class LeagueValueMoverAnalyzer {
    private final TeamRepository teams;
    private final LeagueValueSourceResolver sourceResolver;
    private final RosterRepository rosters;
    private final PlayerRepository players;
    private final PlayerValueRepository values;
    private final SourceValueWindowResolver windows;

    public LeagueValueMoverAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.teams = new TeamRepository(database);
        this.sourceResolver = new LeagueValueSourceResolver(database);
        this.rosters = new RosterRepository(database);
        this.players = new PlayerRepository(database);
        this.values = new PlayerValueRepository(database);
        this.windows = new SourceValueWindowResolver(database);
    }

    public MoverReport analyze(String leagueId) throws SQLException {
        return analyze(leagueId, sourceResolver.resolve(leagueId));
    }

    public MoverReport analyze(String leagueId, LocalDate previousDate, LocalDate latestDate) throws SQLException {
        return analyze(leagueId, sourceResolver.resolve(leagueId), previousDate, latestDate);
    }

    public MoverReport analyze(String leagueId, String source) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedSource = requireText(source, "source");
        var window = windows.latestWindow(normalizedSource);
        if (window.isPresent()) {
            return analyze(normalizedLeagueId, normalizedSource,
                window.orElseThrow().previousDate(), window.orElseThrow().latestDate());
        }

        int totalPlayers = rosters.findByLeagueId(normalizedLeagueId).size();
        return new MoverReport(normalizedLeagueId, normalizedSource, null, null,
            totalPlayers, 0, totalPlayers, List.of());
    }

    public MoverReport analyze(String leagueId, String source,
                               LocalDate previousDate, LocalDate latestDate) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedSource = requireText(source, "source");
        LocalDate normalizedPreviousDate = Objects.requireNonNull(previousDate, "previousDate must not be null");
        LocalDate normalizedLatestDate = Objects.requireNonNull(latestDate, "latestDate must not be null");
        if (!normalizedPreviousDate.isBefore(normalizedLatestDate)) {
            throw new IllegalArgumentException("previousDate must be before latestDate");
        }

        Map<String, String> teamNames = new HashMap<>();
        for (Team team : teams.findByLeagueId(normalizedLeagueId)) {
            teamNames.put(team.getId(), team.getName());
        }

        List<Roster> leagueRosters = rosters.findByLeagueId(normalizedLeagueId);
        Map<String, Player> playersById = new HashMap<>();
        for (Player player : players.findByLeagueId(normalizedLeagueId)) {
            playersById.put(player.getId(), player);
        }

        Map<String, PlayerValue> previousValues = new HashMap<>();
        Map<String, PlayerValue> latestValues = new HashMap<>();
        for (PlayerValue value : values.findBySourceAndDates(
                normalizedSource, normalizedPreviousDate, normalizedLatestDate)) {
            if (value.getAsOfDate().equals(normalizedPreviousDate)) {
                previousValues.put(value.getPlayerId(), value);
            } else if (value.getAsOfDate().equals(normalizedLatestDate)) {
                latestValues.put(value.getPlayerId(), value);
            }
        }

        List<Mover> movers = new ArrayList<>();
        for (Roster roster : leagueRosters) {
            PlayerValue previous = previousValues.get(roster.getPlayerId());
            PlayerValue latest = latestValues.get(roster.getPlayerId());
            if (previous == null || latest == null) continue;

            Player player = playersById.get(roster.getPlayerId());
            if (player == null) {
                throw new IllegalStateException("rostered player not found: " + roster.getPlayerId());
            }
            String teamName = teamNames.get(roster.getTeamId());
            if (teamName == null) {
                throw new IllegalStateException("roster team not found in league: " + roster.getTeamId());
            }
            movers.add(new Mover(
                roster.getTeamId(),
                teamName,
                player.getId(),
                player.getDisplayName(),
                player.getPosition(),
                player.getNflTeam(),
                normalizedPreviousDate,
                previous.getValue(),
                normalizedLatestDate,
                latest.getValue(),
                latest.getValue() - previous.getValue()));
        }

        movers.sort(Comparator.comparingDouble((Mover mover) -> Math.abs(mover.delta())).reversed()
            .thenComparing(Mover::playerName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Mover::playerId));

        int totalPlayers = leagueRosters.size();
        int comparablePlayers = movers.size();
        return new MoverReport(normalizedLeagueId, normalizedSource, normalizedPreviousDate, normalizedLatestDate,
            totalPlayers, comparablePlayers, totalPlayers - comparablePlayers, List.copyOf(movers));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record MoverReport(String leagueId, String source,
                              LocalDate previousDate, LocalDate latestDate,
                              int totalPlayers, int comparablePlayers, int missingPlayers,
                              List<Mover> movers) {
        public double coveragePercent() {
            return totalPlayers == 0 ? 0.0 : (comparablePlayers * 100.0) / totalPlayers;
        }
    }

    public record Mover(String teamId, String teamName,
                        String playerId, String playerName, String position, String nflTeam,
                        LocalDate previousDate, double previousValue,
                        LocalDate latestDate, double latestValue,
                        double delta) {}
}
