package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.DraftPickValueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.domain.DraftPick;
import io.butler.bet.domain.DraftPickValue;
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
import java.util.TreeSet;

/**
 * Compares mixed player and future-draft-pick trade packages using persisted values only.
 * Missing values remain explicit and suppress the package difference; no subjective fairness
 * threshold or speculative draft-pick tier is applied.
 */
public final class TradeAssetAnalyzer {
    private final LeagueAnalyzer leagues;
    private final LeagueValueSourceResolver sources;
    private final PlayerRepository players;
    private final PlayerValueRepository playerValues;
    private final RosterRepository rosters;
    private final DraftPickRepository draftPicks;
    private final DraftPickValueRepository draftPickValues;

    public TradeAssetAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueAnalyzer(database);
        this.sources = new LeagueValueSourceResolver(database);
        this.players = new PlayerRepository(database);
        this.playerValues = new PlayerValueRepository(database);
        this.rosters = new RosterRepository(database);
        this.draftPicks = new DraftPickRepository(database);
        this.draftPickValues = new DraftPickValueRepository(database);
    }

    public TradeReport analyze(String leagueId, TradePackage sideA, TradePackage sideB) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        return analyzeResolved(normalizedLeagueId, sideA, sideB, sources.resolve(normalizedLeagueId));
    }

    public TradeReport analyze(String leagueId, TradePackage sideA, TradePackage sideB,
                               String source) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        leagues.analyze(normalizedLeagueId);
        return analyzeResolved(normalizedLeagueId, sideA, sideB, requireText(source, "source"));
    }

    private TradeReport analyzeResolved(String leagueId, TradePackage sideA, TradePackage sideB,
                                        String source) throws SQLException {
        LeagueAnalyzer.LeagueReport league = leagues.analyze(leagueId);
        NormalizedPackage normalizedA = normalizePackage(sideA, "sideA");
        NormalizedPackage normalizedB = normalizePackage(sideB, "sideB");
        rejectOverlap(normalizedA, normalizedB);
        validateSource(source);

        Map<String, TeamContext> playerTeams = rosterContext(league);
        Map<String, String> teamNames = teamNames(league);
        TradeSide analyzedA = analyzeSide(normalizedA, leagueId, source, playerTeams, teamNames);
        TradeSide analyzedB = analyzeSide(normalizedB, leagueId, source, playerTeams, teamNames);
        return new TradeReport(leagueId, source, analyzedA, analyzedB);
    }

    private void validateSource(String source) throws SQLException {
        Set<String> available = new TreeSet<>();
        available.addAll(playerValues.findSources());
        available.addAll(draftPickValues.findSources());
        if (!available.isEmpty() && !available.contains(source)) {
            throw new IllegalArgumentException("unknown trade value source: " + source
                + ". Available sources: " + String.join(", ", available));
        }
    }

    private TradeSide analyzeSide(NormalizedPackage tradePackage, String leagueId, String source,
                                  Map<String, TeamContext> playerTeams,
                                  Map<String, String> teamNames) throws SQLException {
        List<TradePlayer> tradePlayers = new ArrayList<>();
        List<TradeDraftPick> tradePicks = new ArrayList<>();
        double total = 0.0;
        int valuedPlayers = 0;
        int valuedPicks = 0;

        for (String playerId : tradePackage.playerIds()) {
            TeamContext team = playerTeams.get(playerId);
            if (team == null) {
                throw new IllegalArgumentException("player not rostered in league " + leagueId + ": " + playerId);
            }
            Player player = players.findById(playerId)
                .orElseThrow(() -> new IllegalStateException("rostered player not found: " + playerId));
            PlayerValue value = playerValues.findLatestByPlayerIdAndSource(playerId, source).orElse(null);
            if (value != null) {
                total += value.getValue();
                valuedPlayers++;
            }
            tradePlayers.add(new TradePlayer(
                player.getId(), player.getDisplayName(), player.getPosition(), player.getNflTeam(),
                team.teamId(), team.teamName(),
                value == null ? null : value.getValue(),
                value == null ? null : value.getAsOfDate()));
        }

        for (String draftPickId : tradePackage.draftPickIds()) {
            DraftPick pick = draftPicks.findById(draftPickId)
                .orElseThrow(() -> new IllegalArgumentException("draft pick not found: " + draftPickId));
            if (!pick.getLeagueId().equals(leagueId)) {
                throw new IllegalArgumentException("draft pick not in league " + leagueId + ": " + draftPickId);
            }
            String originalTeamName = teamNames.get(pick.getOriginalTeamId());
            String ownerTeamName = teamNames.get(pick.getOwnerTeamId());
            if (originalTeamName == null) {
                throw new IllegalStateException("draft pick original team not found in league: " + pick.getOriginalTeamId());
            }
            if (ownerTeamName == null) {
                throw new IllegalStateException("draft pick owner team not found in league: " + pick.getOwnerTeamId());
            }

            DraftPickValue value = draftPickValues
                .findLatestByDraftPickIdAndSource(draftPickId, source).orElse(null);
            if (value != null) {
                total += value.getValue();
                valuedPicks++;
            }
            tradePicks.add(new TradeDraftPick(
                pick.getId(), pick.getSeason(), pick.getRound(), genericPickLabel(pick.getSeason(), pick.getRound()),
                pick.getOriginalTeamId(), originalTeamName,
                pick.getOwnerTeamId(), ownerTeamName,
                pick.getPickNumber(),
                value == null ? null : value.getValue(),
                value == null ? null : value.getAsOfDate()));
        }

        return new TradeSide(
            List.copyOf(tradePlayers),
            List.copyOf(tradePicks),
            total,
            valuedPlayers,
            tradePackage.playerIds().size() - valuedPlayers,
            valuedPicks,
            tradePackage.draftPickIds().size() - valuedPicks);
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

    private static Map<String, String> teamNames(LeagueAnalyzer.LeagueReport league) {
        Map<String, String> result = new HashMap<>();
        for (LeagueAnalyzer.TeamReport team : league.teams()) result.put(team.teamId(), team.teamName());
        return Map.copyOf(result);
    }

    private static NormalizedPackage normalizePackage(TradePackage tradePackage, String field) {
        Objects.requireNonNull(tradePackage, field + " must not be null");
        List<String> playerIds = normalizeIds(tradePackage.playerIds(), field + ".playerIds", "player");
        List<String> draftPickIds = normalizeIds(tradePackage.draftPickIds(), field + ".draftPickIds", "draft pick");
        if (playerIds.isEmpty() && draftPickIds.isEmpty()) {
            throw new IllegalArgumentException(field + " must contain at least one player or draft pick");
        }
        return new NormalizedPackage(playerIds, draftPickIds);
    }

    private static List<String> normalizeIds(List<String> ids, String field, String assetName) {
        Objects.requireNonNull(ids, field + " must not be null");
        Set<String> normalized = new LinkedHashSet<>();
        for (String id : ids) {
            String value = requireText(id, field + " entry");
            if (!normalized.add(value)) {
                throw new IllegalArgumentException(field + " contains duplicate " + assetName + ": " + value);
            }
        }
        return List.copyOf(normalized);
    }

    private static void rejectOverlap(NormalizedPackage sideA, NormalizedPackage sideB) {
        Set<String> players = new HashSet<>(sideA.playerIds());
        for (String playerId : sideB.playerIds()) {
            if (players.contains(playerId)) {
                throw new IllegalArgumentException("player appears on both trade sides: " + playerId);
            }
        }
        Set<String> picks = new HashSet<>(sideA.draftPickIds());
        for (String pickId : sideB.draftPickIds()) {
            if (picks.contains(pickId)) {
                throw new IllegalArgumentException("draft pick appears on both trade sides: " + pickId);
            }
        }
    }

    private static String genericPickLabel(int season, int round) {
        return season + " " + switch (round) {
            case 1 -> "1st";
            case 2 -> "2nd";
            case 3 -> "3rd";
            default -> round + "th";
        };
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record TeamContext(String teamId, String teamName) {}
    private record NormalizedPackage(List<String> playerIds, List<String> draftPickIds) {}

    public record TradePackage(List<String> playerIds, List<String> draftPickIds) {
        public TradePackage {
            playerIds = playerIds == null ? List.of() : List.copyOf(playerIds);
            draftPickIds = draftPickIds == null ? List.of() : List.copyOf(draftPickIds);
        }

        public static TradePackage players(List<String> playerIds) {
            return new TradePackage(playerIds, List.of());
        }

        public static TradePackage picks(List<String> draftPickIds) {
            return new TradePackage(List.of(), draftPickIds);
        }
    }

    public record TradeReport(String leagueId, String source, TradeSide sideA, TradeSide sideB) {
        public boolean complete() { return sideA.complete() && sideB.complete(); }
        public Double valueDifference() {
            return complete() ? sideA.totalValue() - sideB.totalValue() : null;
        }
        public int totalAssets() { return sideA.totalAssets() + sideB.totalAssets(); }
        public int valuedAssets() { return sideA.valuedAssets() + sideB.valuedAssets(); }
        public int missingAssets() { return sideA.missingAssets() + sideB.missingAssets(); }
        public double coveragePercent() {
            return totalAssets() == 0 ? 0.0 : valuedAssets() * 100.0 / totalAssets();
        }
    }

    public record TradeSide(List<TradePlayer> players, List<TradeDraftPick> draftPicks,
                            double totalValue, int valuedPlayers, int missingPlayers,
                            int valuedDraftPicks, int missingDraftPicks) {
        public int totalAssets() { return players.size() + draftPicks.size(); }
        public int valuedAssets() { return valuedPlayers + valuedDraftPicks; }
        public int missingAssets() { return missingPlayers + missingDraftPicks; }
        public boolean complete() { return missingAssets() == 0; }
        public double coveragePercent() {
            return totalAssets() == 0 ? 0.0 : valuedAssets() * 100.0 / totalAssets();
        }
    }

    public record TradePlayer(String playerId, String playerName, String position, String nflTeam,
                              String teamId, String teamName, Double value, LocalDate asOfDate) {
        public boolean valued() { return value != null; }
    }

    public record TradeDraftPick(String draftPickId, int season, int round, String label,
                                 String originalTeamId, String originalTeamName,
                                 String ownerTeamId, String ownerTeamName, Integer pickNumber,
                                 Double value, LocalDate asOfDate) {
        public boolean valued() { return value != null; }
    }
}
