package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerTradeSupportingEvidenceCliTest {
    @Test
    void parsesPlayerOnlyTradeEvidenceCommand() {
        var options = ButlerTradeSupportingEvidenceCli.parse(new String[]{
            "trade", "supporting-evidence", "l1", "2025", "p1,p2", "p3"});
        assertEquals("l1", options.leagueId());
        assertEquals(2025, options.season());
        assertEquals(List.of("p1", "p2"), options.sideAPlayerIds());
        assertEquals(List.of("p3"), options.sideBPlayerIds());
        assertNull(options.source());
    }

    @Test
    void parsesExplicitValueSource() {
        var options = ButlerTradeSupportingEvidenceCli.parse(new String[]{
            "trade", "supporting-evidence", "l1", "2025", "p1", "p2", "dynastyprocess-2qb"});
        assertEquals("dynastyprocess-2qb", options.source());
    }

    @Test
    void formatsGovernedFairnessGapWithoutInventingUnavailableValue() {
        assertEquals("5.000%", ButlerTradeSupportingEvidenceCli.formatGap(5.0));
        assertEquals("5.001%", ButlerTradeSupportingEvidenceCli.formatGap(5.001));
        assertEquals("unavailable", ButlerTradeSupportingEvidenceCli.formatGap(null));
    }

    @Test
    void rejectsMalformedInputs() {
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeSupportingEvidenceCli.parse(new String[]{
            "trade", "supporting-evidence", "l1", "bad", "p1", "p2"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeSupportingEvidenceCli.parse(new String[]{
            "trade", "supporting-evidence", "l1", "2025", "p1,", "p2"}));
        assertThrows(IllegalArgumentException.class, () -> ButlerTradeSupportingEvidenceCli.parse(new String[]{
            "trade", "supporting-evidence", "l1", "2025", "p1"}));
    }

    @Test
    void recognizesOnlyTradeSupportingEvidenceCommand() {
        assertTrue(ButlerTradeSupportingEvidenceCli.isCommand(new String[]{
            "trade", "supporting-evidence", "l1", "2025", "p1", "p2"}));
    }
}
