package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Describes player counts and usable value distribution within each fantasy position. This is
 * neutral depth context only; no minimum depth target or positional strength label is inferred.
 */
public final class LeaguePositionalDepthAnalyzer {
    private final LeagueAnalyzer leagues;
    private final LeagueValueSourceResolver sources;
    private final RosterRepository rosters;
    private final PlayerRepository players;
    private final PlayerValueRepository values;

    public LeaguePositionalDepthAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueAnalyzer(database);
        this.sources = new LeagueValueSourceResolver(database);
        this.rosters = new RosterRepository(database);
        this.players = new PlayerRepository(database);
        this.values = new PlayerValueRepository(database);
    }

    public DepthReport analyze(String leagueId) throws SQLException {
        return analyze(leagueId, sources.resolve(leagueId), null);
    }

    public DepthReport analyze(String leagueId, String source) throws SQLException {
        return analyze(leagueId, source, null);
    }

    public DepthReport analyze(String leagueId, LocalDate minimumAsOfDate) throws SQLException {
        return analyze(leagueId, sources.resolve(leagueId), minimumAsOfDate);
    }

    public DepthReport analyze(String leagueId, String source, LocalDate minimumAsOfDate) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedSource = requireText(source, "source");
        var league = leagues.analyze(normalizedLeagueId);
        List<TeamDepth> teams = new ArrayList<>();

        for (var team : league.teams()) {
            Map<String, MutablePosition> positions = new TreeMap<>();
            for (var roster : rosters.findByTeamId(team.teamId())) {
                var player = players.findById(roster.getPlayerId())
                    .orElseThrow(() -> new IllegalArgumentException("player not found: " + roster.getPlayerId()));
                String position = normalizePosition(player.getPosition());
                MutablePosition summary = positions.computeIfAbsent(position, ignored -> new MutablePosition());
                summary.totalPlayers++;

                var value = values.findLatestByPlayerIdAndSource(player.getId(), normalizedSource).orElse(null);
                if (value == null) {
                    summary.missingPlayers++;
                    continue;
                }
                if (minimumAsOfDate != null && value.getAsOfDate().isBefore(minimumAsOfDate)) {
                    summary.stalePlayers++;
                    continue;
                }
                summary.players.add(new PlayerDepthValue(player.getId(), player.getDisplayName(), position,
                    roster.getSlot(), value.getValue(), value.getAsOfDate()));
            }

            Map<String, PositionDepth> frozen = new LinkedHashMap<>();
            positions.forEach((position, mutable) -> {
                mutable.players.sort(Comparator.comparingDouble(PlayerDepthValue::value).reversed()
                    .thenComparing(PlayerDepthValue::playerName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(PlayerDepthValue::playerId));
                frozen.put(position, new PositionDepth(position, mutable.totalPlayers, mutable.players.size(),
                    mutable.stalePlayers, mutable.missingPlayers, List.copyOf(mutable.players)));
            });
            teams.add(new TeamDepth(team.teamId(), team.teamName(), Map.copyOf(frozen)));
        }

        teams.sort(Comparator.comparing(TeamDepth::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamDepth::teamId));
        return new DepthReport(normalizedLeagueId, normalizedSource, minimumAsOfDate, List.copyOf(teams));
    }

    private static String normalizePosition(String position) {
        if (position == null || position.isBlank()) return "UNKNOWN";
        return position.trim().toUpperCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static final class MutablePosition {
        int totalPlayers;
        int stalePlayers;
        int missingPlayers;
        final List<PlayerDepthValue> players = new ArrayList<>();
    }

    public record PlayerDepthValue(String playerId, String playerName, String position, String rosterSlot,
                                   double value, LocalDate asOfDate) {}

    public record PositionDepth(String position, int totalPlayers, int valuedPlayers,
                                int stalePlayers, int missingPlayers, List<PlayerDepthValue> players) {
        public PositionDepth {
            players = List.copyOf(Objects.requireNonNull(players, "players must not be null"));
        }

        public double totalUsableValue() { return players.stream().mapToDouble(PlayerDepthValue::value).sum(); }
        public double topOneValue() { return topValue(1); }
        public double topTwoValue() { return topValue(2); }
        public double topThreeValue() { return topValue(3); }
        public double coveragePercent() { return totalPlayers == 0 ? 0.0 : valuedPlayers * 100.0 / totalPlayers; }
        public double topOneSharePercent() { return share(topOneValue()); }
        public double topTwoSharePercent() { return share(topTwoValue()); }
        public double topThreeSharePercent() { return share(topThreeValue()); }
        public List<PlayerDepthValue> topPlayers(int limit) {
            if (limit < 0) throw new IllegalArgumentException("limit must not be negative");
            return players.subList(0, Math.min(limit, players.size()));
        }
        private double topValue(int count) { return topPlayers(count).stream().mapToDouble(PlayerDepthValue::value).sum(); }
        private double share(double value) { return totalUsableValue() <= 0.0 ? 0.0 : value * 100.0 / totalUsableValue(); }
    }

    public record TeamDepth(String teamId, String teamName, Map<String, PositionDepth> positions) {
        public TeamDepth {
            positions = Map.copyOf(Objects.requireNonNull(positions, "positions must not be null"));
        }
    }

    public record DepthReport(String leagueId, String source, LocalDate minimumAsOfDate, List<TeamDepth> teams) {
        public DepthReport {
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }
    }
}
