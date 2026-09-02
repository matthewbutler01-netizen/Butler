package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.domain.Player;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class SourceValueMoverAnalyzer {
    private final PlayerRepository players;
    private final PlayerValueChangeAnalyzer changes;

    public SourceValueMoverAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.players = new PlayerRepository(database);
        this.changes = new PlayerValueChangeAnalyzer(database);
    }

    public MoverReport analyze(String source) throws SQLException {
        String normalizedSource = requireText(source, "source");
        List<Mover> movers = new ArrayList<>();

        for (Player player : players.findAll()) {
            var change = changes.latestChange(player.getId(), normalizedSource);
            if (change.isEmpty()) continue;
            var value = change.orElseThrow();
            movers.add(new Mover(
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

        movers.sort(Comparator.comparingDouble((Mover mover) -> Math.abs(mover.delta())).reversed()
            .thenComparing(Mover::playerName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Mover::playerId));

        return new MoverReport(normalizedSource, List.copyOf(movers));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record MoverReport(String source, List<Mover> movers) {}

    public record Mover(String playerId, String playerName, String position, String nflTeam,
                        LocalDate previousDate, double previousValue,
                        LocalDate latestDate, double latestValue,
                        double delta) {}
}
