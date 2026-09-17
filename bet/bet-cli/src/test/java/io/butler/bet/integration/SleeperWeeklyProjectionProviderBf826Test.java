package io.butler.bet.integration;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperWeeklyProjectionProviderBf826Test {
    private static final Instant NOW = Instant.parse("2026-09-17T06:00:00Z");

    private SleeperWeeklyProjectionProvider provider() {
        return new SleeperWeeklyProjectionProvider(
            (season, week) -> "[]",
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void precomputedPprRemainsPrimaryWhenPresent() throws Exception {
        String json = """
            [{
              "player_id":"s-rb",
              "season":2026,
              "week":2,
              "season_type":"regular",
              "stats":{"pts_ppr":19.5,"rush_yd":80,"rush_td":1,"rec":4,"rec_yd":30}
            }]
            """;

        var snapshot = provider().parse(
            json,
            2026,
            2,
            SleeperWeeklyProjectionProvider.ScoringBasis.PPR,
            Map.of("rush_yd", 0.1, "rush_td", 6.0, "rec", 1.0, "rec_yd", 0.1));

        assertEquals(1, snapshot.projections().size());
        var projection = snapshot.projections().getFirst();
        assertEquals(new BigDecimal("19.5"), projection.projectedPoints());
        assertEquals(
            SleeperWeeklyProjectionProvider.ProjectionProvenance.SLEEPER_PRECOMPUTED,
            projection.provenance());
        assertEquals(0, snapshot.gaps().size());
    }

    @Test
    void exactRawStatsCanBeLeagueScoredWhenPrecomputedPprIsMissing() throws Exception {
        String json = """
            [{
              "player_id":"s-rb",
              "season":2026,
              "week":2,
              "season_type":"regular",
              "stats":{"rush_yd":80,"rush_td":1,"rec":4,"rec_yd":30}
            }]
            """;

        var snapshot = provider().parse(
            json,
            2026,
            2,
            SleeperWeeklyProjectionProvider.ScoringBasis.PPR,
            Map.of("rush_yd", 0.1, "rush_td", 6.0, "rec", 1.0, "rec_yd", 0.1));

        assertEquals(1, snapshot.projections().size());
        var projection = snapshot.projections().getFirst();
        assertEquals(0, new BigDecimal("21").compareTo(projection.projectedPoints()));
        assertEquals(
            SleeperWeeklyProjectionProvider.ProjectionProvenance.SLEEPER_RAW_STATS_LEAGUE_SCORED,
            projection.provenance());
        assertTrue(projection.scoringKeys().contains("rush_yd"));
        assertTrue(projection.scoringKeys().contains("rush_td"));
        assertTrue(projection.scoringKeys().contains("rec"));
        assertTrue(projection.scoringKeys().contains("rec_yd"));
        assertEquals(0, snapshot.gaps().size());
    }

    @Test
    void exactRowWithNoMatchingRawScoringEvidencePreservesCoverageGap() throws Exception {
        String json = """
            [{
              "player_id":"s-rb",
              "season":2026,
              "week":2,
              "season_type":"regular",
              "stats":{"snap_pct":0.72}
            }]
            """;

        var snapshot = provider().parse(
            json,
            2026,
            2,
            SleeperWeeklyProjectionProvider.ScoringBasis.PPR,
            Map.of("rush_yd", 0.1, "rec", 1.0));

        assertEquals(0, snapshot.projections().size());
        assertEquals(1, snapshot.gaps().size());
        assertEquals("s-rb", snapshot.gaps().getFirst().sleeperPlayerId());
        assertTrue(snapshot.gaps().getFirst().reason().contains("exact Sleeper projection row is present"));
        assertTrue(snapshot.gaps().getFirst().reason().contains("no numeric fields matching"));
    }

    @Test
    void nonNumericMatchingRawStatFailsClosedIntoCoverageGap() throws Exception {
        String json = """
            [{
              "player_id":"s-rb",
              "season":2026,
              "week":2,
              "season_type":"regular",
              "stats":{"rush_yd":"unknown","rec":4}
            }]
            """;

        var snapshot = provider().parse(
            json,
            2026,
            2,
            SleeperWeeklyProjectionProvider.ScoringBasis.PPR,
            Map.of("rush_yd", 0.1, "rec", 1.0));

        assertEquals(0, snapshot.projections().size());
        assertEquals(1, snapshot.gaps().size());
        assertTrue(snapshot.gaps().getFirst().reason().contains("rush_yd is non-numeric"));
    }

    @Test
    void bf822CompatibilityPathDoesNotLeagueScoreRawStats() {
        String json = """
            [{
              "player_id":"s-rb",
              "season":2026,
              "week":2,
              "season_type":"regular",
              "stats":{"rush_yd":80,"rush_td":1,"rec":4,"rec_yd":30}
            }]
            """;

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> provider().parse(
                json,
                2026,
                2,
                SleeperWeeklyProjectionProvider.ScoringBasis.PPR));

        assertTrue(error.getMessage().contains("no usable pts_ppr evidence"));
    }

    @Test
    void duplicateExactPlayerIdentityStillFailsClosed() {
        String json = """
            [
              {"player_id":"s-rb","season":2026,"week":2,"stats":{"pts_ppr":10}},
              {"player_id":"s-rb","season":2026,"week":2,"stats":{"pts_ppr":11}}
            ]
            """;

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> provider().parse(
                json,
                2026,
                2,
                SleeperWeeklyProjectionProvider.ScoringBasis.PPR,
                Map.of("rec", 1.0)));

        assertTrue(error.getMessage().contains("duplicate player_id s-rb"));
    }
}
