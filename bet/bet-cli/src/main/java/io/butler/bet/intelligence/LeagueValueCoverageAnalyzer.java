package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.domain.PlayerValue;
import io.butler.bet.domain.Roster;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class LeagueValueCoverageAnalyzer {
    private final LeagueAnalyzer leagueAnalyzer;
    private final RosterRepository rosters;
    private final PlayerValueRepository playerValues;

    public LeagueValueCoverageAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagueAnalyzer = new LeagueAnalyzer(database);
        this.rosters = new RosterRepository(database);
        this.playerValues = new PlayerValueRepository(database);
    }

    public CoverageReport analyze(String leagueId) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        LeagueAnalyzer.LeagueReport league = leagueAnalyzer.analyze(normalizedLeagueId);
        List<SourceCoverage> sources = new ArrayList<>();

        for (String source : playerValues.findSources()) {
            int valuedPlayers = 0;
            int missingValues = 0;
            int uncoveredTeams = 0;
            LocalDate oldestValueDate = null;
            LocalDate latestValueDate = null;

            for (LeagueAnalyzer.TeamReport team : league.teams()) {
                int teamValuedPlayers = 0;
                int teamMissingValues = 0;
                for (Roster roster : rosters.findByTeamId(team.teamId())) {
                    PlayerValue value = playerValues.findLatestByPlayerIdAndSource(roster.getPlayerId(), source).orElse(null);
                    if (value == null) {
                        missingValues++;
                        teamMissingValues++;
                        continue;
                    }
                    valuedPlayers++;
                    teamValuedPlayers++;
                    oldestValueDate = earlier(oldestValueDate, value.getAsOfDate());
                    latestValueDate = later(latestValueDate, value.getAsOfDate());
                }
                if (teamValuedPlayers == 0 && teamMissingValues > 0) uncoveredTeams++;
            }

            sources.add(new SourceCoverage(source, valuedPlayers, missingValues, uncoveredTeams,
                oldestValueDate, latestValueDate));
        }

        return new CoverageReport(normalizedLeagueId, List.copyOf(sources));
    }

    private static LocalDate earlier(LocalDate current, LocalDate candidate) {
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    private static LocalDate later(LocalDate current, LocalDate candidate) {
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum RankingReadiness {
        UNAVAILABLE,
        BLOCKED,
        PARTIAL,
        READY
    }

    public record CoverageReport(String leagueId, List<SourceCoverage> sources) {}

    public record SourceCoverage(String source, int valuedPlayers, int missingValues, int uncoveredTeams,
                                 LocalDate oldestValueDate, LocalDate latestValueDate) {
        public int totalPlayers() { return valuedPlayers + missingValues; }
        public double coveragePercent() {
            return totalPlayers() == 0 ? 0.0 : valuedPlayers * 100.0 / totalPlayers();
        }
        public boolean rankable() {
            return valuedPlayers > 0 && uncoveredTeams == 0;
        }
        public RankingReadiness readiness() {
            if (valuedPlayers == 0) return RankingReadiness.UNAVAILABLE;
            if (uncoveredTeams > 0) return RankingReadiness.BLOCKED;
            if (missingValues > 0) return RankingReadiness.PARTIAL;
            return RankingReadiness.READY;
        }
    }
}
