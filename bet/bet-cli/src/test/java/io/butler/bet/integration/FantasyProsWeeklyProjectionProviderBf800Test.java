package io.butler.bet.integration;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FantasyProsWeeklyProjectionProviderBf800Test {
    private static final String SAMPLE = """
        {
          "season": "2026",
          "week": "2",
          "scoring": "PPR",
          "players": [
            {
              "fpid": 101,
              "name": "Wide Receiver",
              "position_id": "WR",
              "team_id": "STL",
              "stats": {"points": 10.0, "points_half": 12.5, "points_ppr": 15.0}
            },
            {
              "fpid": 202,
              "name": "Quarter Back",
              "position_id": "QB",
              "team_id": "CHI",
              "stats": {"points": 21.75}
            }
          ]
        }
        """;

    @Test
    void parsesPprPointsAndUsesBasePointsForNonReceptionPositions() throws Exception {
        var provider = new FantasyProsWeeklyProjectionProvider((season, week, scoring) -> SAMPLE);
        var snapshot = provider.load(2026, 2, FantasyProsWeeklyProjectionProvider.ScoringBasis.PPR);

        assertEquals(2026, snapshot.season());
        assertEquals(2, snapshot.week());
        assertEquals(FantasyProsWeeklyProjectionProvider.ScoringBasis.PPR, snapshot.scoring());
        assertEquals(new BigDecimal("15.0"), snapshot.projections().get(0).projectedPoints());
        assertEquals(new BigDecimal("21.75"), snapshot.projections().get(1).projectedPoints());
        assertTrue(snapshot.sourceSurface().contains("scoring=PPR"));
    }

    @Test
    void selectsHalfAndStandardReceptionProjectionFields() throws Exception {
        String half = SAMPLE.replace("\"PPR\"", "\"HALF\"");
        var halfProvider = new FantasyProsWeeklyProjectionProvider((season, week, scoring) -> half);
        var halfSnapshot = halfProvider.load(2026, 2, FantasyProsWeeklyProjectionProvider.ScoringBasis.HALF);
        assertEquals(new BigDecimal("12.5"), halfSnapshot.projections().get(0).projectedPoints());

        String std = SAMPLE.replace("\"PPR\"", "\"STD\"");
        var stdProvider = new FantasyProsWeeklyProjectionProvider((season, week, scoring) -> std);
        var stdSnapshot = stdProvider.load(2026, 2, FantasyProsWeeklyProjectionProvider.ScoringBasis.STD);
        assertEquals(new BigDecimal("10.0"), stdSnapshot.projections().get(0).projectedPoints());
    }

    @Test
    void rejectsMissingScoringSpecificReceiverProjectionInsteadOfGuessing() {
        String missing = SAMPLE.replace(", \"points_ppr\": 15.0", "");
        var provider = new FantasyProsWeeklyProjectionProvider((season, week, scoring) -> missing);

        var error = assertThrows(IllegalStateException.class,
            () -> provider.load(2026, 2, FantasyProsWeeklyProjectionProvider.ScoringBasis.PPR));
        assertTrue(error.getMessage().contains("points_ppr"));
    }

    @Test
    void rejectsMismatchedFrameAndDuplicateFantasyProsIdentity() {
        var weekProvider = new FantasyProsWeeklyProjectionProvider(
            (season, week, scoring) -> SAMPLE.replace("\"week\": \"2\"", "\"week\": \"3\""));
        assertThrows(IllegalStateException.class,
            () -> weekProvider.load(2026, 2, FantasyProsWeeklyProjectionProvider.ScoringBasis.PPR));

        String duplicate = SAMPLE.replace(
            "\"fpid\": 202",
            "\"fpid\": 101");
        var duplicateProvider = new FantasyProsWeeklyProjectionProvider((season, week, scoring) -> duplicate);
        assertThrows(IllegalStateException.class,
            () -> duplicateProvider.load(2026, 2, FantasyProsWeeklyProjectionProvider.ScoringBasis.PPR));
    }

    @Test
    void scoringBasisAllowsOnlyFantasyProsStandardHalfAndPprReceptionFormats() {
        assertEquals(FantasyProsWeeklyProjectionProvider.ScoringBasis.STD,
            FantasyProsWeeklyProjectionProvider.ScoringBasis.fromReceptionPoints(0.0));
        assertEquals(FantasyProsWeeklyProjectionProvider.ScoringBasis.HALF,
            FantasyProsWeeklyProjectionProvider.ScoringBasis.fromReceptionPoints(0.5));
        assertEquals(FantasyProsWeeklyProjectionProvider.ScoringBasis.PPR,
            FantasyProsWeeklyProjectionProvider.ScoringBasis.fromReceptionPoints(1.0));
        assertThrows(IllegalStateException.class,
            () -> FantasyProsWeeklyProjectionProvider.ScoringBasis.fromReceptionPoints(1.5));
        assertThrows(IllegalStateException.class,
            () -> FantasyProsWeeklyProjectionProvider.ScoringBasis.fromReceptionPoints(null));
    }
}
