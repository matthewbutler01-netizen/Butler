package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.domain.PlayerValue;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PlayerValueChangeAnalyzer {
    private final PlayerValueRepository values;

    public PlayerValueChangeAnalyzer(Database database) {
        this.values = new PlayerValueRepository(Objects.requireNonNull(database, "database must not be null"));
    }

    public Optional<ValueChange> latestChange(String playerId, String source) throws SQLException {
        List<PlayerValue> history = values.findByPlayerIdAndSource(playerId, source);
        if (history.size() < 2) return Optional.empty();

        PlayerValue latest = history.get(0);
        PlayerValue previous = history.get(1);
        return Optional.of(new ValueChange(
            latest.getPlayerId(),
            latest.getSource(),
            previous.getAsOfDate(),
            previous.getValue(),
            latest.getAsOfDate(),
            latest.getValue(),
            latest.getValue() - previous.getValue()));
    }

    public record ValueChange(String playerId, String source,
                              LocalDate previousDate, double previousValue,
                              LocalDate latestDate, double latestValue,
                              double delta) {}
}
