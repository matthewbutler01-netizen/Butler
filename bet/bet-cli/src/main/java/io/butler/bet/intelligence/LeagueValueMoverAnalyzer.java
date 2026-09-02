package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.domain.Player;
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
    private final PlayerValueChangeAnalyzer changes;

    public LeagueValueMoverAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueAnalyzer(database);
        this.rosters = new RosterRepository(database);
        this.players = new PlayerRepository(database);
        this.changes = new PlayerValueChangeAnalyzer(database);
    }

    public MoverReport analyze(String leagueId, String source) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedSource = requireText(source, "source");
        var league = leagues.analyze(normalizedLeagueId);
        List<Mover> movers = new ArrayList<>();

        for (LeagueAnalyzer.TeamReport team : league.teams()) {
            for (Roster roster : rosters.findByTeamId(team.teamId())) {
                var change = changes.latestChange(roster.getPlayerId(), normalizedSource);
                if (change.isEmpty()) continue;
                Player player = players.findById(roster.getPlayerId()).orElseThrow(
                    () -> new IllegalStateException("rostered player not found: " + roster.getPlayerId()));
                var value = change.orElseThrow();
                movers.add(new Mover(
                    team.teamId(),
                    team.teamName(),
                    player.getId(),
                    player.getDisplayName(),
                    player.getPosition(),
                    player.getNflTeam(),
                    value.previousDate(),
                    value.previousValue(),
                    value.latestDate(),
                    value.latestValue(),
                    value.delta()));
            }
        }

        movers.sort(Comparator.comparingDouble((Mover mover) -> Math.abs(mover.delta())).reversed()
            .thenComparing(Mover::playerName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Mover::playerId));

        return new MoverReport(normalizedLeagueId, normalizedSource, List.copyOf(movers));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record MoverReport(String leagueId, String source, List<Mover> movers) {}

    public record Mover(String teamId, String teamName,
                        String playerId, String playerName, String position, String nflTeam,
                        LocalDate previousDate, double previousValue,
                        LocalDate latestDate, double latestValue,
                        double delta) {}
}
