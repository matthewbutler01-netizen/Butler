package io.butler.bet.sleeper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        return parseLeagueNode(mapper.readTree(json));
    }

    public List<SleeperLeague> parseLeagues(String json) throws JsonProcessingException {
        JsonNode root = mapper.readTree(json);
        if (!root.isArray()) throw new IllegalArgumentException("Sleeper leagues payload must be an array");
        List<SleeperLeague> leagues = new ArrayList<>();
        for (JsonNode node : root) leagues.add(parseLeagueNode(node));
        return List.copyOf(leagues);
    }

    private static SleeperLeague parseLeagueNode(JsonNode root) {
        JsonNode settings = root.path("settings");
        return new SleeperLeague(
            requiredText(root, "league_id"),
            requiredText(root, "name"),
            stringList(root, "roster_positions"),
            positiveInt(root, "season"),
            settings.path("type").asInt(0),
            settings.path("draft_rounds").asInt(0),
            numericMap(root, "scoring_settings"));
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
            if (rosterId <= 0) throw new IllegalArgumentException("Missing or invalid Sleeper field: roster_id");
            JsonNode settings = node.path("settings");
            rosters.add(new SleeperRoster(
                    rosterId,
                    optionalText(node, "owner_id"),
                    stringList(node, "players"),
                    stringList(node, "starters"),
                    stringList(node, "reserve"),
                    stringList(node, "taxi"),
                    nonNegativeInt(settings, "wins"),
                    nonNegativeInt(settings, "losses"),
                    nonNegativeInt(settings, "ties"),
                    sleeperPoints(settings, "fpts", "fpts_decimal"),
                    sleeperPoints(settings, "fpts_against", "fpts_against_decimal")));
        }
        return List.copyOf(rosters);
    }

    public List<SleeperTradedPick> parseTradedPicks(String json) throws JsonProcessingException {
        JsonNode root = mapper.readTree(json);
        List<SleeperTradedPick> picks = new ArrayList<>();
        for (JsonNode node : root) {
            int season = positiveInt(node, "season");
            int round = node.path("round").asInt(0);
            int originalRosterId = node.path("roster_id").asInt(0);
            int previousOwnerRosterId = node.path("previous_owner_id").asInt(0);
            int ownerRosterId = node.path("owner_id").asInt(0);
            if (season <= 0) throw new IllegalArgumentException("Missing or invalid Sleeper field: season");
            if (round <= 0) throw new IllegalArgumentException("Missing or invalid Sleeper field: round");
            if (originalRosterId <= 0) throw new IllegalArgumentException("Missing or invalid Sleeper field: roster_id");
            if (previousOwnerRosterId <= 0) throw new IllegalArgumentException("Missing or invalid Sleeper field: previous_owner_id");
            if (ownerRosterId <= 0) throw new IllegalArgumentException("Missing or invalid Sleeper field: owner_id");
            picks.add(new SleeperTradedPick(season, round, originalRosterId, previousOwnerRosterId, ownerRosterId));
        }
        return List.copyOf(picks);
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
                    optionalText(node, "team"),
                    optionalNonNegativeInt(node, "age"),
                    optionalNonNegativeInt(node, "years_exp"),
                    stringList(node, "fantasy_positions")));
        });
        return Map.copyOf(players);
    }

    private static int positiveInt(JsonNode node, String field) {
        Integer value = optionalNonNegativeInt(node, field);
        return value == null || value <= 0 ? 0 : value;
    }

    private static int nonNegativeInt(JsonNode node, String field) {
        Integer value = optionalNonNegativeInt(node, field);
        return value == null ? 0 : value;
    }

    private static double sleeperPoints(JsonNode settings, String wholeField, String decimalField) {
        int whole = nonNegativeInt(settings, wholeField);
        int decimal = nonNegativeInt(settings, decimalField);
        if (decimal > 99) throw new IllegalArgumentException("Invalid Sleeper decimal points field: " + decimalField);
        return whole + decimal / 100.0;
    }

    private static Integer optionalNonNegativeInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        int parsed;
        if (value.isIntegralNumber()) {
            parsed = value.asInt();
        } else {
            String text = value.asText(null);
            if (text == null || text.isBlank()) return null;
            try {
                parsed = Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return parsed < 0 ? null : parsed;
    }

    private static Map<String, Double> numericMap(JsonNode node, String field) {
        JsonNode values = node.get(field);
        if (values == null || values.isNull()) return Map.of();
        if (!values.isObject()) {
            throw new IllegalArgumentException("Sleeper field must be an object: " + field);
        }
        Map<String, Double> result = new LinkedHashMap<>();
        values.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Sleeper scoring stat key must not be blank");
            }
            JsonNode value = entry.getValue();
            double parsed;
            if (value != null && value.isNumber()) {
                parsed = value.asDouble();
            } else {
                String text = value == null ? null : value.asText(null);
                if (text == null || text.isBlank()) {
                    throw new IllegalArgumentException("Sleeper scoring value must be numeric for " + key);
                }
                try {
                    parsed = Double.parseDouble(text.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Sleeper scoring value must be numeric for " + key);
                }
            }
            if (!Double.isFinite(parsed)) {
                throw new IllegalArgumentException("Sleeper scoring value must be finite for " + key);
            }
            result.put(key.trim(), parsed);
        });
        return Map.copyOf(result);
    }

    private static List<String> stringList(JsonNode node, String field) {
        JsonNode values = node.path(field);
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            String text = value.asText(null);
            if (text != null && !text.isBlank()) result.add(text);
        });
        return List.copyOf(result);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing Sleeper field: " + field);
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public record SleeperLeague(String id, String name, List<String> rosterPositions,
                                int season, int leagueType, int draftRounds,
                                Map<String, Double> scoringSettings) {
        public SleeperLeague(String id, String name) {
            this(id, name, List.of(), 0, 0, 0, Map.of());
        }
        public SleeperLeague(String id, String name, List<String> rosterPositions) {
            this(id, name, rosterPositions, 0, 0, 0, Map.of());
        }
        public SleeperLeague(String id, String name, List<String> rosterPositions,
                             int season, int leagueType, int draftRounds) {
            this(id, name, rosterPositions, season, leagueType, draftRounds, Map.of());
        }
        public SleeperLeague {
            rosterPositions = rosterPositions == null ? List.of() : List.copyOf(rosterPositions);
            scoringSettings = scoringSettings == null ? Map.of() : normalizeScoringSettings(scoringSettings);
            if (leagueType < 0 || leagueType > 2) {
                throw new IllegalArgumentException("leagueType must be 0 (redraft), 1 (keeper), or 2 (dynasty)");
            }
            if (draftRounds < 0) throw new IllegalArgumentException("draftRounds must not be negative");
        }

        private static Map<String, Double> normalizeScoringSettings(Map<String, Double> settings) {
            Map<String, Double> normalized = new LinkedHashMap<>();
            settings.forEach((key, value) -> {
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("scoring stat key must not be blank");
                }
                if (value == null || !Double.isFinite(value)) {
                    throw new IllegalArgumentException("scoring value must be finite for " + key);
                }
                normalized.put(key.trim(), value);
            });
            return Map.copyOf(normalized);
        }
    }
    public record SleeperUser(String id, String displayName, String teamName) {}
    public record SleeperRoster(int rosterId, String ownerId, List<String> playerIds,
                                List<String> starterIds, List<String> reserveIds, List<String> taxiIds,
                                int wins, int losses, int ties, double pointsFor, double pointsAgainst) {
        public SleeperRoster(int rosterId, String ownerId, List<String> playerIds,
                             List<String> starterIds, List<String> reserveIds, List<String> taxiIds) {
            this(rosterId, ownerId, playerIds, starterIds, reserveIds, taxiIds, 0, 0, 0, 0.0, 0.0);
        }
        public SleeperRoster {
            playerIds = playerIds == null ? List.of() : List.copyOf(playerIds);
            starterIds = starterIds == null ? List.of() : List.copyOf(starterIds);
            reserveIds = reserveIds == null ? List.of() : List.copyOf(reserveIds);
            taxiIds = taxiIds == null ? List.of() : List.copyOf(taxiIds);
            if (wins < 0 || losses < 0 || ties < 0) throw new IllegalArgumentException("Sleeper record counts must be non-negative");
            if (!Double.isFinite(pointsFor) || pointsFor < 0.0) throw new IllegalArgumentException("Sleeper pointsFor must be finite and non-negative");
            if (!Double.isFinite(pointsAgainst) || pointsAgainst < 0.0) throw new IllegalArgumentException("Sleeper pointsAgainst must be finite and non-negative");
        }
    }
    public record SleeperTradedPick(int season, int round, int originalRosterId,
                                    int previousOwnerRosterId, int ownerRosterId) {}
    public record SleeperPlayer(String id, String displayName, String position, String nflTeam,
                                Integer reportedAge, Integer yearsExperience, List<String> fantasyPositions) {
        public SleeperPlayer(String id, String displayName, String position, String nflTeam) {
            this(id, displayName, position, nflTeam, null, null, List.of());
        }
        public SleeperPlayer(String id, String displayName, String position, String nflTeam,
                             Integer reportedAge, Integer yearsExperience) {
            this(id, displayName, position, nflTeam, reportedAge, yearsExperience, List.of());
        }
        public SleeperPlayer {
            fantasyPositions = fantasyPositions == null ? List.of() : List.copyOf(fantasyPositions);
        }
    }
}
