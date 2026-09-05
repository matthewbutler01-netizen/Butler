package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SupportedScoringStatTest {
    @Test
    void exposesExactlyTheProductionBackedScoringKeys() {
        assertEquals(9, SupportedScoringStat.values().length);
        assertEquals("passingYards", SupportedScoringStat.find("pass_yd").productionField());
        assertEquals("fumblesLost", SupportedScoringStat.find("fum_lost").productionField());
        assertNull(SupportedScoringStat.find("bonus_rec_te"));
        assertNull(SupportedScoringStat.find(null));
    }
}
