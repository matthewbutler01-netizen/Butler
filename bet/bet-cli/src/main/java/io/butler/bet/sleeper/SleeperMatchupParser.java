package io.butler.bet.sleeper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses the raw week-specific roster and exact matchup identity required from Sleeper responses. */
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
        requireExactPairing(matchups);
        return List.copyOf(matchups);
    }

    static void requireExactPairing(List<SleeperMatchup> matchups) {
        if (matchups == null) throw new IllegalArgumentException("Sleeper matchups must not be null");
        if (matchups.isEmpty()) return;

        Set<Integer> rosterIds = new HashSet<>();
        Map<Integer, Integer> matchupCounts = new LinkedHashMap<>();
        for (SleeperMatchup matchup : matchups) {
            if (matchup == null) throw new IllegalArgumentException("Sleeper matchup entry must not be null");
            if (!rosterIds.add(matchup.rosterId())) {
                throw new IllegalStateException("Duplicate Sleeper matchup roster_id: " + matchup.rosterId());
            }
            matchupCounts.merge(matchup.matchupId(), 1, Integer::sum);
        }
        for (Map.Entry<Integer, Integer> entry : matchupCounts.entrySet()) {
            if (entry.getValue() != 2) {
                throw new IllegalStateException(
                    "Sleeper matchup_id " + entry.getKey() + " must pair exactly two rosters; observed "
                        + entry.getValue());
            }
        }
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

    public record SleeperMatchup(
        int rosterId,
        int matchupId,
        List<String> playerIds,
        List<String> starterIds) {
        public SleeperMatchup {
            if (rosterId <= 0) throw new IllegalArgumentException("rosterId must be positive");
            if (matchupId <= 0) throw new IllegalArgumentException("matchupId must be positive");
            playerIds = List.copyOf(playerIds == null ? List.of() : playerIds);
            starterIds = List.copyOf(starterIds == null ? List.of() : starterIds);
        }
    }
}
