package io.butler.bet.sleeper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Parses only the raw week-specific roster evidence required from Sleeper matchup responses. */
public final class SleeperMatchupParser {
    private final ObjectMapper mapper;

    public SleeperMatchupParser() {
        this(new ObjectMapper());
    }

    SleeperMatchupParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<SleeperMatchup> parse(String json) throws JsonProcessingException {
        JsonNode root = mapper.readTree(json);
        if (!root.isArray()) throw new IllegalArgumentException("Sleeper matchup response must be an array");
        List<SleeperMatchup> matchups = new ArrayList<>();
        for (JsonNode node : root) {
            int rosterId = node.path("roster_id").asInt(0);
            if (rosterId <= 0) throw new IllegalArgumentException("Missing or invalid Sleeper field: roster_id");
            int matchupId = node.path("matchup_id").asInt(0);
            if (matchupId <= 0) throw new IllegalArgumentException("Missing or invalid Sleeper field: matchup_id");
            matchups.add(new SleeperMatchup(
                rosterId,
                matchupId,
                stringList(node, "players"),
                stringList(node, "starters")));
        }
        return List.copyOf(matchups);
    }

    private static List<String> stringList(JsonNode node, String field) {
        JsonNode values = node.get(field);
        if (values == null || values.isNull()) return List.of();
        if (!values.isArray()) throw new IllegalArgumentException("Sleeper field must be an array: " + field);
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            String text = value.asText(null);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Sleeper " + field + " entry must not be blank");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    public record SleeperMatchup(int rosterId, int matchupId, List<String> playerIds, List<String> starterIds) {
        /**
         * Compatibility constructor for older synthetic fixtures that do not exercise matchup pairing.
         * Production provider responses always use the four-argument constructor through parse().
         */
        public SleeperMatchup(int rosterId, List<String> playerIds, List<String> starterIds) {
            this(rosterId, 1, playerIds, starterIds);
        }

        public SleeperMatchup {
            if (rosterId <= 0) throw new IllegalArgumentException("rosterId must be positive");
            if (matchupId <= 0) throw new IllegalArgumentException("matchupId must be positive");
            playerIds = List.copyOf(playerIds == null ? List.of() : playerIds);
            starterIds = List.copyOf(starterIds == null ? List.of() : starterIds);
        }
    }
}
