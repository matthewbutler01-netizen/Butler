package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TeamMovementReadinessAnalyzer {
    private final TeamValueMovementAnalyzer movement;

    public TeamMovementReadinessAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.movement = new TeamValueMovementAnalyzer(database);
    }

    public ReadinessReport analyze(String leagueId, String source) throws SQLException {
        var report = movement.analyze(leagueId, source);
        List<TeamReadiness> teams = new ArrayList<>();
        for (var team : report.teams()) {
            TeamStatus status;
            if (report.previousDate() == null || report.latestDate() == null) status = TeamStatus.UNAVAILABLE;
            else if (team.rosterSize() == 0) status = TeamStatus.READY;
            else if (team.playersWithHistory() == 0) status = TeamStatus.BLOCKED;
            else if (team.playersWithoutHistory() > 0) status = TeamStatus.PARTIAL;
            else status = TeamStatus.READY;

            teams.add(new TeamReadiness(
                team.teamId(), team.teamName(), team.rosterSize(), team.playersWithHistory(),
                team.playersWithoutHistory(), team.historyCoveragePercent(), status));
        }
        return new ReadinessReport(report.leagueId(), report.source(), report.previousDate(), report.latestDate(), List.copyOf(teams));
    }

    public enum TeamStatus {
        UNAVAILABLE,
        BLOCKED,
        PARTIAL,
        READY
    }

    public record ReadinessReport(String leagueId, String source,
                                  LocalDate previousDate, LocalDate latestDate,
                                  List<TeamReadiness> teams) {}

    public record TeamReadiness(String teamId, String teamName,
                                int rosterSize, int comparablePlayers, int missingPlayers,
                                double coveragePercent, TeamStatus status) {}
}
