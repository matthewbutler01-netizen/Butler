package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerSeasonProduction;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueScoredProductionEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void reportsRosteredPlayersInTeamRosterOrderWithoutScoreRanking() throws Exception {
        Database database = database("league.db");
        new LeagueScoringSettingsRepository(database).replace("league-1", Map.of("rec", 1.0));
        var production = new PlayerSeasonProductionRepository(database);
        production.save(row("prod-low", "p-low", 2));
        production.save(row("prod-high", "p-high", 10));

        var report = new LeagueScoredProductionEvidenceAnalyzer(database)
            .analyze("league-1", 2026, "nflverse");

        assertEquals(3, report.players().size());
        assertEquals(2, report.coveredPlayers());
        assertFalse(report.complete());
        assertEquals(2 * 100.0 / 3.0, report.coveragePercent(), 0.001);
        assertEquals("p-low", report.players().get(0).playerId());
        assertEquals(0, report.players().get(0).fantasyPoints().compareTo(new BigDecimal("2.0")));
        assertEquals("p-high", report.players().get(1).playerId());
        assertEquals(0, report.players().get(1).fantasyPoints().compareTo(new BigDecimal("10.0")));
        assertEquals("p-missing", report.players().get(2).playerId());
        assertFalse(report.players().get(2).available());
        assertTrue(report.players().get(2).unavailableReason().contains("No persisted production"));
    }

    @Test
    void blocksWholeReportWhenScoringCoverageIsIncomplete() throws Exception {
        Database database = database("blocked.db");
        new LeagueScoringSettingsRepository(database).replace("league-1", Map.of(
            "rec", 1.0,
            "bonus_rec_te", 0.5));

        var error = assertThrows(IllegalStateException.class,
            () -> new LeagueScoredProductionEvidenceAnalyzer(database)
                .analyze("league-1", 2026, "nflverse"));

        assertTrue(error.getMessage().contains("Exact league scoring unavailable"));
    }

    private Database database(String file) throws Exception {
        Database database = new Database(tempDir.resolve(file));
        database.initialize();
        new LeagueRepository(database).save(new League("league-1", "sleeper-1", "Test League", 2026));
        new TeamRepository(database).save(new Team("team-1", "roster-1", "league-1", "Alpha Team"));
        var players = new PlayerRepository(database);
        players.save(new Player("p-low", "s-low", "Low Player", "WR", "CHI"));
        players.save(new Player("p-high", "s-high", "High Player", "WR", "DET"));
        players.save(new Player("p-missing", "s-missing", "Missing Player", "WR", "GB"));
        var rosters = new RosterRepository(database);
        rosters.save(new Roster("r-1", null, "team-1", "p-low", "01"));
        rosters.save(new Roster("r-2", null, "team-1", "p-high", "02"));
        rosters.save(new Roster("r-3", null, "team-1", "p-missing", "03"));
        return database;
    }

    private static PlayerSeasonProduction row(String id, String playerId, int receptions) {
        return new PlayerSeasonProduction(
            id, playerId, 2026, 17,
            0, 0, 0, 0, 0, receptions, 0, 0, 0,
            "nflverse", LocalDate.of(2027, 1, 15));
    }
}
