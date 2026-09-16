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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveAutoFillLineupRecommendationBf800Test {
    @TempDir
    Path tempDir;

    @Test
    void composesExactLiveRosterAndWeeklyProjectionIntoReadOnlyRecommendation() throws Exception {
        Database database = initializedDatabase();
        String leagueId = "league-1";
        new LeagueRepository(database).save(new League(leagueId, "sleeper-league", "Test League", 2026));
        new LeagueScoringSettingsRepository(database).replace(leagueId, Map.of("rec", 1.0));
        savePlayer(database, "butler-qb", "s-qb", "Quarter Back", "QB", "CHI", List.of("QB"));
        savePlayer(database, "butler-wr-a", "s-wr-a", "Receiver A", "WR", "JAX", List.of("WR"));
        savePlayer(database, "butler-wr-b", "s-wr-b", "Receiver B", "WR", "WAS", List.of("WR"));

        var snapshot = new SleeperWeeklyProjectionProvider.ProjectionSnapshot(
            SleeperWeeklyProjectionProvider.SOURCE_NAME,
            "projections/nfl/2026/2?season_type=regular",
            2026,
            2,
            SleeperWeeklyProjectionProvider.ScoringBasis.PPR,
            List.of(
                projection("s-qb", "20"),
                projection("s-wr-a", "10"),
                projection("s-wr-b", "15")));

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot)
            .recommend(rosterReport(leagueId));

        assertTrue(report.ready());
        assertEquals(SleeperWeeklyProjectionProvider.ScoringBasis.PPR, report.scoringBasis());
        assertEquals(SleeperWeeklyProjectionProvider.SOURCE_NAME, report.sourceName());
        assertEquals(new BigDecimal("30"), report.currentProjectedTotal());
        assertEquals(new BigDecimal("5"), report.projectedGain());
        assertEquals(new BigDecimal("35"), report.recommendation().projectedTotal());
        assertEquals("s-wr-b", report.recommendation().assignments().get(1).recommendedPlayerId());
        assertEquals(List.of("s-wr-a"), report.recommendation().movesToBench().stream().map(player -> player.playerId()).toList());
        assertEquals(List.of("s-wr-b"), report.recommendation().promotions().stream().map(player -> player.playerId()).toList());
    }

    @Test
    void duplicateProjectionIdentityFailsClosed() throws Exception {
        Database database = initializedDatabase();
        String leagueId = "league-2";
        new LeagueRepository(database).save(new League(leagueId, "sleeper-league-2", "Test League 2", 2026));
        new LeagueScoringSettingsRepository(database).replace(leagueId, Map.of("rec", 1.0));
        savePlayer(database, "butler-qb", "s-qb", "Quarter Back", "QB", "CHI", List.of("QB"));
        savePlayer(database, "butler-wr-a", "s-wr-a", "Receiver A", "WR", "JAX", List.of("WR"));
        savePlayer(database, "butler-wr-b", "s-wr-b", "Receiver B", "WR", "WAS", List.of("WR"));

        var snapshot = new SleeperWeeklyProjectionProvider.ProjectionSnapshot(
            SleeperWeeklyProjectionProvider.SOURCE_NAME,
            "surface",
            2026,
            2,
            SleeperWeeklyProjectionProvider.ScoringBasis.PPR,
            List.of(
                projection("s-qb", "20"),
                projection("s-qb", "19"),
                projection("s-wr-a", "10"),
                projection("s-wr-b", "15")));

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot)
            .recommend(rosterReport(leagueId));

        assertFalse(report.ready());
        assertTrue(report.reason().contains("duplicate Sleeper player id"));
        assertTrue(report.reason().contains("will not guess"));
    }

    @Test
    void missingExactSleeperProjectionIdentityFailsClosed() throws Exception {
        Database database = initializedDatabase();
        String leagueId = "league-3";
        new LeagueRepository(database).save(new League(leagueId, "sleeper-league-3", "Test League 3", 2026));
        new LeagueScoringSettingsRepository(database).replace(leagueId, Map.of("rec", 1.0));
        savePlayer(database, "butler-qb", "s-qb", "Quarter Back", "QB", "CHI", List.of("QB"));
        savePlayer(database, "butler-wr-a", "s-wr-a", "Receiver A", "WR", "JAX", List.of("WR"));
        savePlayer(database, "butler-wr-b", "s-wr-b", "Receiver B", "WR", "WAS", List.of("WR"));

        var snapshot = new SleeperWeeklyProjectionProvider.ProjectionSnapshot(
            SleeperWeeklyProjectionProvider.SOURCE_NAME,
            "surface",
            2026,
            2,
            SleeperWeeklyProjectionProvider.ScoringBasis.PPR,
            List.of(projection("s-qb", "20"), projection("s-wr-a", "10")));

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot)
            .recommend(rosterReport(leagueId));

        assertFalse(report.ready());
        assertTrue(report.reason().contains("no exact Sleeper player-id match"));
        assertTrue(report.reason().contains("will not guess"));
    }

    private Database initializedDatabase() throws Exception {
        Database database = new Database(tempDir.resolve("butler.db"));
        database.initialize();
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
            "sleeper-league",
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
