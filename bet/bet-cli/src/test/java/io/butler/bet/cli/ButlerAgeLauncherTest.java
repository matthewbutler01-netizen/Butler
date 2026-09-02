package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAgeLauncherTest {
    @Test
    void recognizesAgeContextCommand() {
        assertTrue(ButlerAgeLauncher.isAgeContextCommand(new String[]{"league", "age-context", "league-id"}));
    }

    @Test
    void parsesDefaultAgeContext() {
        var options = ButlerAgeLauncher.parseAgeContext(new String[]{"league", "age-context", "league-id"});
        assertEquals("league-id", options.leagueId());
        assertNull(options.ageAsOf());
        assertNull(options.minimumProfileAsOf());
    }

    @Test
    void parsesDatesInEitherOrder() {
        var options = ButlerAgeLauncher.parseAgeContext(new String[]{
            "league", "age-context", "league-id",
            "--minimum-profile-as-of", "2026-08-15",
            "--age-as-of", "2026-09-02"});
        assertEquals(LocalDate.of(2026, 9, 2), options.ageAsOf());
        assertEquals(LocalDate.of(2026, 8, 15), options.minimumProfileAsOf());
    }

    @Test
    void rejectsUnknownDuplicateAndMalformedOptions() {
        assertThrows(IllegalArgumentException.class, () -> ButlerAgeLauncher.parseAgeContext(new String[]{
            "league", "age-context", "league-id", "--unknown", "2026-09-02"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgeLauncher.parseAgeContext(new String[]{
            "league", "age-context", "league-id", "--age-as-of", "2026-09-01", "--age-as-of", "2026-09-02"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgeLauncher.parseAgeContext(new String[]{
            "league", "age-context", "league-id", "--minimum-profile-as-of", "not-a-date"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgeLauncher.parseAgeContext(new String[]{
            "league", "age-context"}));
    }
}
