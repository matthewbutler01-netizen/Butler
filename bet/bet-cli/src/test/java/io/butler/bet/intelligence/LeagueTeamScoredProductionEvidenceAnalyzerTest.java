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
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueTeamScoredProductionEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void aggregatesObservedRosterProductionWithoutRankingTeamsByTotal() throws Exception {
        Database database = database();
        new LeagueScoringSettingsRepository(database).replace("league-1", Map.of("rec", 1.0));
        var production = new PlayerSeasonProductionRepository(database);
        production.save(row("prod-alpha", "p-alpha", 5));
        production.save(row("prod-beta", "p-beta", 20));

        var report = new LeagueTeamScoredProductionEvidenceAnalyzer(database)
            .analyze("league-1", 2026, "nflverse");

        assertEquals(2, report.teams().size());
        var alpha = report.teams().get(0);
        var beta = report.teams().get(1);
        assertEquals("Alpha Team", alpha.teamName());
        assertEquals(0, alpha.observedFantasyPoints().compareTo(new BigDecimal("5.0")));
        assertEquals(1, alpha.coveredPlayers());
        assertEquals(2, alpha.totalPlayers());
        assertFalse(alpha.complete());
        assertEquals(50.0, alpha.coveragePercent(), 0.001);
        assertEquals("Beta Team", beta.teamName());
        assertEquals(0, beta.observedFantasyPoints().compareTo(new BigDecimal("20.0")));
        assertTrue(beta.complete());
        assertTrue(alpha.observedFantasyPoints().compareTo(beta.observedFantasyPoints()) < 0,
            "lower observed total intentionally remains first because teams are not score-ranked");
    }

    private Database database() throws Exception {
        Database database = new Database(tempDir.resolve("team-scoring.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("league-1", "sleeper-1", "Test League", 2026));
        var teams = new TeamRepository(database);
        teams.save(new Team("team-alpha", "roster-alpha", "league-1", "Alpha Team"));
        teams.save(new Team("team-beta", "roster-beta", "league-1", "Beta Team"));
        var players = new PlayerRepository(database);
        players.save(new Player("p-alpha", "s-alpha", "Alpha Player", "WR", "CHI"));
        players.save(new Player("p-alpha-missing", "s-alpha-missing", "Alpha Missing", "WR", "DET"));
        players.save(new Player("p-beta", "s-beta", "Beta Player", "WR", "GB"));
        var rosters = new RosterRepository(database);
        rosters.save(new Roster("r-alpha-1", null, "team-alpha", "p-alpha", "01"));
        rosters.save(new Roster("r-alpha-2", null, "team-alpha", "p-alpha-missing", "02"));
        rosters.save(new Roster("r-beta-1", null, "team-beta", "p-beta", "01"));
        return database;
    }

    private static PlayerSeasonProduction row(String id, String playerId, int receptions) {
        return new PlayerSeasonProduction(
            id, playerId, 2026, 17,
            0, 0, 0, 0, 0, receptions, 0, 0, 0,
            "nflverse", LocalDate.of(2027, 1, 15));
    }
}
