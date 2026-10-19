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
    void fractionalSourceFramesCannotBecomeCurrentWeekEvidence() throws Exception {
        for (String frame : new String[]{"\"season\":2026,\"week\":2.9",
                "\"season\":2026.9,\"week\":2",
                "\"season\":2026,\"week\":2.0000000000000001",
                "\"season\":2026.0000000000000001,\"week\":2",
                "\"season\":2147483648,\"week\":2",
                "\"season\":2026,\"week\":4294967298"}) {
            String json = "[{\"player_id\":\"valid\",\"season\":2026,\"week\":2,"
                + "\"stats\":{\"pts_ppr\":10}},"
                + "{\"player_id\":\"invalid-points\"," + frame + ",\"stats\":{\"pts_ppr\":99}},"
                + "{\"player_id\":\"invalid-raw\"," + frame + ",\"stats\":{\"rec\":99}},"
                + "{\"player_id\":\"invalid-gap\"," + frame + ",\"stats\":{\"adp_dd_ppr\":1000}}]";
            var snapshot = provider().parse(json, 2026, 2,
                SleeperWeeklyProjectionProvider.ScoringBasis.PPR, Map.of("rec", 1.0));
            assertEquals(1, snapshot.projections().size(), frame);
            assertEquals("valid", snapshot.projections().getFirst().sleeperPlayerId());
            assertTrue(snapshot.gaps().isEmpty(), frame);
            var legacy = provider().parse(json, 2026, 2,
                SleeperWeeklyProjectionProvider.ScoringBasis.PPR);
            assertEquals(1, legacy.projections().size(), frame);
            assertEquals("valid", legacy.projections().getFirst().sleeperPlayerId());
        }
    }

    @Test
    void exactWholeNumberAndTextFramesRemainUsable() throws Exception {
        var snapshot = provider().parse("""
            [{"player_id":"decimal","season":2026.0,"week":2.0,"stats":{"pts_ppr":10}},
             {"player_id":"text","season":"2026","week":"2","stats":{"rec":4}}]
            """, 2026, 2, SleeperWeeklyProjectionProvider.ScoringBasis.PPR, Map.of("rec", 1.0));
        assertEquals(2, snapshot.projections().size());
        assertTrue(snapshot.gaps().isEmpty());
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
    void adpOnlyCurrentWeekRowExplainsHoldWithoutInventingPoints() throws Exception {
        var snapshot = provider().parse("""
            [{"player_id":"s-rb","season":2026,"week":2,
              "season_type":"regular","stats":{"adp_dd_ppr":1000}}]
            """, 2026, 2, SleeperWeeklyProjectionProvider.ScoringBasis.PPR,
            Map.of("rush_yd", 0.1, "rec", 1.0));

        assertTrue(snapshot.projections().isEmpty());
        assertEquals(1, snapshot.gaps().size());
        var gap = snapshot.gaps().getFirst();
        assertEquals("s-rb", gap.sleeperPlayerId());
        assertTrue(gap.reason().contains("numeric raw fields=adp_dd_ppr=1000"));
        assertTrue(gap.reason().contains("ADP is average draft position, not projected fantasy points"));
        assertTrue(gap.reason().contains("Recheck current-week projection evidence"));
        assertTrue(gap.reason().contains("keep this player held"));
    }

    @Test
    void adpAlongsideScoreableEvidenceDoesNotCreateAHoldOrReplaceRealZero() throws Exception {
        var snapshot = provider().parse("""
            [{"player_id":"zero","season":2026,"week":2,
              "stats":{"adp_dd_ppr":1000,"pts_ppr":0}},
             {"player_id":"raw","season":2026,"week":2,
              "stats":{"adp_dd_ppr":1000,"rush_yd":40,"rec":2}}]
            """, 2026, 2, SleeperWeeklyProjectionProvider.ScoringBasis.PPR,
            Map.of("rush_yd", 0.1, "rec", 1.0));

        assertTrue(snapshot.gaps().isEmpty());
        assertEquals(2, snapshot.projections().size());
        var zero = snapshot.projections().stream()
            .filter(p -> p.sleeperPlayerId().equals("zero")).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(zero.projectedPoints()));
        assertEquals(SleeperWeeklyProjectionProvider.ProjectionProvenance.SLEEPER_PRECOMPUTED,
            zero.provenance());
        var raw = snapshot.projections().stream()
            .filter(p -> p.sleeperPlayerId().equals("raw")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("6").compareTo(raw.projectedPoints()));
        assertEquals(SleeperWeeklyProjectionProvider.ProjectionProvenance.SLEEPER_RAW_STATS_LEAGUE_SCORED,
            raw.provenance());
        assertTrue(raw.scoringKeys().stream().noneMatch(key -> key.startsWith("adp_")));
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
        assertTrue(snapshot.gaps().getFirst().reason().contains("numeric raw fields=snap_pct=0.72"));
    }

    @Test
    void projectionGapListsAllCurrentNumericRowFieldsWithoutScoringThem() throws Exception {
        String json = """
            [{
              "player_id":"s-rb",
              "season":2026,
              "week":2,
              "season_type":"regular",
              "stats":{"bonus":2,"snap_pct":0.72,"note":"x","usage":11}
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
        assertTrue(snapshot.gaps().getFirst().reason().contains(
            "numeric raw fields=bonus=2,snap_pct=0.72,usage=11"));
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
