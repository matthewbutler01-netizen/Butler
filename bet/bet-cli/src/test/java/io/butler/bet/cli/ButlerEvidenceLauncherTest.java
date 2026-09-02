package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerEvidenceLauncherTest {
    @Test
    void parsesImplicitSeasonWithIndependentFreshnessCutoffs() {
        var options = ButlerEvidenceLauncher.parseEvidenceOverview(new String[]{
            "league", "evidence-overview", "league-id",
            "--minimum-value-as-of", "2026-09-01",
            "--minimum-profile-as-of", "2026-08-15"});

        assertEquals("league-id", options.leagueId());
        assertNull(options.season());
        assertEquals(LocalDate.of(2026, 9, 1), options.minimumValueAsOf());
        assertEquals(LocalDate.of(2026, 8, 15), options.minimumProfileAsOf());
    }

    @Test
    void parsesExplicitSeasonAndEitherCutoffOrder() {
        var options = ButlerEvidenceLauncher.parseEvidenceOverview(new String[]{
            "league", "evidence-overview", "league-id", "2025",
            "--minimum-profile-as-of", "2026-08-15",
            "--minimum-value-as-of", "2026-09-01"});

        assertEquals(2025, options.season());
        assertEquals(LocalDate.of(2026, 9, 1), options.minimumValueAsOf());
        assertEquals(LocalDate.of(2026, 8, 15), options.minimumProfileAsOf());
    }

    @Test
    void rejectsUnknownDuplicateAndMalformedOptions() {
        assertThrows(IllegalArgumentException.class, () -> ButlerEvidenceLauncher.parseEvidenceOverview(new String[]{
            "league", "evidence-overview", "league-id", "--unknown", "2026-09-01"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerEvidenceLauncher.parseEvidenceOverview(new String[]{
            "league", "evidence-overview", "league-id",
            "--minimum-value-as-of", "2026-09-01",
            "--minimum-value-as-of", "2026-09-02"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerEvidenceLauncher.parseEvidenceOverview(new String[]{
            "league", "evidence-overview", "league-id", "--minimum-profile-as-of", "not-a-date"}));
    }

    @Test
    void recognizesOnlyEvidenceOverviewForInterception() {
        assertTrue(ButlerEvidenceLauncher.isEvidenceOverviewCommand(new String[]{
            "league", "evidence-overview", "league-id"}));
    }
}
