package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerProfileRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerProfile;
import io.butler.bet.domain.PlayerSeasonProduction;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeaguePlayerEvidenceProfileAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void preservesIndependentAgeAndProductionCoverage() throws Exception {
        Database database = seeded();
        var profiles = new PlayerProfileRepository(database);
        profiles.save(new PlayerProfile("p1", LocalDate.of(2000, 1, 1), 5));
        profiles.save(new PlayerProfile("p2", LocalDate.of(2002, 6, 15), 3));
        new PlayerSeasonProductionRepository(database).save(PlayerSeasonProduction.create(
            "p1", 2025, 17, 100, 1, 1, 400, 4, 40, 300, 2, 1,
            "nflverse", LocalDate.of(2026, 1, 10)));

        var report = new LeaguePlayerEvidenceProfileAnalyzer(database).analyze(
            "l1", 2025, LocalDate.of(2026, 9, 2), null);

        assertEquals(2, report.totalPlayers());
        assertEquals(100.0, report.ageCoveragePercent());
        assertEquals(50.0, report.productionCoveragePercent());
        assertEquals(1, report.teams().size());
        var team = report.teams().getFirst();
        assertEquals(100.0, team.ageCoveragePercent());
        assertEquals(50.0, team.productionCoveragePercent());
        assertEquals(26.0, team.age().averageAge());
        assertEquals(1, team.production().missingPlayers().size());
        assertEquals("p2", team.production().missingPlayers().getFirst().playerId());
    }

    @Test
    void defaultsProductionSeasonFromLeagueMetadata() throws Exception {
        Database database = seeded();
        new PlayerProfileRepository(database).save(new PlayerProfile("p1", LocalDate.of(2000, 1, 1), 5));
        new PlayerSeasonProductionRepository(database).save(PlayerSeasonProduction.create(
            "p1", 2025, 17, 0, 0, 0, 500, 5, 30, 250, 2, 0,
            "nflverse", LocalDate.of(2026, 1, 10)));

        var report = new LeaguePlayerEvidenceProfileAnalyzer(database).analyze("l1");

        assertEquals(2025, report.season());
    }

    private Database seeded() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", null, "League", 2025));
        new TeamRepository(database).save(new Team("t1", null, "l1", "Alpha"));
        new PlayerRepository(database).save(new Player("p1", "101", "Player One", "RB", null));
        new PlayerRepository(database).save(new Player("p2", "102", "Player Two", "WR", null));
        new RosterRepository(database).save(new Roster("r1", null, "t1", "p1", "STARTER"));
        new RosterRepository(database).save(new Roster("r2", null, "t1", "p2", "BENCH"));
        return database;
    }
}
