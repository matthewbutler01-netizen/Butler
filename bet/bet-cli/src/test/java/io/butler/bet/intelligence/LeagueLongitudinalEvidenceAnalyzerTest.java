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
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueLongitudinalEvidenceAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void countsExactAgeConsecutiveRatePairsWithoutInventingReadinessThresholds() throws Exception {
        Database database = seeded();
        var profiles = new PlayerProfileRepository(database);
        profiles.save(new PlayerProfile("p1", LocalDate.of(2000, 1, 1), 5));
        profiles.save(new PlayerProfile("p3", LocalDate.of(1998, 5, 1), 7));

        var production = new PlayerSeasonProductionRepository(database);
        production.save(snapshot("p1", 2023, 10, LocalDate.of(2024, 1, 1)));
        production.save(snapshot("p1", 2024, 12, LocalDate.of(2025, 1, 1)));
        production.save(snapshot("p1", 2024, 12, LocalDate.of(2025, 2, 1)));
        production.save(snapshot("p1", 2025, 0, LocalDate.of(2026, 1, 1)));

        production.save(snapshot("p2", 2024, 17, LocalDate.of(2025, 1, 1)));
        production.save(snapshot("p2", 2025, 17, LocalDate.of(2026, 1, 1)));

        production.save(snapshot("p3", 2022, 10, LocalDate.of(2023, 1, 1)));
        production.save(snapshot("p3", 2024, 10, LocalDate.of(2025, 1, 1)));

        var report = new LeagueLongitudinalEvidenceAnalyzer(database).analyze("l1");

        assertEquals(3, report.totalPlayers());
        assertEquals(2, report.exactBirthDatePlayers());
        assertEquals(7, report.productionPlayerSeasons());
        assertEquals(6, report.rateEligiblePlayerSeasons());
        assertEquals(2, report.consecutiveRatePairs());
        assertEquals(1, report.exactAgeConsecutiveRatePairs());
        assertEquals(1, report.playersWithExactAgeConsecutiveRatePair());

        var rb = report.teams().getFirst().positions().get("RB");
        assertEquals(2, rb.totalPlayers());
        assertEquals(2, rb.exactBirthDatePlayers());
        assertEquals(5, rb.productionPlayerSeasons());
        assertEquals(4, rb.rateEligiblePlayerSeasons());
        assertEquals(1, rb.consecutiveRatePairs());
        assertEquals(1, rb.exactAgeConsecutiveRatePairs());
    }

    @Test
    void currentPlayerWithoutExactBirthDateCanHaveProductionPairsButNotExactAgePairs() throws Exception {
        Database database = seeded();
        var production = new PlayerSeasonProductionRepository(database);
        production.save(snapshot("p2", 2024, 17, LocalDate.of(2025, 1, 1)));
        production.save(snapshot("p2", 2025, 17, LocalDate.of(2026, 1, 1)));

        var player = new LeagueLongitudinalEvidenceAnalyzer(database).analyze("l1")
            .teams().getFirst().players().stream()
            .filter(value -> value.playerId().equals("p2"))
            .findFirst().orElseThrow();

        assertFalse(player.exactBirthDateAvailable());
        assertEquals(1, player.consecutiveRatePairs());
        assertEquals(0, player.exactAgeConsecutiveRatePairs());
        assertFalse(player.exactAgePairAvailable());
    }

    @Test
    void zeroGameSeasonIsHistoricalProductionButDoesNotCreateRatePair() throws Exception {
        Database database = seeded();
        new PlayerProfileRepository(database).save(new PlayerProfile("p1", LocalDate.of(2000, 1, 1), 5));
        var production = new PlayerSeasonProductionRepository(database);
        production.save(snapshot("p1", 2024, 10, LocalDate.of(2025, 1, 1)));
        production.save(snapshot("p1", 2025, 0, LocalDate.of(2026, 1, 1)));

        var player = new LeagueLongitudinalEvidenceAnalyzer(database).analyze("l1")
            .teams().getFirst().players().stream()
            .filter(value -> value.playerId().equals("p1"))
            .findFirst().orElseThrow();

        assertEquals(2, player.productionSeasons().size());
        assertEquals(1, player.rateEligibleSeasons().size());
        assertEquals(0, player.consecutiveRatePairs());
        assertEquals(0, player.exactAgeConsecutiveRatePairs());
        assertTrue(player.exactBirthDateAvailable());
    }

    private PlayerSeasonProduction snapshot(String playerId, int season, int games, LocalDate asOf) {
        return PlayerSeasonProduction.create(playerId, season, games,
            0, 0, 0, 100, 1, 10, 100, 1, 0,
            "nflverse", asOf);
    }

    private Database seeded() throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", null, "League", 2025));
        new TeamRepository(database).save(new Team("t1", null, "l1", "Alpha"));
        new PlayerRepository(database).save(new Player("p1", "101", "Runner One", "RB", null));
        new PlayerRepository(database).save(new Player("p2", "102", "Receiver Two", "WR", null));
        new PlayerRepository(database).save(new Player("p3", "103", "Runner Three", "RB", null));
        new RosterRepository(database).save(new Roster("r1", null, "t1", "p1", "STARTER"));
        new RosterRepository(database).save(new Roster("r2", null, "t1", "p2", "STARTER"));
        new RosterRepository(database).save(new Roster("r3", null, "t1", "p3", "BENCH"));
        return database;
    }
}
