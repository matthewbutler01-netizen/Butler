package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAgeProductionContextCliTest {
    @Test
    void parsesStoredSeasonDefaultAndExplicitSeasonOverride() {
        var implicit = ButlerAgeProductionContextCli.parse(new String[]{
            "league", "age-production-context", "l1"});
        assertEquals("l1", implicit.leagueId());
        assertNull(implicit.season());

        var explicit = ButlerAgeProductionContextCli.parse(new String[]{
            "league", "age-production-context", "l1", "2025"});
        assertEquals(2025, explicit.season());
    }

    @Test
    void parsesAgeAndProfileDatesInEitherOrder() {
        var options = ButlerAgeProductionContextCli.parse(new String[]{
            "league", "age-production-context", "l1", "2025",
            "--minimum-profile-as-of", "2026-08-15",
            "--age-as-of", "2026-09-02"});

        assertEquals(LocalDate.of(2026, 9, 2), options.ageAsOf());
        assertEquals(LocalDate.of(2026, 8, 15), options.minimumProfileAsOf());
    }

    @Test
    void rejectsDuplicateUnknownAndMalformedOptions() {
        assertThrows(IllegalArgumentException.class, () -> ButlerAgeProductionContextCli.parse(new String[]{
            "league", "age-production-context", "l1",
            "--age-as-of", "2026-09-02", "--age-as-of", "2026-09-03"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgeProductionContextCli.parse(new String[]{
            "league", "age-production-context", "l1", "--unknown", "2026-09-02"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgeProductionContextCli.parse(new String[]{
            "league", "age-production-context", "l1", "--minimum-profile-as-of", "bad-date"}));
    }

    @Test
    void recognizesOnlyAgeProductionContextCommand() {
        assertTrue(ButlerAgeProductionContextCli.isCommand(new String[]{
            "league", "age-production-context", "l1"}));
    }
}
