package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.PlayerSeasonProduction;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Neutral raw production context by team and position. This analyzer deliberately does not convert
 * production into fantasy points, grades, rankings, or strategy labels.
 */
public final class LeagueProductionContextAnalyzer {
    public static final String DEFAULT_SOURCE = NflversePlayerSeasonProductionImporter.SOURCE;

    private final LeagueRepository leagues;
    private final TeamRepository teams;
    private final RosterRepository rosters;
    private final PlayerRepository players;
    private final PlayerSeasonProductionRepository production;

    public LeagueProductionContextAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueRepository(database);
        this.teams = new TeamRepository(database);
        this.rosters = new RosterRepository(database);
        this.players = new PlayerRepository(database);
        this.production = new PlayerSeasonProductionRepository(database);
    }

    public ProductionContextReport analyze(String leagueId) throws SQLException {
        return analyze(leagueId, resolveSeason(leagueId), DEFAULT_SOURCE);
    }

    public ProductionContextReport analyze(String leagueId, int season) throws SQLException {
        return analyze(leagueId, season, DEFAULT_SOURCE);
    }

    public ProductionContextReport analyze(String leagueId, int season, String source) throws SQLException {
        requireText(leagueId, "leagueId");
        requireText(source, "source");
        if (season <= 0) throw new IllegalArgumentException("season must be positive");
        if (leagues.findById(leagueId).isEmpty()) throw new IllegalArgumentException("league not found: " + leagueId);

        List<TeamProductionContext> result = new ArrayList<>();
        for (var team : teams.findByLeagueId(leagueId)) {
            Map<String, MutablePosition> byPosition = new LinkedHashMap<>();
            List<MissingProduction> missing = new ArrayList<>();
            int totalPlayers = 0;
            int coveredPlayers = 0;
            LocalDate earliestAsOf = null;
            LocalDate latestAsOf = null;

            for (var roster : rosters.findByTeamId(team.getId())) {
                var player = players.findById(roster.getPlayerId())
                    .orElseThrow(() -> new IllegalStateException("roster references missing player: " + roster.getPlayerId()));
                totalPlayers++;
                String position = normalizePosition(player.getPosition());
                MutablePosition aggregate = byPosition.computeIfAbsent(position, MutablePosition::new);
                aggregate.totalPlayers++;

                var snapshot = production.findLatest(player.getId(), season, source);
                if (snapshot.isEmpty()) {
                    missing.add(new MissingProduction(player.getId(), player.getDisplayName(), position));
                    continue;
                }

                PlayerSeasonProduction value = snapshot.get();
                coveredPlayers++;
                aggregate.add(value);
                if (earliestAsOf == null || value.asOfDate().isBefore(earliestAsOf)) earliestAsOf = value.asOfDate();
                if (latestAsOf == null || value.asOfDate().isAfter(latestAsOf)) latestAsOf = value.asOfDate();
            }

            Map<String, PositionProductionContext> positions = new LinkedHashMap<>();
            byPosition.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                positions.put(entry.getKey(), entry.getValue().freeze()));
            missing.sort(Comparator.comparing(MissingProduction::playerName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(MissingProduction::playerId));

            result.add(new TeamProductionContext(team.getId(), team.getName(), totalPlayers, coveredPlayers,
                Map.copyOf(positions), List.copyOf(missing), earliestAsOf, latestAsOf));
        }
        result.sort(Comparator.comparing(TeamProductionContext::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamProductionContext::teamId));
        return new ProductionContextReport(leagueId, season, source, List.copyOf(result));
    }

    private int resolveSeason(String leagueId) throws SQLException {
        requireText(leagueId, "leagueId");
        var league = leagues.findById(leagueId)
            .orElseThrow(() -> new IllegalArgumentException("league not found: " + leagueId));
        if (league.getSeason() == null) {
            throw new IllegalStateException("league season is unavailable; supply an explicit season");
        }
        return league.getSeason();
    }

    private static String normalizePosition(String position) {
        return position == null || position.isBlank() ? "UNKNOWN" : position.trim().toUpperCase(Locale.ROOT);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    public record ProductionContextReport(String leagueId, int season, String source,
                                          List<TeamProductionContext> teams) {
        public ProductionContextReport {
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }
        public int totalPlayers() { return teams.stream().mapToInt(TeamProductionContext::totalPlayers).sum(); }
        public int coveredPlayers() { return teams.stream().mapToInt(TeamProductionContext::coveredPlayers).sum(); }
        public double coveragePercent() { return percent(coveredPlayers(), totalPlayers()); }
    }

    public record TeamProductionContext(String teamId, String teamName, int totalPlayers, int coveredPlayers,
                                        Map<String, PositionProductionContext> positions,
                                        List<MissingProduction> missingPlayers,
                                        LocalDate earliestAsOf, LocalDate latestAsOf) {
        public TeamProductionContext {
            positions = Map.copyOf(Objects.requireNonNull(positions, "positions must not be null"));
            missingPlayers = List.copyOf(Objects.requireNonNull(missingPlayers, "missingPlayers must not be null"));
        }
        public double coveragePercent() { return percent(coveredPlayers, totalPlayers); }
    }

    public record PositionProductionContext(String position, int totalPlayers, int coveredPlayers,
                                            int playerGames, int passingYards, int passingTouchdowns,
                                            int interceptions, int rushingYards, int rushingTouchdowns,
                                            int receptions, int receivingYards, int receivingTouchdowns,
                                            int fumblesLost) {
        public double coveragePercent() { return percent(coveredPlayers, totalPlayers); }
    }

    public record MissingProduction(String playerId, String playerName, String position) {}

    private static final class MutablePosition {
        private final String position;
        private int totalPlayers;
        private int coveredPlayers;
        private int playerGames;
        private int passingYards;
        private int passingTouchdowns;
        private int interceptions;
        private int rushingYards;
        private int rushingTouchdowns;
        private int receptions;
        private int receivingYards;
        private int receivingTouchdowns;
        private int fumblesLost;

        private MutablePosition(String position) { this.position = position; }

        private void add(PlayerSeasonProduction value) {
            coveredPlayers++;
            playerGames += value.gamesPlayed();
            passingYards += value.passingYards();
            passingTouchdowns += value.passingTouchdowns();
            interceptions += value.interceptions();
            rushingYards += value.rushingYards();
            rushingTouchdowns += value.rushingTouchdowns();
            receptions += value.receptions();
            receivingYards += value.receivingYards();
            receivingTouchdowns += value.receivingTouchdowns();
            fumblesLost += value.fumblesLost();
        }

        private PositionProductionContext freeze() {
            return new PositionProductionContext(position, totalPlayers, coveredPlayers, playerGames,
                passingYards, passingTouchdowns, interceptions, rushingYards, rushingTouchdowns,
                receptions, receivingYards, receivingTouchdowns, fumblesLost);
        }
    }

    private static double percent(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }
}
