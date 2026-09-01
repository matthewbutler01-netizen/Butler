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
        int imported = 0;
        int missingPlayers = 0;

        for (ValueInput input : inputs) {
            validate(input);
            Player player = players.findByExternalId(input.playerId()).orElse(null);
            if (player == null) {
                missingPlayers++;
                continue;
            }
            values.save(PlayerValue.create(player.getId(), input.value(), input.source(), LocalDate.parse(input.asOfDate())));
            imported++;
        }
        return new ImportResult(inputs.size(), imported, missingPlayers);
    }

    private static void validate(ValueInput input) {
        if (input == null) throw new IllegalArgumentException("player value entry must not be null");
        if (input.playerId() == null || input.playerId().isBlank()) throw new IllegalArgumentException("playerId must not be blank");
        if (!Double.isFinite(input.value()) || input.value() < 0) throw new IllegalArgumentException("value must be finite and non-negative");
        if (input.source() == null || input.source().isBlank()) throw new IllegalArgumentException("source must not be blank");
        if (input.asOfDate() == null || input.asOfDate().isBlank()) throw new IllegalArgumentException("asOfDate must not be blank");
        LocalDate.parse(input.asOfDate());
    }

    public record ValueInput(String playerId, double value, String source, String asOfDate) {}
    public record ImportResult(int entriesRead, int imported, int missingPlayers) {}
}
