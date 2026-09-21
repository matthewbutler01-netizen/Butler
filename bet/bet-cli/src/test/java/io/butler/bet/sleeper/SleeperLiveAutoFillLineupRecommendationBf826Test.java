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

class SleeperLiveAutoFillLineupRecommendationBf826Test {
    private static final Instant OBSERVED_AT = Instant.parse("2026-09-17T06:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void recommendPassesPersistedLeagueScoringToBf826ProjectionSource() throws Exception {
        Database database = initializedDatabase("league-score-wire");
        var snapshot = snapshot(List.of(
            rawProjection("s-qb", "20", List.of("pass_yd", "pass_td")),
            rawProjection("s-wr-a", "10", List.of("rec", "rec_yd")),
            rawProjection("s-wr-b", "15", List.of("rec", "rec_yd"))), List.of());

        final boolean[] fourArgCalled = {false};
        SleeperLiveAutoFillLineupRecommendation.ProjectionSource source =
            new SleeperLiveAutoFillLineupRecommendation.ProjectionSource() {
                @Override
                public SleeperWeeklyProjectionProvider.ProjectionSnapshot load(
                    int season,
                    int week,
                    SleeperWeeklyProjectionProvider.ScoringBasis scoring) throws IOException {
                    throw new AssertionError("BF-826 production composition must use persisted league scoring");
                }

                @Override
                public SleeperWeeklyProjectionProvider.ProjectionSnapshot load(
                    int season,
                    int week,
                    SleeperWeeklyProjectionProvider.ScoringBasis scoring,
                    Map<String, Double> leagueScoringSettings) {
                    fourArgCalled[0] = true;
                    assertEquals(1.0, leagueScoringSettings.get("rec"));
                    assertEquals(0.1, leagueScoringSettings.get("rec_yd"));
                    return snapshot;
                }
            };

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            source,
            ids -> Map.of())
            .recommend(rosterReport("league-score-wire"));

        assertTrue(fourArgCalled[0]);
        assertTrue(report.ready());
        assertTrue(report.projectionProvenance().contains("exact Sleeper raw projected stats"));
    }

    @Test
    void exactProjectionRowGapBecomesExplicitProjectionHoldWithoutIdentityGuessing() throws Exception {
        Database database = initializedDatabase("league-gap");
        var snapshot = snapshot(
            List.of(projection("s-qb", "20"), projection("s-wr-b", "15")),
            List.of(new SleeperWeeklyProjectionProvider.ProjectionGap(
                "s-wr-a",
                "exact Sleeper projection row is present but pts_ppr is missing/non-numeric; raw projected stats are incomplete")));

        var report = new SleeperLiveAutoFillLineupRecommendation(
            database,
            (season, week, scoring) -> snapshot,
            ids -> Map.of(
                "s-wr-a",
                new SleeperPlayerAvailabilityProvider.PlayerAvailability("s-wr-a", "Active", null)))
            .recommend(rosterReport("league-gap"));

        assertTrue(report.ready());
        assertEquals(1, report.projectionHolds().size());
        String reason = report.projectionHolds().getFirst().reason();
        assertTrue(reason.contains("exact Sleeper player-id row"));
        assertTrue(reason.contains("not scoreable"));
        assertTrue(reason.contains("raw projected stats are incomplete"));
        assertFalse(reason.contains("no exact Sleeper player-id row"));
        assertTrue(reason.contains("does not explicitly prove unavailable"));
    }

    private Database initializedDatabase(String leagueId) throws Exception {
        Database database = new Database(tempDir.resolve(leagueId + ".db"));
        database.initialize();
        new LeagueRepository(database).save(new League(leagueId, "sleeper-" + leagueId, "Test League", 2026));
        new LeagueScoringSettingsRepository(database).replace(
            leagueId,
            Map.of("rec", 1.0, "rec_yd", 0.1, "pass_yd", 0.04, "pass_td", 4.0));
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

    private static SleeperWeeklyProjectionProvider.Projection projection(String id, String points) {
        return new SleeperWeeklyProjectionProvider.Projection(id, new BigDecimal(points));
    }

    private static SleeperWeeklyProjectionProvider.Projection rawProjection(
        String id,
        String points,
        List<String> keys) {
        return new SleeperWeeklyProjectionProvider.Projection(
            id,
            new BigDecimal(points),
            SleeperWeeklyProjectionProvider.ProjectionProvenance.SLEEPER_RAW_STATS_LEAGUE_SCORED,
            keys);
    }

    private static SleeperWeeklyProjectionProvider.ProjectionSnapshot snapshot(
        List<SleeperWeeklyProjectionProvider.Projection> projections,
        List<SleeperWeeklyProjectionProvider.ProjectionGap> gaps) {
        return new SleeperWeeklyProjectionProvider.ProjectionSnapshot(
            SleeperWeeklyProjectionProvider.SOURCE_NAME,
            "projections/nfl/2026/2?season_type=regular",
            2026,
            2,
            SleeperWeeklyProjectionProvider.ScoringBasis.PPR,
            OBSERVED_AT,
            projections,
            gaps);
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
