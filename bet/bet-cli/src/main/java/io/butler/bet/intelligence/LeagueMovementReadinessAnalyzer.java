package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Objects;

public final class LeagueMovementReadinessAnalyzer {
    private final LeagueValueMoverAnalyzer movers;

    public LeagueMovementReadinessAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.movers = new LeagueValueMoverAnalyzer(database);
    }

    public ReadinessReport analyze(String leagueId, String source) throws SQLException {
        var report = movers.analyze(leagueId, source);
        Readiness readiness;
        if (report.previousDate() == null || report.latestDate() == null) readiness = Readiness.UNAVAILABLE;
        else if (report.totalPlayers() == 0) readiness = Readiness.READY;
        else if (report.comparablePlayers() == 0) readiness = Readiness.BLOCKED;
        else if (report.missingPlayers() > 0) readiness = Readiness.PARTIAL;
        else readiness = Readiness.READY;

        return new ReadinessReport(
            report.leagueId(), report.source(), report.previousDate(), report.latestDate(),
            report.totalPlayers(), report.comparablePlayers(), report.missingPlayers(), readiness);
    }

    public enum Readiness {
        UNAVAILABLE,
        BLOCKED,
        PARTIAL,
        READY
    }

    public record ReadinessReport(String leagueId, String source,
                                  LocalDate previousDate, LocalDate latestDate,
                                  int totalPlayers, int comparablePlayers, int missingPlayers,
                                  Readiness readiness) {
        public double coveragePercent() {
            return totalPlayers == 0 ? 0.0 : (comparablePlayers * 100.0) / totalPlayers;
        }
    }
}
