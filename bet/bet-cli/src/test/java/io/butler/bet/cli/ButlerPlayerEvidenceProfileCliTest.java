package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPlayerEvidenceProfileCliTest {
    @Test
    void parsesImplicitSeasonAndIndependentAgeOptions() {
        var options = ButlerPlayerEvidenceProfileCli.parse(new String[]{
            "league", "player-evidence-profile", "l1",
            "--age-as-of", "2026-09-02",
            "--minimum-profile-as-of", "2026-08-15"});

        assertEquals("l1", options.leagueId());
        assertNull(options.season());
        assertEquals(LocalDate.of(2026, 9, 2), options.ageAsOf());
        assertEquals(LocalDate.of(2026, 8, 15), options.minimumProfileAsOf());
    }

    @Test
    void parsesExplicitSeasonAndEitherFlagOrder() {
        var options = ButlerPlayerEvidenceProfileCli.parse(new String[]{
            "league", "player-evidence-profile", "l1", "2025",
            "--minimum-profile-as-of", "2026-08-15",
            "--age-as-of", "2026-09-02"});

        assertEquals(2025, options.season());
        assertEquals(LocalDate.of(2026, 9, 2), options.ageAsOf());
        assertEquals(LocalDate.of(2026, 8, 15), options.minimumProfileAsOf());
    }

    @Test
    void rejectsUnknownDuplicateAndMalformedOptions() {
        assertThrows(IllegalArgumentException.class, () -> ButlerPlayerEvidenceProfileCli.parse(new String[]{
            "league", "player-evidence-profile", "l1", "--unknown", "2026-09-02"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerPlayerEvidenceProfileCli.parse(new String[]{
            "league", "player-evidence-profile", "l1",
            "--age-as-of", "2026-09-02", "--age-as-of", "2026-09-03"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerPlayerEvidenceProfileCli.parse(new String[]{
            "league", "player-evidence-profile", "l1", "--minimum-profile-as-of", "bad-date"}));
    }

    @Test
    void recognizesOnlyPlayerEvidenceProfileCommand() {
        assertTrue(ButlerPlayerEvidenceProfileCli.isCommand(new String[]{
            "league", "player-evidence-profile", "l1"}));
    }
}
