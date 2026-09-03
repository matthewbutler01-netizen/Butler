package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerLongitudinalEvidenceCliTest {
    @Test
    void parsesLeagueId() {
        assertEquals("l1", ButlerLongitudinalEvidenceCli.parse(new String[]{
            "league", "longitudinal-evidence", "l1"}));
    }

    @Test
    void rejectsMissingBlankAndExtraArguments() {
        assertThrows(IllegalArgumentException.class, () -> ButlerLongitudinalEvidenceCli.parse(new String[]{
            "league", "longitudinal-evidence"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLongitudinalEvidenceCli.parse(new String[]{
            "league", "longitudinal-evidence", "  "}));
        assertThrows(IllegalArgumentException.class, () -> ButlerLongitudinalEvidenceCli.parse(new String[]{
            "league", "longitudinal-evidence", "l1", "extra"}));
    }

    @Test
    void recognizesOnlyLongitudinalEvidenceCommand() {
        assertTrue(ButlerLongitudinalEvidenceCli.isCommand(new String[]{
            "league", "longitudinal-evidence", "l1"}));
        assertFalse(ButlerLongitudinalEvidenceCli.isCommand(new String[]{
            "league", "production-context", "l1"}));
    }
}
