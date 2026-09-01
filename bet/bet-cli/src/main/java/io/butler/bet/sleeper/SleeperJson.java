package io.butler.bet.sleeper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public final class SleeperJson {
    private final ObjectMapper mapper = new ObjectMapper();

    public SleeperLeague parseLeague(String json) {
        JsonNode node = object(json, "league");
        return new SleeperLeague(requiredText(node, "league_id"), requiredText(node, "name"));
    }

    public List<SleeperUser> parseUsers(String json) {
        JsonNode root = array(json, "users");
        List<SleeperUser> users = new ArrayList<>();
        for (JsonNode node : root) {
            users.add(new SleeperUser(requiredText(node, "user_id"), optionalText(node, "display_name")));
        }
        return List.copyOf(users);
    }

    public List<SleeperRoster> parseRosters(String json) {
        JsonNode root = array(json, "rosters");
        List<SleeperRoster> rosters = new ArrayList<>();
        for (JsonNode node : root) {
            List<String> players = stringArray(node.get("players"));
            List<String> starters = stringArray(node.get("starters"));
            rosters.add(new SleeperRoster(
                    requiredText(node, "roster_id"),
                    optionalText(node, "owner_id"),
                    players,
                    starters));
        }
        return List.copyOf(rosters);
    }

    public JsonNode parsePlayerMap(String json) {
        return object(json, "players");
    }

    private JsonNode object(String json, String label) {
        JsonNode root = read(json);
        if (!root.isObject()) throw new IllegalArgumentException("Sleeper " + label + " response must be an object");
        return root;
    }

    private JsonNode array(String json, String label) {
        JsonNode root = read(json);
        if (!root.isArray()) throw new IllegalArgumentException("Sleeper " + label + " response must be an array");
        return root;
    }

    private JsonNode read(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("Sleeper JSON must not be blank");
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid Sleeper JSON", e);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) throw new IllegalArgumentException("Missing Sleeper field: " + field);
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static List<String> stringArray(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException("Expected Sleeper array");
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isNull() && !value.asText().isBlank()) values.add(value.asText().trim());
        }
        return List.copyOf(values);
    }

    public record SleeperLeague(String leagueId, String name) {}
    public record SleeperUser(String userId, String displayName) {}
    public record SleeperRoster(String rosterId, String ownerId, List<String> players, List<String> starters) {}
}
