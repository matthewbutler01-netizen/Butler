package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Provides neutral position-by-position player-value context for every team in a league.
 * No roster strategy, contender/rebuild label, or preferred positional mix is inferred.
 */
public final class LeaguePositionValueAnalyzer {
    private final LeagueAnalyzer leagues;
    private final LeagueValueSourceResolver sources;
    private final RosterRepository rosters;
    private final PlayerRepository players;
    private final PlayerValueRepository values;

    public LeaguePositionValueAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueAnalyzer(database);
        this.sources = new LeagueValueSourceResolver(database);
        this.rosters = new RosterRepository(database);
        this.players = new PlayerRepository(database);
        this.values = new PlayerValueRepository(database);
    }

    public PositionContextReport analyze(String leagueId) throws SQLException {
        return analyze(leagueId, sources.resolve(leagueId), null);
    }

    public PositionContextReport analyze(String leagueId, String sourceOverride) throws SQLException {
        return analyze(leagueId, sourceOverride, null);
    }

    public PositionContextReport analyze(String leagueId, LocalDate minimumAsOfDate) throws SQLException {
        return analyze(leagueId, sources.resolve(leagueId), minimumAsOfDate);
    }

    public PositionContextReport analyze(String leagueId, String sourceOverride,
                                         LocalDate minimumAsOfDate) throws SQLException {
        String source = requireText(sourceOverride, "source");
        var league = leagues.analyze(requireText(leagueId, "leagueId"));
        List<TeamPositionContext> teams = new ArrayList<>();
        Map<String, MutablePosition> leaguePositions = new TreeMap<>();

        for (var team : league.teams()) {
            Map<String, MutablePosition> teamPositions = new TreeMap<>();
            for (var roster : rosters.findByTeamId(team.teamId())) {
                var player = players.findById(roster.getPlayerId())
                    .orElseThrow(() -> new IllegalArgumentException("player not found: " + roster.getPlayerId()));
                String position = normalizePosition(player.getPosition());
                MutablePosition teamPosition = teamPositions.computeIfAbsent(position, ignored -> new MutablePosition());
                MutablePosition leaguePosition = leaguePositions.computeIfAbsent(position, ignored -> new MutablePosition());
                teamPosition.totalPlayers++;
                leaguePosition.totalPlayers++;

                var value = values.findLatestByPlayerIdAndSource(player.getId(), source).orElse(null);
                if (value == null) {
                    teamPosition.missingPlayers++;
                    leaguePosition.missingPlayers++;
                    continue;
                }
                if (minimumAsOfDate != null && value.getAsOfDate().isBefore(minimumAsOfDate)) {
                    teamPosition.stalePlayers++;
                    leaguePosition.stalePlayers++;
                    continue;
                }
                teamPosition.valuedPlayers++;
                teamPosition.value += value.getValue();
                leaguePosition.valuedPlayers++;
                leaguePosition.value += value.getValue();
            }
            teams.add(new TeamPositionContext(team.teamId(), team.teamName(), freeze(teamPositions)));
        }

        teams.sort(java.util.Comparator.comparing(TeamPositionContext::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamPositionContext::teamId));
        return new PositionContextReport(league.leagueId(), source, minimumAsOfDate,
            freeze(leaguePositions), List.copyOf(teams));
    }

    private static Map<String, PositionValue> freeze(Map<String, MutablePosition> source) {
        Map<String, PositionValue> result = new LinkedHashMap<>();
        source.forEach((position, value) -> result.put(position,
            new PositionValue(position, value.value, value.valuedPlayers, value.stalePlayers,
                value.missingPlayers, value.totalPlayers)));
        return Map.copyOf(result);
    }

    private static String normalizePosition(String position) {
        if (position == null || position.isBlank()) return "UNKNOWN";
        return position.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static final class MutablePosition {
        double value;
        int valuedPlayers;
        int stalePlayers;
        int missingPlayers;
        int totalPlayers;
    }

    public record PositionContextReport(String leagueId, String source, LocalDate minimumAsOfDate,
                                        Map<String, PositionValue> leaguePositions,
                                        List<TeamPositionContext> teams) {
        public PositionContextReport {
            leaguePositions = Map.copyOf(Objects.requireNonNull(leaguePositions, "leaguePositions must not be null"));
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }

        public int totalPlayers() {
            return leaguePositions.values().stream().mapToInt(PositionValue::totalPlayers).sum();
        }

        public int valuedPlayers() {
            return leaguePositions.values().stream().mapToInt(PositionValue::valuedPlayers).sum();
        }

        public int stalePlayers() {
            return leaguePositions.values().stream().mapToInt(PositionValue::stalePlayers).sum();
        }

        public int missingPlayers() {
            return leaguePositions.values().stream().mapToInt(PositionValue::missingPlayers).sum();
        }

        public double coveragePercent() {
            return totalPlayers() == 0 ? 0.0 : valuedPlayers() * 100.0 / totalPlayers();
        }
    }

    public record TeamPositionContext(String teamId, String teamName,
                                      Map<String, PositionValue> positions) {
        public TeamPositionContext {
            positions = Map.copyOf(Objects.requireNonNull(positions, "positions must not be null"));
        }

        public double totalPlayerValue() {
            return positions.values().stream().mapToDouble(PositionValue::value).sum();
        }

        public int totalPlayers() {
            return positions.values().stream().mapToInt(PositionValue::totalPlayers).sum();
        }

        public int valuedPlayers() {
            return positions.values().stream().mapToInt(PositionValue::valuedPlayers).sum();
        }

        public double coveragePercent() {
            return totalPlayers() == 0 ? 0.0 : valuedPlayers() * 100.0 / totalPlayers();
        }
    }

    public record PositionValue(String position, double value, int valuedPlayers,
                                int stalePlayers, int missingPlayers, int totalPlayers) {
        public double coveragePercent() {
            return totalPlayers == 0 ? 0.0 : valuedPlayers * 100.0 / totalPlayers;
        }
    }
}
