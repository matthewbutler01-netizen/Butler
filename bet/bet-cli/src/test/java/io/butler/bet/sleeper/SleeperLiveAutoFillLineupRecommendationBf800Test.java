package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.PlayerFantasyPositionRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.Player;
import io.butler.bet.integration.FantasyProsWeeklyProjectionProvider;
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
    void composesExactLiveRosterAndFantasyProsProjectionIntoReadOnlyRecommendation() throws Exception {
        Database database = initializedDatabase();
        String leagueId = "league-1";
        new LeagueRepository(database).save(new League(leagueId, "sleeper-league", "Test League", 2026));
        new LeagueScoringSettingsRepository(database).replace(leagueId, Map.of("rec", 1.0));
        savePlayer(database, "butler-qb", "s-qb", "Quarter Back", "QB", "CHI", List.of("QB"));
        savePlayer(database, "butler-wr-a", "s-wr-a", "Receiver A", "WR", "JAX", List.of("WR"));
        savePlayer(database, "butler-wr-b", "s-wr-b", "Receiver B", "WR", "WAS", List.of("WR"));

        var snapshot = new FantasyProsWeeklyProjectionProvider.ProjectionSnapshot(
            FantasyProsWeeklyProjectionProvider.SOURCE_NAME,
            "nfl/2026/projections?week=2&scoring=PPR",
            2026,
            2,
            FantasyProsWeeklyProjectionProvider.ScoringBasis.PPR,
            List.of(
                projection("1", "Quarter Back", "QB", "CHI", "20"),
                projection("2", "Receiver A", "WR", "JAC", "10"),
                projection("3", "Receiver B", "WR", "WSH", "15")));

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot)
            .recommend(rosterReport(leagueId));

        assertTrue(report.ready());
        assertEquals(FantasyProsWeeklyProjectionProvider.ScoringBasis.PPR, report.scoringBasis());
        assertEquals(new BigDecimal("30"), report.currentProjectedTotal());
        assertEquals(new BigDecimal("5"), report.projectedGain());
        assertEquals(new BigDecimal("35"), report.recommendation().projectedTotal());
        assertEquals("s-wr-b", report.recommendation().assignments().get(1).recommendedPlayerId());
        assertEquals(List.of("s-wr-a"), report.recommendation().movesToBench().stream().map(player -> player.playerId()).toList());
        assertEquals(List.of("s-wr-b"), report.recommendation().promotions().stream().map(player -> player.playerId()).toList());
    }

    @Test
    void ambiguousProjectionIdentityFailsClosed() throws Exception {
        Database database = initializedDatabase();
        String leagueId = "league-2";
        new LeagueRepository(database).save(new League(leagueId, "sleeper-league-2", "Test League 2", 2026));
        new LeagueScoringSettingsRepository(database).replace(leagueId, Map.of("rec", 1.0));
        savePlayer(database, "butler-qb", "s-qb", "D.J. Example", "QB", "CHI", List.of("QB"));
        savePlayer(database, "butler-wr-a", "s-wr-a", "Receiver A", "WR", "JAX", List.of("WR"));
        savePlayer(database, "butler-wr-b", "s-wr-b", "Receiver B", "WR", "WAS", List.of("WR"));

        var snapshot = new FantasyProsWeeklyProjectionProvider.ProjectionSnapshot(
            FantasyProsWeeklyProjectionProvider.SOURCE_NAME,
            "surface",
            2026,
            2,
            FantasyProsWeeklyProjectionProvider.ScoringBasis.PPR,
            List.of(
                projection("1", "DJ Example", "QB", "CHI", "20"),
                projection("9", "D.J. Example", "QB", "CHI", "19"),
                projection("2", "Receiver A", "WR", "JAX", "10"),
                projection("3", "Receiver B", "WR", "WAS", "15")));

        var base = rosterReport(leagueId);
        var alteredPlayers = List.of(
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "s-qb", "STARTER", 0, "QB", "butler-qb", "D.J. Example", "QB", "CHI", "EXACT_CANONICAL"),
            base.targetPlayers().get(1),
            base.targetPlayers().get(2));
        var altered = copyWithPlayers(base, alteredPlayers);

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot)
            .recommend(altered);

        assertFalse(report.ready());
        assertTrue(report.reason().contains("ambiguous"));
        assertTrue(report.reason().contains("will not guess"));
    }

    @Test
    void nameAndTeamNormalizationAreDeterministicNotFuzzy() {
        assertEquals("djmoore", SleeperLiveAutoFillLineupRecommendation.normalizeName("D.J. Moore"));
        assertEquals("djmoore", SleeperLiveAutoFillLineupRecommendation.normalizeName("DJ Moore"));
        assertEquals("JAX", SleeperLiveAutoFillLineupRecommendation.normalizeTeam("JAC"));
        assertEquals("WAS", SleeperLiveAutoFillLineupRecommendation.normalizeTeam("WSH"));
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

    private static FantasyProsWeeklyProjectionProvider.Projection projection(
        String id, String name, String position, String team, String points) {
        return new FantasyProsWeeklyProjectionProvider.Projection(
            id, name, position, team, new BigDecimal(points));
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

    private static SleeperLiveWaiverTargetRosterContextAudit.AuditReport copyWithPlayers(
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport source,
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players) {
        return new SleeperLiveWaiverTargetRosterContextAudit.AuditReport(
            source.policyId(), source.leagueId(), source.marketSnapshotId(), source.waiverSnapshotId(),
            source.sleeperLeagueId(), source.providerSeason(), source.providerStatus(), source.providerLeg(),
            source.sleeperOwnerId(), source.ownerDisplayName(), source.ownerTeamName(), source.rosterId(),
            source.butlerTeamId(), source.butlerTeamName(), source.lineupSlots(), source.startingSlots(),
            source.candidateCount(), source.reviewableCandidateCount(), source.targetPlayerCount(),
            source.starterCount(), source.benchCount(), source.reserveCount(), source.taxiCount(),
            source.exactMappedTargetPlayers(), source.unmappedTargetPlayers(), players);
    }
}
