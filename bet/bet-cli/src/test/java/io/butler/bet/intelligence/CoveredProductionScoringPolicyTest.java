package io.butler.bet.intelligence;

import io.butler.bet.domain.PlayerSeasonProduction;
import io.butler.bet.domain.PlayerWeekProduction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoveredProductionScoringPolicyTest {
    private final CoveredProductionScoringPolicy policy = new CoveredProductionScoringPolicy();

    @Test
    void scoresEverySupportedProductionDimensionExactly() {
        var result = policy.score(production(300, 2, 1, 25, 1, 8, 100, 1, 1), standardPpr());

        assertEquals(0, result.totalPoints().compareTo(new BigDecimal("48.5")));
        assertEquals(9, result.components().size());
        assertEquals("pass_int", result.components().get(1).statKey());
        assertEquals(0, result.components().get(1).contribution().compareTo(new BigDecimal("-2")));
        assertTrue(result.components().stream().anyMatch(component ->
            component.statKey().equals("rec")
                && component.rawValue() == 8
                && component.contribution().compareTo(new BigDecimal("8.0")) == 0));
    }

    @Test
    void weekAndSeasonProductionUseTheSameExactArithmeticAndMapping() {
        var season = production(250, 2, 1, 30, 1, 5, 50, 1, 1);
        var week = new PlayerWeekProduction(
            "week-prod-1", "player-1", 2026, 7,
            250, 2, 1, 30, 1, 5, 50, 1, 1,
            "nflverse", LocalDate.of(2026, 10, 20));

        var seasonScore = policy.score(season, standardPpr());
        var weekScore = policy.score(week, standardPpr());

        assertEquals(0, seasonScore.totalPoints().compareTo(weekScore.totalPoints()));
        assertEquals(0, weekScore.totalPoints().compareTo(new BigDecimal("39")));
        assertEquals(7, weekScore.week());
        assertEquals("week-prod-1", weekScore.productionId());
        assertEquals(seasonScore.components(), weekScore.components());
    }

    @Test
    void preservesLegitimateNegativeYardageContributions() {
        var result = policy.score(production(0, 0, 0, -5, 0, 0, 0, 0, 0), Map.of("rush_yd", 0.1));

        assertEquals(0, result.totalPoints().compareTo(new BigDecimal("-0.5")));
        assertEquals(-5, result.components().getFirst().rawValue());
    }

    @Test
    void ignoresUnknownZeroRulesButFailsClosedOnUnknownNonzeroRules() {
        var withZero = new LinkedHashMap<String, Double>();
        withZero.put("rec", 1.0);
        withZero.put("bonus_rec_te", 0.0);
        var result = policy.score(production(0, 0, 0, 0, 0, 5, 0, 0, 0), withZero);
        assertEquals(0, result.totalPoints().compareTo(new BigDecimal("5.0")));
        assertEquals(1, result.components().size());

        var unsupported = new LinkedHashMap<String, Double>(withZero);
        unsupported.put("bonus_rec_te", 0.5);
        var error = assertThrows(IllegalStateException.class,
            () -> policy.score(production(0, 0, 0, 0, 0, 5, 0, 0, 0), unsupported));
        assertTrue(error.getMessage().contains("bonus_rec_te"));
    }

    @Test
    void refusesMissingScoringSettings() {
        assertThrows(IllegalStateException.class,
            () -> policy.score(production(0, 0, 0, 0, 0, 0, 0, 0, 0), Map.of()));
    }

    private static Map<String, Double> standardPpr() {
        Map<String, Double> scoring = new LinkedHashMap<>();
        scoring.put("pass_yd", 0.04);
        scoring.put("pass_td", 4.0);
        scoring.put("pass_int", -2.0);
        scoring.put("rush_yd", 0.1);
        scoring.put("rush_td", 6.0);
        scoring.put("rec", 1.0);
        scoring.put("rec_yd", 0.1);
        scoring.put("rec_td", 6.0);
        scoring.put("fum_lost", -2.0);
        return scoring;
    }

    private static PlayerSeasonProduction production(
        int passingYards,
        int passingTouchdowns,
        int interceptions,
        int rushingYards,
        int rushingTouchdowns,
        int receptions,
        int receivingYards,
        int receivingTouchdowns,
        int fumblesLost) {
        return new PlayerSeasonProduction(
            "prod-1", "player-1", 2026, 17,
            passingYards, passingTouchdowns, interceptions,
            rushingYards, rushingTouchdowns, receptions, receivingYards, receivingTouchdowns,
            fumblesLost, "nflverse", LocalDate.of(2027, 1, 15));
    }
}
