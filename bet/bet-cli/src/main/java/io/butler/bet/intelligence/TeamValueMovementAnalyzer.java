package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TeamValueMovementAnalyzer {
    private final LeagueAnalyzer leagues;
    private final LeagueValueMoverAnalyzer leagueMovers;

    public TeamValueMovementAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueAnalyzer(database);
        this.leagueMovers = new LeagueValueMoverAnalyzer(database);
    }

    public MovementReport analyze(String leagueId, String source) throws SQLException {
        var report = leagueMovers.analyze(leagueId, source);
        var league = leagues.analyze(report.leagueId());
        Map<String, MutableTeamMovement> byTeam = new LinkedHashMap<>();

        for (var team : league.teams()) {
            byTeam.put(team.teamId(), new MutableTeamMovement(team.teamId(), team.teamName(), team.rosterSize()));
        }

        for (var mover : report.movers()) {
            var team = byTeam.get(mover.teamId());
            if (team == null) throw new IllegalStateException("mover team not found in league: " + mover.teamId());
            team.delta += mover.delta();
            team.playersWithHistory++;
            if (mover.delta() > 0) team.risers++;
            else if (mover.delta() < 0) team.fallers++;
            else team.unchanged++;
        }

        List<TeamMovement> teams = new ArrayList<>();
        for (var team : byTeam.values()) {
            teams.add(new TeamMovement(
                team.teamId,
                team.teamName,
                team.rosterSize,
                team.playersWithHistory,
                team.risers,
                team.fallers,
                team.unchanged,
                team.delta));
        }

        teams.sort(Comparator.comparingDouble((TeamMovement team) -> Math.abs(team.delta())).reversed()
            .thenComparing(TeamMovement::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamMovement::teamId));

        return new MovementReport(report.leagueId(), report.source(), report.previousDate(), report.latestDate(),
            report.totalPlayers(), report.comparablePlayers(), report.missingPlayers(), List.copyOf(teams));
    }

    public record MovementReport(String leagueId, String source,
                                 LocalDate previousDate, LocalDate latestDate,
                                 int totalPlayers, int comparablePlayers, int missingPlayers,
                                 List<TeamMovement> teams) {
        public double coveragePercent() {
            return totalPlayers == 0 ? 0.0 : (comparablePlayers * 100.0) / totalPlayers;
        }
    }

    public record TeamMovement(String teamId, String teamName, int rosterSize,
                               int playersWithHistory, int risers, int fallers, int unchanged,
                               double delta) {
        public int playersWithoutHistory() {
            return rosterSize - playersWithHistory;
        }

        public double historyCoveragePercent() {
            return rosterSize == 0 ? 0.0 : (playersWithHistory * 100.0) / rosterSize;
        }
    }

    private static final class MutableTeamMovement {
        private final String teamId;
        private final String teamName;
        private final int rosterSize;
        private int playersWithHistory;
        private int risers;
        private int fallers;
        private int unchanged;
        private double delta;

        private MutableTeamMovement(String teamId, String teamName, int rosterSize) {
            this.teamId = teamId;
            this.teamName = teamName;
            this.rosterSize = rosterSize;
        }
    }
}
