package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynastyProcessDraftPickCatalogTest {
    @Test
    void keepsOnlyGenericYearRoundValuesAndPreservesOneQbTwoQbValues() {
        String csv = """
            player,pos,team,age,draft_year,ecr_1qb,ecr_2qb,ecr_pos,value_1qb,value_2qb,scrape_date,fp_id
            "2027 1st",PICK,NA,NA,NA,75,70,NA,1782,2028,2026-08-28,NA
            "2027 Early 1st",PICK,NA,NA,NA,41,37,NA,2500,2800,2026-08-28,NA
            "2027 Mid 1st",PICK,NA,NA,NA,76,71,NA,1761,1994,2026-08-28,NA
            "2027 Late 1st",PICK,NA,NA,NA,108,101,NA,1200,1400,2026-08-28,NA
            "2027 2nd",PICK,NA,NA,NA,166,156,NA,900,1050,2026-08-28,NA
            "2026 Pick 1.01",PICK,NA,NA,NA,21,15,NA,6000,6500,2026-08-28,1
            "Example Player",WR,KC,24,2024,10,12,2,5000,4800,2026-08-28,123
            """;

        var catalog = new DynastyProcessDraftPickCatalog().parseCsv(csv);

        assertEquals(LocalDate.of(2026, 8, 28), catalog.asOfDate());
        assertEquals(2, catalog.values().size());
        var first = catalog.find(2027, 1).orElseThrow();
        assertEquals("2027 1st", first.label());
        assertEquals(1782.0, first.oneQbValue());
        assertEquals(2028.0, first.twoQbValue());
        assertTrue(catalog.find(2027, 2).isPresent());
        assertFalse(catalog.find(2026, 1).isPresent());
    }

    @Test
    void sortsBySeasonThenRound() {
        String csv = """
            player,pos,value_1qb,value_2qb,scrape_date
            "2028 2nd",PICK,200,210,2026-08-28
            "2027 2nd",PICK,300,310,2026-08-28
            "2028 1st",PICK,500,520,2026-08-28
            "2027 1st",PICK,600,620,2026-08-28
            """;

        var values = new DynastyProcessDraftPickCatalog().parseCsv(csv).values();

        assertEquals("2027 1st", values.get(0).label());
        assertEquals("2027 2nd", values.get(1).label());
        assertEquals("2028 1st", values.get(2).label());
        assertEquals("2028 2nd", values.get(3).label());
    }

    @Test
    void rejectsMixedScrapeDatesAndDuplicateGenericKeys() {
        String mixedDates = """
            player,pos,value_1qb,value_2qb,scrape_date
            "2027 1st",PICK,600,620,2026-08-28
            "2027 2nd",PICK,300,310,2026-08-29
            """;
        assertThrows(IllegalArgumentException.class,
            () -> new DynastyProcessDraftPickCatalog().parseCsv(mixedDates));

        String duplicate = """
            player,pos,value_1qb,value_2qb,scrape_date
            "2027 1st",PICK,600,620,2026-08-28
            "2027 1st",PICK,601,621,2026-08-28
            """;
        assertThrows(IllegalArgumentException.class,
            () -> new DynastyProcessDraftPickCatalog().parseCsv(duplicate));
    }

    @Test
    void rejectsInvalidGenericValuesButIgnoresInvalidNonGenericRows() {
        String invalidGeneric = """
            player,pos,value_1qb,value_2qb,scrape_date
            "2027 1st",PICK,-1,620,2026-08-28
            """;
        assertThrows(IllegalArgumentException.class,
            () -> new DynastyProcessDraftPickCatalog().parseCsv(invalidGeneric));

        String irrelevant = """
            player,pos,value_1qb,value_2qb,scrape_date
            "2027 Early 1st",PICK,NA,NA,not-a-date
            """;
        var catalog = new DynastyProcessDraftPickCatalog().parseCsv(irrelevant);
        assertTrue(catalog.values().isEmpty());
    }
}
