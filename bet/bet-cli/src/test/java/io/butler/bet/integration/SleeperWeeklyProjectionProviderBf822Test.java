package io.butler.bet.integration;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperWeeklyProjectionProviderBf822Test {
    private static final Instant OBSERVED_AT = Instant.parse("2026-09-16T23:30:00Z");

    @Test
    void readsExactSleeperIdsAndRequestedPprPointsWithoutCredentials() throws Exception {
        String payload = """
            [
              {"player_id":"s-qb","season":"2026","week":2,"season_type":"regular","stats":{"pts_std":18.1,"pts_half_ppr":18.1,"pts_ppr":18.1}},
              {"player_id":"s-wr","season":"2026","week":2,"season_type":"regular","stats":{"pts_std":9.0,"pts_half_ppr":11.5,"pts_ppr":14.0}},
              {"player_id":"old","season":"2026","week":1,"season_type":"regular","stats":{"pts_ppr":99.0}},
              {"player_id":"post","season":"2026","week":2,"season_type":"post","stats":{"pts_ppr":99.0}}
            ]
            """;
        var provider = provider(payload);

        var snapshot = provider.load(2026, 2, SleeperWeeklyProjectionProvider.ScoringBasis.PPR);

        assertEquals(SleeperWeeklyProjectionProvider.SOURCE_NAME, snapshot.sourceName());
        assertEquals(2026, snapshot.season());
        assertEquals(2, snapshot.week());
        assertEquals(SleeperWeeklyProjectionProvider.ScoringBasis.PPR, snapshot.scoring());
        assertEquals(OBSERVED_AT, snapshot.observedAt());
        assertEquals(2, snapshot.projections().size());
        assertEquals("s-qb", snapshot.projections().get(0).sleeperPlayerId());
        assertEquals(new BigDecimal("18.1"), snapshot.projections().get(0).projectedPoints());
        assertEquals("s-wr", snapshot.projections().get(1).sleeperPlayerId());
        assertEquals(new BigDecimal("14.0"), snapshot.projections().get(1).projectedPoints());
    }

    @Test
    void readsPlayerIdKeyedFlatPayloadWithoutChangingScoringBasis() throws Exception {
        String payload = """
            {
              "s-qb":{"season":2026,"week":2,"season_type":"regular","pts_std":18.1,"pts_half_ppr":18.1,"pts_ppr":18.1},
              "s-wr":{"player_id":"s-wr","season":2026,"week":2,"season_type":"regular","pts_std":9.0,"pts_half_ppr":11.5,"pts_ppr":14.0}
            }
            """;
        var provider = provider(payload);

        var snapshot = provider.load(2026, 2, SleeperWeeklyProjectionProvider.ScoringBasis.PPR);

        assertEquals(2, snapshot.projections().size());
        assertEquals("s-qb", snapshot.projections().get(0).sleeperPlayerId());
        assertEquals(new BigDecimal("18.1"), snapshot.projections().get(0).projectedPoints());
        assertEquals("s-wr", snapshot.projections().get(1).sleeperPlayerId());
        assertEquals(new BigDecimal("14.0"), snapshot.projections().get(1).projectedPoints());
    }

    @Test
    void selectsHalfPprFieldExactly() throws Exception {
        String payload = """
            [{"player_id":"s-wr","season":2026,"week":2,"stats":{"pts_std":9.0,"pts_half_ppr":11.5,"pts_ppr":14.0}}]
            """;
        var provider = provider(payload);

        var snapshot = provider.load(2026, 2, SleeperWeeklyProjectionProvider.ScoringBasis.HALF);

        assertEquals(new BigDecimal("11.5"), snapshot.projections().get(0).projectedPoints());
    }

    @Test
    void duplicatePlayerIdsFailClosed() {
        String payload = """
            [
              {"player_id":"same","season":2026,"week":2,"stats":{"pts_ppr":10}},
              {"player_id":"same","season":2026,"week":2,"stats":{"pts_ppr":11}}
            ]
            """;
        var provider = provider(payload);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> provider.load(2026, 2, SleeperWeeklyProjectionProvider.ScoringBasis.PPR));
        assertTrue(error.getMessage().contains("duplicate player_id"));
    }

    @Test
    void playerIdKeyAndEmbeddedIdentityMustAgree() {
        String payload = """
            {"s-wr":{"player_id":"different","season":2026,"week":2,"pts_ppr":14.0}}
            """;
        var provider = provider(payload);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> provider.load(2026, 2, SleeperWeeklyProjectionProvider.ScoringBasis.PPR));
        assertTrue(error.getMessage().contains("contradictory player identity"));
    }

    @Test
    void missingRequestedScoringEvidenceFailsClosedInsteadOfUsingStandardPoints() {
        String payload = """
            {"s-wr":{"season":2026,"week":2,"pts_std":9.0}}
            """;
        var provider = provider(payload);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> provider.load(2026, 2, SleeperWeeklyProjectionProvider.ScoringBasis.PPR));
        assertTrue(error.getMessage().contains("no usable pts_ppr evidence"));
    }

    @Test
    void malformedExplicitSeasonOrWeekDoesNotBecomeCurrentEvidence() {
        String payload = """
            {
              "bad-season":{"season":"not-a-season","week":2,"pts_ppr":20.0},
              "bad-week":{"season":2026,"week":"not-a-week","pts_ppr":21.0}
            }
            """;
        var provider = provider(payload);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> provider.load(2026, 2, SleeperWeeklyProjectionProvider.ScoringBasis.PPR));
        assertTrue(error.getMessage().contains("no usable pts_ppr evidence"));
    }

    private static SleeperWeeklyProjectionProvider provider(String payload) {
        return new SleeperWeeklyProjectionProvider(
            (season, week) -> payload,
            Clock.fixed(OBSERVED_AT, ZoneOffset.UTC));
    }
}
