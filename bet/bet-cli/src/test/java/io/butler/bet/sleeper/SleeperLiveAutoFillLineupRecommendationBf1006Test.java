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

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveAutoFillLineupRecommendationBf1006Test {
    private static final Instant OBSERVED = Instant.parse("2026-10-03T06:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void activePlusOutProjectionGapIsUnavailableInsteadOfHeld() throws Exception {
        Database database = initializedDatabase("bf1006-out-gap");
        var projections = snapshot(
            List.of(projection("s-qb", "20"), projection("s-wr-b", "15")),
            List.of(new SleeperWeeklyProjectionProvider.ProjectionGap(
                "s-wr-a", "exact projection row is present but not scoreable")));

        var report = recommendation(
            database,
            projections,
            ids -> Map.of(
                "s-wr-a", new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-a", "Active", "Out")),
            allUnlocked()).recommend(rosterReport("bf1006-out-gap"));

        assertTrue(report.ready());
        assertEquals(1, report.availabilityExclusions().size());
        assertEquals("s-wr-a", report.availabilityExclusions().getFirst().sleeperPlayerId());
        assertTrue(report.availabilityExclusions().getFirst().reason().contains("confirms unavailable status"));
        assertFalse(report.projectionHolds().stream()
            .anyMatch(hold -> "s-wr-a".equals(hold.sleeperPlayerId())));
        assertTrue(report.recommendation().promotions().stream()
            .anyMatch(player -> "s-wr-b".equals(player.playerId())));
    }

    @Test
    void lockedBenchPlayerCannotBePromoted() throws Exception {
        Database database = initializedDatabase("bf1006-locked-bench");
        var projections = snapshot(
            List.of(projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "15")),
            List.of());

        var report = recommendation(
            database,
            projections,
            ids -> Map.of(),
            locks("s-wr-b")).recommend(rosterReport("bf1006-locked-bench"));

        assertTrue(report.ready());
        assertTrue(report.recommendation().promotions().isEmpty());
        assertEquals(BigDecimal.ZERO, report.projectedGain());
        assertTrue(report.projectionHolds().stream().anyMatch(hold ->
            "s-wr-b".equals(hold.sleeperPlayerId()) && hold.reason().contains("Game locked")));
    }

    @Test
    void lockedStarterDoesNotGenerateReplacementCandidates() throws Exception {
        Database database = initializedDatabase("bf1006-locked-starter");
        var projections = snapshot(
            List.of(projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "15")),
            List.of());

        var report = recommendation(
            database,
            projections,
            ids -> Map.of(),
            locks("s-wr-a")).recommend(rosterReport("bf1006-locked-starter"));

        assertTrue(report.ready());
        assertTrue(report.recommendation().promotions().isEmpty());
        assertTrue(report.decisionEvidence().stream().anyMatch(text ->
            text.contains("Game-lock review for Receiver A")));
        assertFalse(report.swapReviews().stream().anyMatch(review ->
            "MANUAL_REVIEW_REPLACEMENT".equals(review.status())
                && "Receiver A".equals(review.current())));
    }

    @Test
    void subOnePointTotalEdgeIsWithheldWithoutHardLegalityNeed() throws Exception {
        Database database = initializedDatabase("bf1006-small-edge");
        var projections = snapshot(
            List.of(projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "10.49")),
            List.of());

        var report = recommendation(
            database,
            projections,
            ids -> Map.of(),
            allUnlocked()).recommend(rosterReport("bf1006-small-edge"));

        assertTrue(report.ready());
        assertTrue(report.recommendation().promotions().isEmpty());
        assertEquals(BigDecimal.ZERO, report.projectedGain());
        assertTrue(report.projectionHolds().stream().anyMatch(hold ->
            "s-wr-b".equals(hold.sleeperPlayerId())
                && hold.reason().contains("below the 1.0-point action threshold")));
        assertTrue(report.swapReviews().stream().anyMatch(review ->
            "WITHHELD_SMALL_EDGE".equals(review.status())));
    }

    @Test
    void smallEdgeDoesNotBlockReplacingConfirmedOutStarter() throws Exception {
        Database database = initializedDatabase("bf1006-out-small-edge");
        var projections = snapshot(
            List.of(projection("s-qb", "20"), projection("s-wr-a", "10"), projection("s-wr-b", "0.49")),
            List.of());

        var report = recommendation(
            database,
            projections,
            ids -> Map.of(
                "s-wr-a", new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-a", "Active", "Out")),
            allUnlocked()).recommend(rosterReport("bf1006-out-small-edge"));

        assertTrue(report.ready());
        assertEquals(new BigDecimal("0.49"), report.projectedGain());
        assertTrue(report.recommendation().promotions().stream()
            .anyMatch(player -> "s-wr-b".equals(player.playerId())));
        assertFalse(report.projectionHolds().stream().anyMatch(hold ->
            "s-wr-b".equals(hold.sleeperPlayerId())
                && hold.reason().contains("1.0-point action threshold")));
    }

    private SleeperLiveAutoFillLineupRecommendation recommendation(
        Database database,
        SleeperWeeklyProjectionProvider.ProjectionSnapshot snapshot,
        SleeperLiveAutoFillLineupRecommendation.AvailabilitySource availability,
        SleeperLiveAutoFillLineupRecommendation.GameLockSource locks) {

        return new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot,
            availability,
            players -> Map.of(),
            players -> Map.of(),
            (season, week, ids) -> Map.of(),
            (season, week, players) -> Map.of(),
            (season, week, players) -> List.of(),
            locks);
    }

    private static SleeperLiveAutoFillLineupRecommendation.GameLockSource allUnlocked() {
        return (season, week, players) -> {
            Map<String, NflverseGameLockProvider.GameLockEvidence> result = new LinkedHashMap<>();
            for (var player : players) {
                result.put(player.sleeperPlayerId(), lock(false));
            }
            return Map.copyOf(result);
        };
    }

    private static SleeperLiveAutoFillLineupRecommendation.GameLockSource locks(String lockedId) {
        return (season, week, players) -> {
            Map<String, NflverseGameLockProvider.GameLockEvidence> result = new LinkedHashMap<>();
            for (var player : players) {
                result.put(player.sleeperPlayerId(), lock(player.sleeperPlayerId().equals(lockedId)));
            }
            return Map.copyOf(result);
        };
    }

    private static NflverseGameLockProvider.GameLockEvidence lock(boolean locked) {
        return new NflverseGameLockProvider.GameLockEvidence(
            true,
            locked,
            Instant.parse("2026-10-02T00:15:00Z"),
            locked ? "fixture kickoff already occurred" : "fixture kickoff not locked");
    }

    private Database initializedDatabase(String leagueId) throws Exception {
        Database database = new Database(tempDir.resolve(leagueId + ".db"));
        database.initialize();
        new LeagueRepository(database).save(new League(leagueId, "sleeper-" + leagueId, "Test League", 2026));
        new LeagueScoringSettingsRepository(database).replace(leagueId, Map.of("rec", 1.0));
        savePlayer(database, "butler-qb", "s-qb", "Quarter Back", "QB", "CHI", List.of("QB"));
        savePlayer(database, "butler-wr-a", "s-wr-a", "Receiver A", "WR", "CLE", List.of("WR"));
        savePlayer(database, "butler-wr-b", "s-wr-b", "Receiver B", "WR", "KC", List.of("WR"));
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
        List<SleeperWeeklyProjectionProvider.Projection> projections,
        List<SleeperWeeklyProjectionProvider.ProjectionGap> gaps) {

        return new SleeperWeeklyProjectionProvider.ProjectionSnapshot(
            SleeperWeeklyProjectionProvider.SOURCE_NAME,
            "projections/nfl/2026/4?season_type=regular",
            2026,
            4,
            SleeperWeeklyProjectionProvider.ScoringBasis.PPR,
            OBSERVED,
            projections,
            gaps);
    }

    private static SleeperWeeklyProjectionProvider.Projection projection(String id, String points) {
        return new SleeperWeeklyProjectionProvider.Projection(id, new BigDecimal(points));
    }

    private static SleeperLiveWaiverTargetRosterContextAudit.AuditReport rosterReport(String leagueId) {
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players = List.of(
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "s-qb", "STARTER", 0, "QB", "butler-qb", "Quarter Back", "QB", "CHI", "EXACT_CANONICAL"),
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "s-wr-a", "STARTER", 1, "WR", "butler-wr-a", "Receiver A", "WR", "CLE", "EXACT_CANONICAL"),
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "s-wr-b", "BENCH", null, null, "butler-wr-b", "Receiver B", "WR", "KC", "EXACT_CANONICAL"));

        return new SleeperLiveWaiverTargetRosterContextAudit.AuditReport(
            SleeperLiveWaiverTargetRosterContextAudit.POLICY_ID,
            leagueId,
            "market",
            "waiver",
            "sleeper-" + leagueId,
            2026,
            "in_season",
            4,
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
