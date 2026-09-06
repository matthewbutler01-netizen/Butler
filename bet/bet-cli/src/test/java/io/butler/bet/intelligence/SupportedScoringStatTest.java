package io.butler.bet.intelligence;

import io.butler.bet.domain.PlayerWeekProduction;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportedScoringStatTest {
    @Test
    void exposesExactlyTheProductionBackedScoringKeys() {
        assertEquals(22, SupportedScoringStat.values().length);
        assertEquals("passingYards", SupportedScoringStat.find("pass_yd").productionField());
        assertEquals("sacksSuffered", SupportedScoringStat.find("pass_sack").productionField());
        assertEquals(3, SupportedScoringStat.find("pass_sack").minimumRawScoringSchemaVersion());
        assertEquals("fumblesLost", SupportedScoringStat.find("fum_lost").productionField());
        assertEquals("passingTwoPointConversions", SupportedScoringStat.find("pass_2pt").productionField());
        assertEquals("rushingAttempts", SupportedScoringStat.find("rush_att").productionField());
        assertEquals("rushingTwoPointConversions", SupportedScoringStat.find("rush_2pt").productionField());
        assertEquals("receivingTwoPointConversions", SupportedScoringStat.find("rec_2pt").productionField());
        assertEquals("fumbleRecoveryTouchdowns", SupportedScoringStat.find("fum_rec_td").productionField());
        assertEquals("specialTeamsTouchdowns", SupportedScoringStat.find("st_td").productionField());
        assertEquals("weeklyPassingYards>=300", SupportedScoringStat.find("bonus_pass_yd_300").productionField());
        assertEquals("weeklyPassingYards>=400", SupportedScoringStat.find("bonus_pass_yd_400").productionField());
        assertEquals("weeklyRushingYards>=100", SupportedScoringStat.find("bonus_rush_yd_100").productionField());
        assertEquals("weeklyRushingYards>=200", SupportedScoringStat.find("bonus_rush_yd_200").productionField());
        assertEquals("weeklyReceivingYards>=100", SupportedScoringStat.find("bonus_rec_yd_100").productionField());
        assertEquals("weeklyReceivingYards>=200", SupportedScoringStat.find("bonus_rec_yd_200").productionField());
        assertNull(SupportedScoringStat.find("bonus_rec_te"));
        assertNull(SupportedScoringStat.find(null));
    }

    @Test
    void weeklyYardageBonusesUseExactThresholdBoundaries() {
        assertEquals(0, SupportedScoringStat.find("bonus_pass_yd_300").value(week(299, 0, 0)));
        assertEquals(1, SupportedScoringStat.find("bonus_pass_yd_300").value(week(300, 0, 0)));
        assertEquals(0, SupportedScoringStat.find("bonus_pass_yd_400").value(week(399, 0, 0)));
        assertEquals(1, SupportedScoringStat.find("bonus_pass_yd_400").value(week(400, 0, 0)));

        assertEquals(0, SupportedScoringStat.find("bonus_rush_yd_100").value(week(0, 99, 0)));
        assertEquals(1, SupportedScoringStat.find("bonus_rush_yd_100").value(week(0, 100, 0)));
        assertEquals(0, SupportedScoringStat.find("bonus_rush_yd_200").value(week(0, 199, 0)));
        assertEquals(1, SupportedScoringStat.find("bonus_rush_yd_200").value(week(0, 200, 0)));

        assertEquals(0, SupportedScoringStat.find("bonus_rec_yd_100").value(week(0, 0, 99)));
        assertEquals(1, SupportedScoringStat.find("bonus_rec_yd_100").value(week(0, 0, 100)));
        assertEquals(0, SupportedScoringStat.find("bonus_rec_yd_200").value(week(0, 0, 199)));
        assertEquals(1, SupportedScoringStat.find("bonus_rec_yd_200").value(week(0, 0, 200)));
    }

    @Test
    void weeklyOnlyRulesDoNotClaimSeasonAggregateSupport() {
        var bonus = SupportedScoringStat.find("bonus_pass_yd_300");
        var sacks = SupportedScoringStat.find("pass_sack");
        assertTrue(bonus.supports(SupportedScoringStat.ProductionGrain.WEEK_ONLY));
        assertFalse(bonus.supports(SupportedScoringStat.ProductionGrain.SEASON_AND_WEEK));
        assertTrue(sacks.supports(SupportedScoringStat.ProductionGrain.WEEK_ONLY));
        assertFalse(sacks.supports(SupportedScoringStat.ProductionGrain.SEASON_AND_WEEK));
        assertTrue(SupportedScoringStat.find("pass_yd")
            .supports(SupportedScoringStat.ProductionGrain.SEASON_AND_WEEK));
        assertTrue(SupportedScoringStat.find("pass_yd")
            .supports(SupportedScoringStat.ProductionGrain.WEEK_ONLY));
    }

    private static PlayerWeekProduction week(int passingYards, int rushingYards, int receivingYards) {
        return new PlayerWeekProduction(
            "week-prod", "player-1", 2026, 7,
            passingYards, 0, 0, rushingYards, 0, 0, receivingYards, 0, 0,
            "nflverse", LocalDate.of(2026, 10, 20));
    }
}
