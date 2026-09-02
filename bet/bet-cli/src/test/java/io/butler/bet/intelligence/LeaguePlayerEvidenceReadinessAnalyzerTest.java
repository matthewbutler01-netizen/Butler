package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerProfileSnapshotRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerProfileSnapshot;
import io.butler.bet.domain.PlayerSeasonProduction;
import io.butler.bet.domain.Roster;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaguePlayerEvidenceReadinessAnalyzerTest {
    @TempDir Path tempDir;

    @Test
    void readyRequiresAgeAndProductionEvidenceForEveryRosterPlayer() throws Exception {
        Database database = seeded(2);
        var snapshots = new PlayerProfileSnapshotRepository(database);
        var production = new PlayerSeasonProductionRepository(database);
        for (int i = 1; i <= 2; i++) {
            snapshots.save(PlayerProfileSnapshot.create("p" + i, 24 + i, i, "sleeper", LocalDate.of(2026, 9, 1)));
            production.save(PlayerSeasonProduction.create("p" + i, 2025, 17, 0, 0, 0,
                100 * i, i, 10 * i, 100 * i, i, 0, "nflverse", LocalDate.of(2026, 1, 10)));
        }

        var report = new LeaguePlayerEvidenceReadinessAnalyzer(database).analyze("l1", 2025);

        assertEquals(LeaguePlayerEvidenceReadinessAnalyzer.Readiness.READY, report.readiness());
        assertTrue(report.ready());
        assertEquals(100.0, report.ageCoveragePercent());
        assertEquals(100.0, report.productionCoveragePercent());
    }

    @Test
    void resolvesStoredLeagueSeasonWhenExplicitSeasonIsOmitted() throws Exception {
        Database database = seeded(1, 2025);
        new PlayerProfileSnapshotRepository(database).save(
            PlayerProfileSnapshot.create("p1", 25, 2, "sleeper", LocalDate.of(2026, 9, 1)));
        new PlayerSeasonProductionRepository(database).save(PlayerSeasonProduction.create(
            "p1", 2025, 17, 0, 0, 0, 500, 4, 20, 150, 1, 0, "nflverse", LocalDate.of(2026, 1, 10)));

        var report = new LeaguePlayerEvidenceReadinessAnalyzer(database).analyze("l1");

        assertEquals(2025, report.season());
        assertEquals(LeaguePlayerEvidenceReadinessAnalyzer.Readiness.READY, report.readiness());
    }

    @Test
    void refusesImplicitSeasonWhenLeagueMetadataDoesNotContainOne() throws Exception {
        Database database = seeded(1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new LeaguePlayerEvidenceReadinessAnalyzer(database).analyze("l1"));

        assertTrue(error.getMessage().contains("league season is unavailable"));
    }

    @Test
    void partialRequiresSomeButNotAllEvidenceOnBothDimensions() throws Exception {
        Database database = seeded(2);
        new PlayerProfileSnapshotRepository(database).save(
            PlayerProfileSnapshot.create("p1", 25, 2, "sleeper", LocalDate.of(2026, 9, 1)));
        new PlayerSeasonProductionRepository(database).save(PlayerSeasonProduction.create(
            "p1", 2025, 17, 0, 0, 0, 500, 4, 20, 150, 1, 0, "nflverse", LocalDate.of(2026, 1, 10)));

        var report = new LeaguePlayerEvidenceReadinessAnalyzer(database).analyze("l1", 2025);

        assertEquals(LeaguePlayerEvidenceReadinessAnalyzer.Readiness.PARTIAL, report.readiness());
        assertFalse(report.ready());
        assertEquals(50.0, report.ageCoveragePercent());
        assertEquals(50.0, report.productionCoveragePercent());
    }

    @Test
    void blockedWhenEitherEvidenceDimensionIsAbsent() throws Exception {
        Database database = seeded(2);
        var snapshots = new PlayerProfileSnapshotRepository(database);
        snapshots.save(PlayerProfileSnapshot.create("p1", 25, null, "sleeper", LocalDate.of(2026, 9, 1)));
        snapshots.save(PlayerProfileSnapshot.create("p2", 26, null, "sleeper", LocalDate.of(2026, 9, 1)));

        var report = new LeaguePlayerEvidenceReadinessAnalyzer(database).analyze("l1", 2025);

        assertEquals(LeaguePlayerEvidenceReadinessAnalyzer.Readiness.BLOCKED, report.readiness());
        assertEquals(100.0, report.ageCoveragePercent());
        assertEquals(0.0, report.productionCoveragePercent());
    }

    @Test
    void profileFreshnessCutoffCanBlockOtherwisePresentReportedAge() throws Exception {
        Database database = seeded(1);
        new PlayerProfileSnapshotRepository(database).save(
            PlayerProfileSnapshot.create("p1", 25, 2, "sleeper", LocalDate.of(2026, 8, 1)));
        new PlayerSeasonProductionRepository(database).save(PlayerSeasonProduction.create(
            "p1", 2025, 17, 0, 0, 0, 500, 4, 20, 150, 1, 0, "nflverse", LocalDate.of(2026, 1, 10)));

        var report = new LeaguePlayerEvidenceReadinessAnalyzer(database).analyze(
            "l1", 2025, LocalDate.of(2026, 9, 1));

        assertEquals(LeaguePlayerEvidenceReadinessAnalyzer.Readiness.BLOCKED, report.readiness());
        assertEquals(0, report.ageEvidencePlayers());
        assertEquals(1, report.productionEvidencePlayers());
    }

    private Database seeded(int playerCount) throws Exception {
        return seeded(playerCount, null);
    }

    private Database seeded(int playerCount, Integer season) throws Exception {
        Database database = new Database(tempDir.resolve("test.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", null, "League", season));
        new TeamRepository(database).save(new Team("t1", null, "l1", "Alpha"));
        for (int i = 1; i <= playerCount; i++) {
            new PlayerRepository(database).save(new Player("p" + i, "10" + i, "Player " + i, "RB", null));
            new RosterRepository(database).save(new Roster("r" + i, null, "t1", "p" + i, "STARTER"));
        }
        return database;
    }
}
