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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueAgeProductionContextAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void combinesExactAgeWithPerGameRawProductionWithoutACompositeScore() throws Exception {
        Database database = seeded();
        new PlayerProfileRepository(database).save(new PlayerProfile("p1", LocalDate.of(2000, 1, 1), 5));
        new PlayerSeasonProductionRepository(database).save(PlayerSeasonProduction.create(
            "p1", 2025, 10, 1000, 10, 5, 200, 4, 30, 400, 3, 1,
            "nflverse", LocalDate.of(2026, 1, 10)));

        var report = new LeagueAgeProductionContextAnalyzer(database).analyze(
            "l1", 2025, LocalDate.of(2026, 9, 2), null);

        assertEquals(2, report.totalPlayers());
        assertEquals(1, report.ageCoveredPlayers());
        assertEquals(1, report.productionCoveredPlayers());
        assertEquals(1, report.rateCoveredPlayers());
        assertEquals(1, report.jointCoveredPlayers());
        assertEquals(50.0, report.jointCoveragePercent());

        var player = report.teams().getFirst().players().stream()
            .filter(value -> value.playerId().equals("p1"))
            .findFirst().orElseThrow();
        assertEquals(26, player.age());
        assertEquals(LeagueAgeContextAnalyzer.AgeProvenance.EXACT_BIRTH_DATE, player.ageProvenance());
        assertTrue(player.productionAvailable());
        assertTrue(player.ratesAvailable());
        assertEquals(100.0, player.passingYardsPerGame());
        assertEquals(20.0, player.rushingYardsPerGame());
        assertEquals(3.0, player.receptionsPerGame());
        assertEquals(40.0, player.receivingYardsPerGame());
    }

    @Test
    void zeroGameSnapshotIsEvidenceButDoesNotInventPerGameRates() throws Exception {
        Database database = seeded();
        new PlayerProfileRepository(database).save(new PlayerProfile("p1", LocalDate.of(2000, 1, 1), 5));
        new PlayerSeasonProductionRepository(database).save(PlayerSeasonProduction.create(
            "p1", 2025, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            "nflverse", LocalDate.of(2026, 1, 10)));

        var report = new LeagueAgeProductionContextAnalyzer(database).analyze(
            "l1", 2025, LocalDate.of(2026, 9, 2), null);
        var player = report.teams().getFirst().players().stream()
            .filter(value -> value.playerId().equals("p1"))
            .findFirst().orElseThrow();

        assertTrue(player.productionAvailable());
        assertFalse(player.ratesAvailable());
        assertTrue(player.jointEvidenceAvailable());
        assertNull(player.passingYardsPerGame());
        assertEquals(1, report.productionCoveredPlayers());
        assertEquals(0, report.rateCoveredPlayers());
    }

    @Test
    void missingProductionRemainsVisibleInsteadOfBecomingZeroRates() throws Exception {
        Database database = seeded();
        new PlayerProfileRepository(database).save(new PlayerProfile("p2", LocalDate.of(2002, 6, 15), 3));

        var report = new LeagueAgeProductionContextAnalyzer(database).analyze(
            "l1", 2025, LocalDate.of(2026, 9, 2), null);
        var player = report.teams().getFirst().players().stream()
            .filter(value -> value.playerId().equals("p2"))
            .findFirst().orElseThrow();

        assertEquals(24, player.age());
        assertFalse(player.productionAvailable());
        assertFalse(player.ratesAvailable());
        assertNull(player.receivingYardsPerGame());
    }

    private Database seeded() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", null, "League", 2025));
        new TeamRepository(database).save(new Team("t1", null, "l1", "Alpha"));
        new PlayerRepository(database).save(new Player("p1", "101", "Player One", "QB", null));
        new PlayerRepository(database).save(new Player("p2", "102", "Player Two", "WR", null));
        new RosterRepository(database).save(new Roster("r1", null, "t1", "p1", "STARTER"));
        new RosterRepository(database).save(new Roster("r2", null, "t1", "p2", "BENCH"));
        return database;
    }
}
