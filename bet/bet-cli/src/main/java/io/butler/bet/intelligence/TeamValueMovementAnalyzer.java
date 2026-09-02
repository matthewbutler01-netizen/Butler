package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TeamValueMovementAnalyzer {
    private final LeagueValueMoverAnalyzer leagueMovers;

    public TeamValueMovementAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagueMovers = new LeagueValueMoverAnalyzer(database);
    }

    public MovementReport analyze(String leagueId, String source) throws SQLException {
        var report = leagueMovers.analyze(leagueId, source);
        Map<String, MutableTeamMovement> byTeam = new LinkedHashMap<>();

        for (var mover : report.movers()) {
            var team = byTeam.computeIfAbsent(
                mover.teamId(),
                ignored -> new MutableTeamMovement(mover.teamId(), mover.teamName()));
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
                team.playersWithHistory,
                team.risers,
                team.fallers,
                team.unchanged,
                team.delta));
        }

        teams.sort(Comparator.comparingDouble((TeamMovement team) -> Math.abs(team.delta())).reversed()
            .thenComparing(TeamMovement::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamMovement::teamId));

        return new MovementReport(report.leagueId(), report.source(), List.copyOf(teams));
    }

    public record MovementReport(String leagueId, String source, List<TeamMovement> teams) {}

    public record TeamMovement(String teamId, String teamName,
                               int playersWithHistory, int risers, int fallers, int unchanged,
                               double delta) {}

    private static final class MutableTeamMovement {
        private final String teamId;
        private final String teamName;
        private int playersWithHistory;
        private int risers;
        private int fallers;
        private int unchanged;
        private double delta;

        private MutableTeamMovement(String teamId, String teamName) {
            this.teamId = teamId;
            this.teamName = teamName;
        }
    }
}
