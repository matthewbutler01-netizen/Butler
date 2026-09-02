package io.butler.bet.sleeper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SleeperJsonParser {
    private final ObjectMapper mapper;

    public SleeperJsonParser() {
        this(new ObjectMapper());
    }

    SleeperJsonParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SleeperLeague parseLeague(String json) throws JsonProcessingException {
        JsonNode root = mapper.readTree(json);
        return new SleeperLeague(requiredText(root, "league_id"), requiredText(root, "name"),
            stringList(root, "roster_positions"));
    }

    public List<SleeperUser> parseUsers(String json) throws JsonProcessingException {
        JsonNode root = mapper.readTree(json);
        List<SleeperUser> users = new ArrayList<>();
        for (JsonNode node : root) {
            JsonNode metadata = node.path("metadata");
            users.add(new SleeperUser(
                    requiredText(node, "user_id"),
                    optionalText(node, "display_name"),
                    optionalText(metadata, "team_name")));
        }
        return List.copyOf(users);
    }

    public List<SleeperRoster> parseRosters(String json) throws JsonProcessingException {
        JsonNode root = mapper.readTree(json);
        List<SleeperRoster> rosters = new ArrayList<>();
        for (JsonNode node : root) {
            int rosterId = node.path("roster_id").asInt(0);
            if (rosterId <= 0) {
                throw new IllegalArgumentException("Missing or invalid Sleeper field: roster_id");
            }
            rosters.add(new SleeperRoster(
                    rosterId,
                    optionalText(node, "owner_id"),
                    stringList(node, "players"),
                    stringList(node, "starters"),
                    stringList(node, "reserve"),
                    stringList(node, "taxi")));
        }
        return List.copyOf(rosters);
    }

    public Map<String, SleeperPlayer> parsePlayers(String json) throws JsonProcessingException {
        JsonNode root = mapper.readTree(json);
        Map<String, SleeperPlayer> players = new HashMap<>();
        root.fields().forEachRemaining(entry -> {
            JsonNode node = entry.getValue();
            String name = optionalText(node, "full_name");
            if (name == null) {
                String first = optionalText(node, "first_name");
                String last = optionalText(node, "last_name");
                name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
            }
            players.put(entry.getKey(), new SleeperPlayer(
                    entry.getKey(),
                    name == null || name.isBlank() ? entry.getKey() : name,
                    optionalText(node, "position"),
                    optionalText(node, "team")));
        });
        return Map.copyOf(players);
    }

    private static List<String> stringList(JsonNode node, String field) {
        JsonNode values = node.path(field);
        if (!values.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            String text = value.asText(null);
            if (text != null && !text.isBlank()) {
                result.add(text);
            }
        });
        return List.copyOf(result);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing Sleeper field: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public record SleeperLeague(String id, String name, List<String> rosterPositions) {
        public SleeperLeague(String id, String name) {
            this(id, name, List.of());
        }

        public SleeperLeague {
            rosterPositions = rosterPositions == null ? List.of() : List.copyOf(rosterPositions);
        }
    }
    public record SleeperUser(String id, String displayName, String teamName) {}
    public record SleeperRoster(
            int rosterId,
            String ownerId,
            List<String> playerIds,
            List<String> starterIds,
            List<String> reserveIds,
            List<String> taxiIds) {}
    public record SleeperPlayer(String id, String displayName, String position, String nflTeam) {}
}
