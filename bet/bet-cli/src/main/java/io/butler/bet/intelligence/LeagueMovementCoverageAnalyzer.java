package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.domain.PlayerValue;
import io.butler.bet.domain.Roster;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class LeagueMovementCoverageAnalyzer {
    private final LeagueAnalyzer leagues;
    private final RosterRepository rosters;
    private final PlayerRepository players;
    private final PlayerValueRepository values;
    private final SourceValueWindowResolver windows;

    public LeagueMovementCoverageAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueAnalyzer(database);
        this.rosters = new RosterRepository(database);
        this.players = new PlayerRepository(database);
        this.values = new PlayerValueRepository(database);
        this.windows = new SourceValueWindowResolver(database);
    }

    public CoverageReport analyze(String leagueId, String source) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedSource = requireText(source, "source");
        var league = leagues.analyze(normalizedLeagueId);
        int totalPlayers = league.teams().stream().mapToInt(LeagueAnalyzer.TeamReport::rosterSize).sum();
        var window = windows.latestWindow(normalizedSource);
        if (window.isEmpty()) {
            return new CoverageReport(normalizedLeagueId, normalizedSource, null, null,
                totalPlayers, 0, totalPlayers, List.of());
        }

        LocalDate previousDate = window.orElseThrow().previousDate();
        LocalDate latestDate = window.orElseThrow().latestDate();
        List<MissingSnapshot> missing = new ArrayList<>();
        int comparablePlayers = 0;

        for (var team : league.teams()) {
            for (Roster roster : rosters.findByTeamId(team.teamId())) {
                var player = players.findById(roster.getPlayerId()).orElseThrow(
                    () -> new IllegalStateException("rostered player not found: " + roster.getPlayerId()));
                List<PlayerValue> history = values.findByPlayerIdAndSource(player.getId(), normalizedSource);
                boolean missingPrevious = !hasDate(history, previousDate);
                boolean missingLatest = !hasDate(history, latestDate);
                if (!missingPrevious && !missingLatest) {
                    comparablePlayers++;
                    continue;
                }
                missing.add(new MissingSnapshot(
                    team.teamId(), team.teamName(), player.getId(), player.getDisplayName(),
                    player.getPosition(), player.getNflTeam(), missingPrevious, missingLatest));
            }
        }

        return new CoverageReport(normalizedLeagueId, normalizedSource, previousDate, latestDate,
            totalPlayers, comparablePlayers, missing.size(), List.copyOf(missing));
    }

    private static boolean hasDate(List<PlayerValue> history, LocalDate date) {
        for (PlayerValue value : history) {
            if (value.getAsOfDate().equals(date)) return true;
        }
        return false;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record CoverageReport(String leagueId, String source,
                                 LocalDate previousDate, LocalDate latestDate,
                                 int totalPlayers, int comparablePlayers, int missingPlayers,
                                 List<MissingSnapshot> missingSnapshots) {
        public double coveragePercent() {
            return totalPlayers == 0 ? 0.0 : (comparablePlayers * 100.0) / totalPlayers;
        }
    }

    public record MissingSnapshot(String teamId, String teamName,
                                  String playerId, String playerName, String position, String nflTeam,
                                  boolean missingPrevious, boolean missingLatest) {}
}
