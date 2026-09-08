package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.PlayerSeasonProduction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverTargetRosterProductionComparabilityAuditTest {
    @TempDir Path tempDir;

    @Test
    void partitionsExactTargetRosterAndUsesLatestObservationPerSource() throws Exception {
        Database database = databaseWithPlayers();
        PlayerSeasonProductionRepository production = new PlayerSeasonProductionRepository(database);
        production.save(PlayerSeasonProduction.create(
            "B1", 2025, 10, 100, 1, 0, 50, 1, 10, 100, 1, 0,
            "nflverse", LocalDate.of(2026, 1, 1)));
        production.save(PlayerSeasonProduction.create(
            "B1", 2025, 17, 200, 2, 0, 80, 2, 20, 250, 2, 0,
            "nflverse", LocalDate.of(2026, 2, 1)));
        production.save(PlayerSeasonProduction.create(
            "B1", 2025, 17, 210, 2, 0, 85, 2, 21, 260, 2, 0,
            "provider-test", LocalDate.of(2026, 2, 2)));
        production.save(PlayerSeasonProduction.create(
            "B2", 2025, 16, 0, 0, 0, 700, 6, 35, 250, 1, 1,
            "nflverse", LocalDate.of(2026, 2, 1)));
        production.save(PlayerSeasonProduction.create(
            "B3", 2024, 17, 0, 0, 0, 500, 3, 20, 100, 0, 0,
            "nflverse", LocalDate.of(2025, 2, 1)));

        var audit = new SleeperLiveWaiverTargetRosterProductionComparabilityAudit(
            database, (leagueId, ownerId) -> exactContext());
        var report = audit.audit("L", "owner-1");

        assertEquals(3, report.targetPlayerCount());
        assertEquals(2, report.priorProductionPresent());
        assertEquals(1, report.priorProductionMissing());
        assertEquals(2, report.players().get(0).production().size());
        assertEquals(LocalDate.of(2026, 2, 1), report.players().get(0).production().stream()
            .filter(row -> "nflverse".equals(row.source())).findFirst().orElseThrow().asOfDate());
        assertEquals(SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_MISSING,
            report.players().get(2).state());
        assertTrue(report.players().get(2).production().isEmpty());
        assertEquals(2, report.sourceCoverage().get("nflverse").targetPlayerObservations());
        assertEquals(1, report.sourceCoverage().get("provider-test").targetPlayerObservations());
    }

    @Test
    void anyUnmappedTargetPlayerBlocksComparability() throws Exception {
        Database database = databaseWithPlayers();
        var context = contextWithUnmappedPlayer();
        var audit = new SleeperLiveWaiverTargetRosterProductionComparabilityAudit(
            database, (leagueId, ownerId) -> context);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> audit.audit("L", "owner-1"));
        assertTrue(error.getMessage().contains("every target-roster player must have an exact canonical mapping"));
    }

    @Test
    void contextMustMatchRequestedOwner() throws Exception {
        Database database = databaseWithPlayers();
        var audit = new SleeperLiveWaiverTargetRosterProductionComparabilityAudit(
            database, (leagueId, ownerId) -> exactContext());

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> audit.audit("L", "different-owner"));
        assertTrue(error.getMessage().contains("does not match requested league/owner"));
    }

    private Database databaseWithPlayers() throws Exception {
        Database database = new Database(tempDir.resolve("butler-test.db"));
        database.initialize();
        PlayerRepository players = new PlayerRepository(database);
        players.save(new Player("B1", "p1", "Player One", "QB", "A"));
        players.save(new Player("B2", "p2", "Player Two", "RB", "B"));
        players.save(new Player("B3", "p3", "Player Three", "WR", "C"));
        return database;
    }

    private static SleeperLiveWaiverTargetRosterContextAudit.AuditReport exactContext() {
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players = List.of(
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "p1", "STARTER", 0, "QB", "B1", "Player One", "QB", "A", "EXACT_CANONICAL"),
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "p2", "BENCH", null, null, "B2", "Player Two", "RB", "B", "EXACT_CANONICAL"),
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "p3", "RESERVE", null, null, "B3", "Player Three", "WR", "C", "EXACT_CANONICAL"));
        return new SleeperLiveWaiverTargetRosterContextAudit.AuditReport(
            SleeperLiveWaiverTargetRosterContextAudit.POLICY_ID,
            "L", "M", "W", "S", 2026, "in_season", 1,
            "owner-1", "Owner", "Team", 1, "T", "Team",
            List.of("QB", "BN"), List.of("QB"),
            51, 42, 3, 1, 1, 1, 0, 3, 0, players);
    }

    private static SleeperLiveWaiverTargetRosterContextAudit.AuditReport contextWithUnmappedPlayer() {
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players = List.of(
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "p1", "STARTER", 0, "QB", "B1", "Player One", "QB", "A", "EXACT_CANONICAL"),
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "p2", "BENCH", null, null, "B2", "Player Two", "RB", "B", "EXACT_CANONICAL"),
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "pX", "RESERVE", null, null, null, null, null, null, "UNMAPPED_CANONICAL"));
        return new SleeperLiveWaiverTargetRosterContextAudit.AuditReport(
            SleeperLiveWaiverTargetRosterContextAudit.POLICY_ID,
            "L", "M", "W", "S", 2026, "in_season", 1,
            "owner-1", "Owner", "Team", 1, "T", "Team",
            List.of("QB", "BN"), List.of("QB"),
            51, 42, 3, 1, 1, 1, 0, 2, 1, players);
    }
}
