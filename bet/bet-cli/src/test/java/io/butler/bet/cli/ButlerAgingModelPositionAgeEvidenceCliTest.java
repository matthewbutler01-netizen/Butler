package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ButlerAgingModelPositionAgeEvidenceCliTest {
    @Test
    void parsesOnlyBoundedSupportedPositionAgeCoordinates() {
        var options = ButlerAgingModelPositionAgeEvidenceCli.parse(
            new String[]{"aging-model", "position-age-evidence", " qb ", "25"});
        assertEquals("QB", options.position());
        assertEquals(25, options.age());
    }

    @Test
    void rejectsUnsupportedOrExtraInput() {
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelPositionAgeEvidenceCli.parse(
            new String[]{"aging-model", "position-age-evidence", "K", "25"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelPositionAgeEvidenceCli.parse(
            new String[]{"aging-model", "position-age-evidence", "QB", "-1"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerAgingModelPositionAgeEvidenceCli.parse(
            new String[]{"aging-model", "position-age-evidence", "QB", "25", "extra"}));
    }
}
