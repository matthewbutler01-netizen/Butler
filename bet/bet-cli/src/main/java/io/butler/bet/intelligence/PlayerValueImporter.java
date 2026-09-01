package io.butler.bet.intelligence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerValue;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PlayerValueImporter {
    private final PlayerRepository players;
    private final PlayerValueRepository values;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlayerValueImporter(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.players = new PlayerRepository(database);
        this.values = new PlayerValueRepository(database);
    }

    public ImportResult importJson(Path path) throws IOException, SQLException {
        Objects.requireNonNull(path, "path must not be null");
        List<ValueInput> inputs = mapper.readValue(path.toFile(), new TypeReference<List<ValueInput>>() {});
        if (inputs == null) throw new IllegalArgumentException("player value file must contain a JSON array");

        List<PlayerValue> resolved = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            ValueInput input = inputs.get(i);
            int entry = i + 1;
            if (input == null) throw new IllegalArgumentException("player value entry " + entry + " must not be null");

            String externalPlayerId = requireText(input.externalPlayerId(), "externalPlayerId", entry);
            String source = requireText(input.source(), "source", entry);
            if (!Double.isFinite(input.value()) || input.value() < 0) {
                throw new IllegalArgumentException("value must be finite and non-negative at entry " + entry);
            }

            String dateText = requireText(input.asOfDate(), "asOfDate", entry);
            LocalDate asOfDate;
            try {
                asOfDate = LocalDate.parse(dateText);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("invalid asOfDate for externalPlayerId " + externalPlayerId + ": " + dateText, e);
            }

            Player player = players.findByExternalId(externalPlayerId)
                .orElseThrow(() -> new IllegalArgumentException("unknown externalPlayerId at entry " + entry + ": " + externalPlayerId));
            resolved.add(PlayerValue.create(player.getId(), input.value(), source, asOfDate));
        }

        // Validate and resolve the complete input first, then commit the whole batch atomically.
        values.saveAll(resolved);
        return new ImportResult(resolved.size());
    }

    private static String requireText(String value, String field, int entry) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank at entry " + entry);
        return value.trim();
    }

    public record ValueInput(String externalPlayerId, double value, String source, String asOfDate) {}
    public record ImportResult(int valuesImported) {}
}
