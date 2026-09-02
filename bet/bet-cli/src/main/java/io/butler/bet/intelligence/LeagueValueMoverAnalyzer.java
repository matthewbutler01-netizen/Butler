package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerValue;
import io.butler.bet.domain.Roster;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class LeagueValueMoverAnalyzer {
    private final LeagueAnalyzer leagues;
    private final RosterRepository rosters;
    private final PlayerRepository players;
    private final PlayerValueRepository values;
    private final SourceValueWindowResolver windows;

    public LeagueValueMoverAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueAnalyzer(database);
        this.rosters = new RosterRepository(database);
        this.players = new PlayerRepository(database);
        this.values = new PlayerValueRepository(database);
        this.windows = new SourceValueWindowResolver(database);
    }

    public MoverReport analyze(String leagueId, String source) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedSource = requireText(source, "source");
        var league = leagues.analyze(normalizedLeagueId);
        int totalPlayers = league.teams().stream().mapToInt(LeagueAnalyzer.TeamReport::rosterSize).sum();
        var window = windows.latestWindow(normalizedSource);
        if (window.isEmpty()) {
            return new MoverReport(normalizedLeagueId, normalizedSource, null, null,
                totalPlayers, 0, totalPlayers, List.of());
        }
        return analyze(normalizedLeagueId, normalizedSource,
            window.orElseThrow().previousDate(), window.orElseThrow().latestDate());
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

        var league = leagues.analyze(normalizedLeagueId);
        int totalPlayers = league.teams().stream().mapToInt(LeagueAnalyzer.TeamReport::rosterSize).sum();
        List<Mover> movers = new ArrayList<>();

        for (LeagueAnalyzer.TeamReport team : league.teams()) {
            for (Roster roster : rosters.findByTeamId(team.teamId())) {
                List<PlayerValue> history = values.findByPlayerIdAndSource(roster.getPlayerId(), normalizedSource);
                PlayerValue previous = valueOn(history, normalizedPreviousDate);
                PlayerValue latest = valueOn(history, normalizedLatestDate);
                if (previous == null || latest == null) continue;
                Player player = players.findById(roster.getPlayerId()).orElseThrow(
                    () -> new IllegalStateException("rostered player not found: " + roster.getPlayerId()));
                movers.add(new Mover(
                    team.teamId(),
                    team.teamName(),
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
        }

        movers.sort(Comparator.comparingDouble((Mover mover) -> Math.abs(mover.delta())).reversed()
            .thenComparing(Mover::playerName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Mover::playerId));

        int comparablePlayers = movers.size();
        return new MoverReport(normalizedLeagueId, normalizedSource, normalizedPreviousDate, normalizedLatestDate,
            totalPlayers, comparablePlayers, totalPlayers - comparablePlayers, List.copyOf(movers));
    }

    private static PlayerValue valueOn(List<PlayerValue> history, LocalDate date) {
        for (PlayerValue value : history) {
            if (value.getAsOfDate().equals(date)) return value;
        }
        return null;
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
