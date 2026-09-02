package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerValue;
import io.butler.bet.domain.Roster;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compares two player-only trade packages using persisted player values.
 * The analyzer reports incomplete coverage instead of inventing values and does not apply a
 * subjective winner/fairness threshold.
 */
public final class TradeValueAnalyzer {
    private final LeagueAnalyzer leagues;
    private final LeagueValueSourceResolver sources;
    private final PlayerRepository players;
    private final PlayerValueRepository values;
    private final RosterRepository rosters;

    public TradeValueAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueAnalyzer(database);
        this.sources = new LeagueValueSourceResolver(database);
        this.players = new PlayerRepository(database);
        this.values = new PlayerValueRepository(database);
        this.rosters = new RosterRepository(database);
    }

    public TradeReport analyze(String leagueId, List<String> sideAPlayerIds,
                               List<String> sideBPlayerIds) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        return analyzeResolved(normalizedLeagueId, sideAPlayerIds, sideBPlayerIds,
            sources.resolve(normalizedLeagueId));
    }

    public TradeReport analyze(String leagueId, List<String> sideAPlayerIds,
                               List<String> sideBPlayerIds, String source) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        leagues.analyze(normalizedLeagueId);
        String normalizedSource = requireText(source, "source");
        return analyzeResolved(normalizedLeagueId, sideAPlayerIds, sideBPlayerIds, normalizedSource);
    }

    private TradeReport analyzeResolved(String leagueId, List<String> sideAPlayerIds,
                                        List<String> sideBPlayerIds, String source) throws SQLException {
        var league = leagues.analyze(leagueId);
        List<String> sideAIds = normalizePackage(sideAPlayerIds, "sideAPlayerIds");
        List<String> sideBIds = normalizePackage(sideBPlayerIds, "sideBPlayerIds");
        rejectOverlap(sideAIds, sideBIds);

        List<String> availableSources = values.findSources();
        if (!availableSources.isEmpty() && !availableSources.contains(source)) {
            throw new IllegalArgumentException("unknown player value source: " + source
                + ". Available sources: " + String.join(", ", availableSources));
        }

        Map<String, TeamContext> leagueRoster = rosterContext(league);
        TradeSide sideA = analyzeSide(sideAIds, leagueId, source, leagueRoster);
        TradeSide sideB = analyzeSide(sideBIds, leagueId, source, leagueRoster);
        return new TradeReport(leagueId, source, sideA, sideB);
    }

    private TradeSide analyzeSide(List<String> playerIds, String leagueId, String source,
                                  Map<String, TeamContext> leagueRoster) throws SQLException {
        List<TradePlayer> tradePlayers = new ArrayList<>();
        double total = 0.0;
        int valued = 0;

        for (String playerId : playerIds) {
            TeamContext team = leagueRoster.get(playerId);
            if (team == null) {
                throw new IllegalArgumentException("player not rostered in league " + leagueId + ": " + playerId);
            }
            Player player = players.findById(playerId)
                .orElseThrow(() -> new IllegalStateException("rostered player not found: " + playerId));
            PlayerValue value = values.findLatestByPlayerIdAndSource(playerId, source).orElse(null);
            if (value != null) {
                total += value.getValue();
                valued++;
            }
            tradePlayers.add(new TradePlayer(
                player.getId(),
                player.getDisplayName(),
                player.getPosition(),
                player.getNflTeam(),
                team.teamId(),
                team.teamName(),
                value == null ? null : value.getValue(),
                value == null ? null : value.getAsOfDate()));
        }

        return new TradeSide(List.copyOf(tradePlayers), total, valued, playerIds.size() - valued);
    }

    private Map<String, TeamContext> rosterContext(LeagueAnalyzer.LeagueReport league) throws SQLException {
        Map<String, TeamContext> context = new HashMap<>();
        for (LeagueAnalyzer.TeamReport team : league.teams()) {
            for (Roster roster : rosters.findByTeamId(team.teamId())) {
                TeamContext previous = context.putIfAbsent(roster.getPlayerId(),
                    new TeamContext(team.teamId(), team.teamName()));
                if (previous != null && !previous.teamId().equals(team.teamId())) {
                    throw new IllegalStateException("player rostered on multiple teams in league: " + roster.getPlayerId());
                }
            }
        }
        return Map.copyOf(context);
    }

    private static List<String> normalizePackage(List<String> playerIds, String field) {
        Objects.requireNonNull(playerIds, field + " must not be null");
        if (playerIds.isEmpty()) throw new IllegalArgumentException(field + " must not be empty");
        Set<String> normalized = new LinkedHashSet<>();
        for (String playerId : playerIds) {
            String value = requireText(playerId, field + " entry");
            if (!normalized.add(value)) {
                throw new IllegalArgumentException(field + " contains duplicate player: " + value);
            }
        }
        return List.copyOf(normalized);
    }

    private static void rejectOverlap(List<String> sideA, List<String> sideB) {
        Set<String> seen = new HashSet<>(sideA);
        for (String playerId : sideB) {
            if (seen.contains(playerId)) {
                throw new IllegalArgumentException("player appears on both trade sides: " + playerId);
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record TeamContext(String teamId, String teamName) {}

    public record TradeReport(String leagueId, String source, TradeSide sideA, TradeSide sideB) {
        public boolean complete() { return sideA.complete() && sideB.complete(); }
        public Double valueDifference() {
            return complete() ? sideA.totalValue() - sideB.totalValue() : null;
        }
        public int totalPlayers() { return sideA.players().size() + sideB.players().size(); }
        public int valuedPlayers() { return sideA.valuedPlayers() + sideB.valuedPlayers(); }
        public int missingPlayers() { return sideA.missingPlayers() + sideB.missingPlayers(); }
        public double coveragePercent() {
            return totalPlayers() == 0 ? 0.0 : valuedPlayers() * 100.0 / totalPlayers();
        }
    }

    public record TradeSide(List<TradePlayer> players, double totalValue,
                            int valuedPlayers, int missingPlayers) {
        public boolean complete() { return missingPlayers == 0; }
        public double coveragePercent() {
            return players.isEmpty() ? 0.0 : valuedPlayers * 100.0 / players.size();
        }
    }

    public record TradePlayer(String playerId, String playerName, String position, String nflTeam,
                              String teamId, String teamName, Double value, LocalDate asOfDate) {
        public boolean valued() { return value != null; }
    }
}
