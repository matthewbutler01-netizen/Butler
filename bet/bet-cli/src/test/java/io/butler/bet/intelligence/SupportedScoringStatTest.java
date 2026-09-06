package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SupportedScoringStatTest {
    @Test
    void exposesExactlyTheProductionBackedScoringKeys() {
        assertEquals(15, SupportedScoringStat.values().length);
        assertEquals("passingYards", SupportedScoringStat.find("pass_yd").productionField());
        assertEquals("fumblesLost", SupportedScoringStat.find("fum_lost").productionField());
        assertEquals("passingTwoPointConversions", SupportedScoringStat.find("pass_2pt").productionField());
        assertEquals("rushingAttempts", SupportedScoringStat.find("rush_att").productionField());
        assertEquals("rushingTwoPointConversions", SupportedScoringStat.find("rush_2pt").productionField());
        assertEquals("receivingTwoPointConversions", SupportedScoringStat.find("rec_2pt").productionField());
        assertEquals("fumbleRecoveryTouchdowns", SupportedScoringStat.find("fum_rec_td").productionField());
        assertEquals("specialTeamsTouchdowns", SupportedScoringStat.find("st_td").productionField());
        assertNull(SupportedScoringStat.find("bonus_rec_te"));
        assertNull(SupportedScoringStat.find(null));
    }
}
