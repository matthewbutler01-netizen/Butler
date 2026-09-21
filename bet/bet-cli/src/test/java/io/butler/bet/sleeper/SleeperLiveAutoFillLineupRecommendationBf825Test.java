package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.PlayerFantasyPositionRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.integration.SleeperWeeklyProjectionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveAutoFillLineupRecommendationBf825Test {
    private static final Instant PROJECTION_OBSERVED_AT = Instant.parse("2026-09-17T04:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void benchExplicitlyUnavailableWithoutProjectionDoesNotBlockRecommendation() throws Exception {
        Database database = initializedDatabase("league-bench-out");
        var snapshot = snapshot(List.of(projection("s-qb", "20"), projection("s-wr-a", "10")));

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot,
            ids -> Map.of(
                "s-wr-b",
                new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-b", "Inactive", null)))
            .recommend(rosterReport("league-bench-out"));

        assertTrue(report.ready());
        assertEquals(new BigDecimal("30"), report.currentProjectedTotal());
        assertEquals(new BigDecimal("30"), report.recommendation().projectedTotal());
        assertEquals(BigDecimal.ZERO, report.projectedGain());
        assertTrue(report.recommendation().promotions().isEmpty());
        assertEquals(1, report.availabilityExclusions().size());
        assertEquals("s-wr-b", report.availabilityExclusions().getFirst().sleeperPlayerId());
        assertTrue(report.availabilityExclusions().getFirst().reason().contains("did not synthesize a zero projection"));
    }

    @Test
    void starterExplicitlyUnavailableWithoutProjectionIsReplacedByEligibleBenchPlayer() throws Exception {
        Database database = initializedDatabase("league-starter-out");
        var snapshot = snapshot(List.of(projection("s-qb", "20"), projection("s-wr-b", "15")));

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot,
            ids -> Map.of(
                "s-wr-a",
                new SleeperPlayerAvailabilityProvider.PlayerAvailability(
                    "s-wr-a", "Commissioner Exempt", null)))
            .recommend(rosterReport("league-starter-out"));

        assertTrue(report.ready());
        assertEquals(new BigDecimal("20"), report.currentProjectedTotal());
        assertEquals(new BigDecimal("35"), report.recommendation().projectedTotal());
        assertEquals(new BigDecimal("15"), report.projectedGain());
        assertEquals("s-wr-b", report.recommendation().assignments().get(1).recommendedPlayerId());
        assertEquals(List.of("s-wr-a"), report.recommendation().movesToBench().stream()
            .map(player -> player.playerId()).toList());
        assertEquals(List.of("s-wr-b"), report.recommendation().promotions().stream()
            .map(player -> player.playerId()).toList());
        assertEquals("Commissioner Exempt", report.availabilityExclusions().getFirst().status());
    }

    @Test
    void ambiguousAvailabilityCreatesProjectionHoldInsteadOfBlockingOtherScoreableSlots() throws Exception {
        Database database = initializedDatabase("league-ambiguous");
        var snapshot = snapshot(List.of(projection("s-qb", "20"), projection("s-wr-b", "15")));

        List<SleeperPlayerAvailabilityProvider.PlayerAvailability> ambiguous = List.of(
            new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-a", "Active", null),
            new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-a", null, "Questionable"),
            new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-a", null, "Doubtful"),
            new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-a", "Unknown", null),
            new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-a", "Active", "Out"));

        for (var availability : ambiguous) {
            var report = new SleeperLiveAutoFillLineupRecommendation(
                database,
                (season, week, scoring) -> snapshot,
                ids -> Map.of("s-wr-a", availability))
                .recommend(rosterReport("league-ambiguous"));

            assertTrue(report.ready());
            assertEquals(1, report.projectionHolds().size());
            assertEquals("s-wr-a", report.projectionHolds().getFirst().sleeperPlayerId());
            assertTrue(report.projectionHolds().getFirst().reason().contains("preserved the player's current lineup state"));
            assertEquals(new BigDecimal("20"), report.currentProjectedTotal());
            assertEquals(new BigDecimal("20"), report.recommendation().projectedTotal());
            assertEquals(BigDecimal.ZERO, report.projectedGain());
        }
    }

    @Test
    void availabilityProviderFailureCreatesProjectionHoldInsteadOfBlockingReview() throws Exception {
        Database database = initializedDatabase("league-provider-failure");
        var snapshot = snapshot(List.of(projection("s-qb", "20"), projection("s-wr-b", "15")));

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot,
            ids -> { throw new IOException("provider down"); })
            .recommend(rosterReport("league-provider-failure"));

        assertTrue(report.ready());
        assertEquals(1, report.projectionHolds().size());
        assertTrue(report.projectionHolds().getFirst().reason().contains("availability evidence is unavailable"));
        assertTrue(report.projectionHolds().getFirst().reason().contains("provider down"));
        assertTrue(report.projectionHolds().getFirst().reason().contains("preserved the player's current lineup state"));
    }

    @Test
    void missingExactAvailabilityCreatesProjectionHoldWithoutUsingWrongIdentity() throws Exception {
        Database database = initializedDatabase("league-exact-id");
        var snapshot = snapshot(List.of(projection("s-qb", "20"), projection("s-wr-b", "15")));

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot,
            ids -> Map.of(
                "wrong-id",
                new SleeperPlayerAvailabilityProvider.PlayerAvailability("wrong-id", "Inactive", null)))
            .recommend(rosterReport("league-exact-id"));

        assertTrue(report.ready());
        assertEquals(1, report.projectionHolds().size());
        assertEquals("s-wr-a", report.projectionHolds().getFirst().sleeperPlayerId());
        assertTrue(report.projectionHolds().getFirst().reason().contains("availability evidence has no exact match"));
        assertFalse(report.projectionHolds().getFirst().reason().contains("wrong-id"));
    }

    private Database initializedDatabase(String leagueId) throws Exception {
        Database database = new Database(tempDir.resolve(leagueId + ".db"));
        database.initialize();
        new LeagueRepository(database).save(new League(leagueId, "sleeper-" + leagueId, "Test League", 2026));
        new LeagueScoringSettingsRepository(database).replace(leagueId, Map.of("rec", 1.0));
        savePlayer(database, "butler-qb", "s-qb", "Quarter Back", "QB", "CHI", List.of("QB"));
        savePlayer(database, "butler-wr-a", "s-wr-a", "Receiver A", "WR", "JAX", List.of("WR"));
        savePlayer(database, "butler-wr-b", "s-wr-b", "Receiver B", "WR", "WAS", List.of("WR"));
        return database;
    }

    private static void savePlayer(
        Database database,
        String butlerId,
        String sleeperId,
        String name,
        String position,
        String team,
        List<String> fantasyPositions) throws Exception {
        new PlayerRepository(database).save(new Player(butlerId, sleeperId, name, position, team));
        new PlayerFantasyPositionRepository(database).replace(butlerId, fantasyPositions);
    }

    private static SleeperWeeklyProjectionProvider.ProjectionSnapshot snapshot(
        List<SleeperWeeklyProjectionProvider.Projection> projections) {
        return new SleeperWeeklyProjectionProvider.ProjectionSnapshot(
            SleeperWeeklyProjectionProvider.SOURCE_NAME,
            "projections/nfl/2026/2?season_type=regular",
            2026,
            2,
            SleeperWeeklyProjectionProvider.ScoringBasis.PPR,
            PROJECTION_OBSERVED_AT,
            projections);
    }

    private static SleeperWeeklyProjectionProvider.Projection projection(String id, String points) {
        return new SleeperWeeklyProjectionProvider.Projection(id, new BigDecimal(points));
    }

    private static SleeperLiveWaiverTargetRosterContextAudit.AuditReport rosterReport(String leagueId) {
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players = List.of(
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "s-qb", "STARTER", 0, "QB", "butler-qb", "Quarter Back", "QB", "CHI", "EXACT_CANONICAL"),
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "s-wr-a", "STARTER", 1, "WR", "butler-wr-a", "Receiver A", "WR", "JAX", "EXACT_CANONICAL"),
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "s-wr-b", "BENCH", null, null, "butler-wr-b", "Receiver B", "WR", "WAS", "EXACT_CANONICAL"));
        return new SleeperLiveWaiverTargetRosterContextAudit.AuditReport(
            SleeperLiveWaiverTargetRosterContextAudit.POLICY_ID,
            leagueId,
            "market",
            "waiver",
            "sleeper-" + leagueId,
            2026,
            "in_season",
            2,
            "owner",
            "Owner",
            "Team",
            1,
            "butler-team",
            "Team",
            List.of("QB", "WR", "BN"),
            List.of("QB", "WR"),
            1,
            1,
            3,
            2,
            1,
            0,
            0,
            3,
            0,
            players);
    }
}
