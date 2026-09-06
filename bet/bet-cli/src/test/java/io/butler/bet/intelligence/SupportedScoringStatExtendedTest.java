package io.butler.bet.intelligence;

import io.butler.bet.domain.PlayerSeasonProduction;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportedScoringStatExtendedTest {
    @Test
    void extendedV2ProductionExposesAllSixNewSleeperDimensionsExactly() {
        var p = PlayerSeasonProduction.createExactScoringV2(
            "p1", 2025, 17,
            4000, 30, 10,
            500, 5,
            60, 700, 6,
            2,
            3, 123, 4, 5, 1, 2,
            "nflverse", LocalDate.of(2026, 9, 6));

        assertEquals(3, SupportedScoringStat.find("pass_2pt").value(p));
        assertEquals(123, SupportedScoringStat.find("rush_att").value(p));
        assertEquals(4, SupportedScoringStat.find("rush_2pt").value(p));
        assertEquals(5, SupportedScoringStat.find("rec_2pt").value(p));
        assertEquals(1, SupportedScoringStat.find("fum_rec_td").value(p));
        assertEquals(2, SupportedScoringStat.find("st_td").value(p));
    }

    @Test
    void legacyProductionFailsClosedForNewDimensionsButRetainsOldScoring() {
        var legacy = PlayerSeasonProduction.create(
            "p1", 2025, 17,
            4000, 30, 10,
            500, 5,
            60, 700, 6,
            2, "nflverse", LocalDate.of(2026, 9, 5));

        assertEquals(4000, SupportedScoringStat.find("pass_yd").value(legacy));
        var error = assertThrows(IllegalStateException.class,
            () -> SupportedScoringStat.find("rush_att").value(legacy));
        assertTrue(error.getMessage().contains("requires refreshed raw production schema v2"));
        assertTrue(error.getMessage().contains("Refresh nflverse production"));
    }
}
