package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.data.RosterRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Describes season-production evidence coverage without grading the production itself. */
public final class LeagueProductionCoverageAnalyzer {
    private final LeagueAnalyzer leagues;
    private final RosterRepository rosters;
    private final PlayerRepository players;
    private final PlayerSeasonProductionRepository production;

    public LeagueProductionCoverageAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueAnalyzer(database);
        this.rosters = new RosterRepository(database);
        this.players = new PlayerRepository(database);
        this.production = new PlayerSeasonProductionRepository(database);
    }

    public CoverageReport analyze(String leagueId, int season) throws SQLException {
        return analyze(leagueId, season, NflversePlayerSeasonProductionImporter.SOURCE);
    }

    public CoverageReport analyze(String leagueId, int season, String source) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedSource = requireText(source, "source");
        if (season <= 0) throw new IllegalArgumentException("season must be positive");

        var league = leagues.analyze(normalizedLeagueId);
        List<TeamCoverage> teams = new ArrayList<>();
        for (var team : league.teams()) {
            Map<String, MutablePosition> positions = new TreeMap<>();
            List<MissingPlayer> missing = new ArrayList<>();
            int totalPlayers = 0;
            int coveredPlayers = 0;
            int gamesRecorded = 0;

            for (var roster : rosters.findByTeamId(team.teamId())) {
                var player = players.findById(roster.getPlayerId())
                    .orElseThrow(() -> new IllegalArgumentException("player not found: " + roster.getPlayerId()));
                String position = normalizePosition(player.getPosition());
                MutablePosition positionCoverage = positions.computeIfAbsent(position, ignored -> new MutablePosition());
                totalPlayers++;
                positionCoverage.totalPlayers++;

                var snapshot = production.findLatest(player.getId(), season, normalizedSource).orElse(null);
                if (snapshot == null) {
                    missing.add(new MissingPlayer(player.getId(), player.getDisplayName(), position, roster.getSlot()));
                    continue;
                }
                coveredPlayers++;
                positionCoverage.coveredPlayers++;
                gamesRecorded += snapshot.gamesPlayed();
                positionCoverage.gamesRecorded += snapshot.gamesPlayed();
            }

            Map<String, PositionCoverage> frozen = new LinkedHashMap<>();
            positions.forEach((position, value) -> frozen.put(position,
                new PositionCoverage(position, value.coveredPlayers, value.totalPlayers, value.gamesRecorded)));
            missing.sort(Comparator.comparing(MissingPlayer::playerName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(MissingPlayer::playerId));
            teams.add(new TeamCoverage(team.teamId(), team.teamName(), coveredPlayers, totalPlayers, gamesRecorded,
                Map.copyOf(frozen), List.copyOf(missing)));
        }

        teams.sort(Comparator.comparing(TeamCoverage::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamCoverage::teamId));
        return new CoverageReport(normalizedLeagueId, season, normalizedSource, List.copyOf(teams));
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
        int coveredPlayers;
        int totalPlayers;
        int gamesRecorded;
    }

    public record CoverageReport(String leagueId, int season, String source, List<TeamCoverage> teams) {
        public CoverageReport {
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }
        public int coveredPlayers() { return teams.stream().mapToInt(TeamCoverage::coveredPlayers).sum(); }
        public int totalPlayers() { return teams.stream().mapToInt(TeamCoverage::totalPlayers).sum(); }
        public int missingPlayers() { return totalPlayers() - coveredPlayers(); }
        public double coveragePercent() { return totalPlayers() == 0 ? 0.0 : coveredPlayers() * 100.0 / totalPlayers(); }
        public boolean complete() { return totalPlayers() > 0 && coveredPlayers() == totalPlayers(); }
    }

    public record TeamCoverage(String teamId, String teamName, int coveredPlayers, int totalPlayers,
                               int gamesRecorded, Map<String, PositionCoverage> positions,
                               List<MissingPlayer> missing) {
        public TeamCoverage {
            positions = Map.copyOf(Objects.requireNonNull(positions, "positions must not be null"));
            missing = List.copyOf(Objects.requireNonNull(missing, "missing must not be null"));
        }
        public int missingPlayers() { return totalPlayers - coveredPlayers; }
        public double coveragePercent() { return totalPlayers == 0 ? 0.0 : coveredPlayers * 100.0 / totalPlayers; }
        public boolean complete() { return totalPlayers > 0 && coveredPlayers == totalPlayers; }
    }

    public record PositionCoverage(String position, int coveredPlayers, int totalPlayers, int gamesRecorded) {
        public int missingPlayers() { return totalPlayers - coveredPlayers; }
        public double coveragePercent() { return totalPlayers == 0 ? 0.0 : coveredPlayers * 100.0 / totalPlayers; }
    }

    public record MissingPlayer(String playerId, String playerName, String position, String rosterSlot) {}
}
