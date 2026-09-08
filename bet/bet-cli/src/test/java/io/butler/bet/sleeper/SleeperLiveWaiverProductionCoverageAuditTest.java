package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LiveWaiverMarketAttentionRepository;
import io.butler.bet.data.LiveWaiverSnapshotRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SleeperLiveWaiverProductionCoverageAuditTest {
    @TempDir Path tempDir;

    @Test
    void auditsExactMarketActiveFrameAndPreservesIndependentProductionSources() throws Exception {
        Database database = seededDatabase();
        seedEvidence(database);

        var report = new SleeperLiveWaiverProductionCoverageAudit(database).audit("L");

        assertEquals(3, report.marketActiveCandidates());
        assertEquals(1, report.unmappedCanonical());
        assertEquals(1, report.mappedNoProduction());
        assertEquals(1, report.mappedWithProduction());
        assertEquals(2, report.sourceCoverage().size());
        assertEquals(1, report.sourceCoverage().get("NFLVERSE").candidateObservations());
        assertEquals("2025-09-03", report.sourceCoverage().get("NFLVERSE").latestAsOf().toString());
        assertEquals(1, report.sourceCoverage().get("SLEEPER_PROVIDER_NATIVE").candidateObservations());

        var a = report.candidates().stream()
            .filter(candidate -> candidate.market().sleeperPlayerId().equals("A"))
            .findFirst().orElseThrow();
        var b = report.candidates().stream()
            .filter(candidate -> candidate.market().sleeperPlayerId().equals("B"))
            .findFirst().orElseThrow();
        var c = report.candidates().stream()
            .filter(candidate -> candidate.market().sleeperPlayerId().equals("C"))
            .findFirst().orElseThrow();

        assertEquals(SleeperLiveWaiverProductionCoverageAudit.CoverageState.MAPPED_WITH_2025_PRODUCTION, a.state());
        assertEquals(2, a.production().size());
        assertEquals("NFLVERSE", a.production().get(0).source());
        assertEquals("2025-09-03", a.production().get(0).asOfDate().toString());
        assertEquals(SleeperLiveWaiverProductionCoverageAudit.CoverageState.MAPPED_NO_2025_PRODUCTION, b.state());
        assertEquals(SleeperLiveWaiverProductionCoverageAudit.CoverageState.UNMAPPED_CANONICAL, c.state());
    }

    @Test
    void missingBf603SnapshotBlocksReadOnlyAudit() throws Exception {
        Database database = seededDatabase();
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new SleeperLiveWaiverProductionCoverageAudit(database).audit("L"));
        assertEquals("BF-604 BLOCKED: required evidence table missing: live_waiver_market_attention_snapshots", error.getMessage());
    }

    private Database seededDatabase() throws Exception {
        Database database = new Database(tempDir.resolve("butler-test.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("L", "S", "Test League", 2026));
        new PlayerRepository(database).save(new Player("PA", "A", "Alpha", "WR", "MIN"));
        new PlayerRepository(database).save(new Player("PB", "B", "Bravo", "RB", "CHI"));
        return database;
    }

    private static void seedEvidence(Database database) throws Exception {
        var waiverRepository = new LiveWaiverSnapshotRepository(database);
        waiverRepository.save(
            new LiveWaiverSnapshotRepository.Snapshot(
                "W", "L", "S", 2026, "in_season", 1, "SLEEPER_ACTIVE_NFL_PLAYERS",
                "bf601", "eligibility", Instant.parse("2026-09-08T01:40:00Z"),
                0, 4, 0, 0, 4, 4),
            List.of(
                free("A", "Alpha", "WR", "MIN"),
                free("B", "Bravo", "RB", "CHI"),
                free("C", "Charlie", "TE", "GB"),
                free("D", "Delta", "QB", "LV")));

        var marketRepository = new LiveWaiverMarketAttentionRepository(database);
        marketRepository.save(
            new LiveWaiverMarketAttentionRepository.Snapshot(
                "M", "L", "W", "S", 2026, "in_season", 1, "bf603",
                24, 200, Instant.parse("2026-09-08T01:42:00Z"),
                4, 2, 2, 1, 1, 1, 1),
            List.of(
                market("A", "Alpha", "WR", "MIN", 100, 20, "BOTH"),
                market("B", "Bravo", "RB", "CHI", 0, 90, "DROP_ONLY"),
                market("C", "Charlie", "TE", "GB", 50, 0, "ADD_ONLY"),
                market("D", "Delta", "QB", "LV", 0, 0, "NEITHER")));

        try (var connection = database.openConnection()) {
            insertProduction(connection, "P-OLD", "PA", "NFLVERSE", "2025-08-01", 4, 40, 400, 3);
            insertProduction(connection, "P-NFL", "PA", "NFLVERSE", "2025-09-03", 17, 120, 1100, 8);
            insertProduction(connection, "P-SLP", "PA", "SLEEPER_PROVIDER_NATIVE", "2025-09-02", 17, 118, 1090, 7);
        }
    }

    private static LiveWaiverSnapshotRepository.Entry free(String id, String name, String position, String team) {
        return new LiveWaiverSnapshotRepository.Entry(
            id, name, position, List.of(position), team, "Active",
            false, true, true, "LEAGUE_ELIGIBLE_POSITION_MATCH");
    }

    private static LiveWaiverMarketAttentionRepository.Entry market(
        String id, String name, String position, String team, int add, int drop, String membership) {
        return new LiveWaiverMarketAttentionRepository.Entry(
            id, name, position, team, "Active", add, drop, add - drop,
            membership.equals("ADD_ONLY") || membership.equals("BOTH"),
            membership.equals("DROP_ONLY") || membership.equals("BOTH"),
            membership);
    }

    private static void insertProduction(
        java.sql.Connection connection,
        String id,
        String playerId,
        String source,
        String asOf,
        int games,
        int receptions,
        int receivingYards,
        int receivingTds) throws Exception {
        try (var statement = connection.prepareStatement("""
            INSERT INTO player_season_production(
                id, player_id, season, games_played, passing_yards, passing_touchdowns, interceptions,
                rushing_yards, rushing_touchdowns, receptions, receiving_yards, receiving_touchdowns,
                fumbles_lost, source, as_of_date)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            statement.setString(1, id);
            statement.setString(2, playerId);
            statement.setInt(3, 2025);
            statement.setInt(4, games);
            statement.setInt(5, 0);
            statement.setInt(6, 0);
            statement.setInt(7, 0);
            statement.setInt(8, 0);
            statement.setInt(9, 0);
            statement.setInt(10, receptions);
            statement.setInt(11, receivingYards);
            statement.setInt(12, receivingTds);
            statement.setInt(13, 0);
            statement.setString(14, source);
            statement.setString(15, asOf);
            statement.executeUpdate();
        }
    }
}
