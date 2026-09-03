package io.butler.bet.intelligence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeagueLineupRequirementsAnalyzerTest {
    @Test
    void separatesDirectRequirementsFromFlexExposureAndNonStarters() {
        var report = LeagueLineupRequirementsAnalyzer.interpret("l1", List.of(
            "QB", "RB", "RB", "WR", "WR", "TE", "FLEX", "SUPER_FLEX", "BN", "BN", "IR", "TAXI"));

        assertTrue(report.available());
        assertEquals(1, report.directStarterRequirements().get("QB"));
        assertEquals(2, report.directStarterRequirements().get("RB"));
        assertEquals(2, report.directStarterRequirements().get("WR"));
        assertEquals(1, report.directStarterRequirements().get("TE"));
        assertEquals(1, report.flexSlots());
        assertEquals(1, report.superFlexSlots());
        assertEquals(2, report.benchSlots());
        assertEquals(1, report.reserveSlots());
        assertEquals(1, report.taxiSlots());
    }

    @Test
    void preservesNonCoreAndUnknownSlotsAsInspectableContext() {
        var report = LeagueLineupRequirementsAnalyzer.interpret("l1",
            List.of("QB", "K", "DEF", "IDP_FLEX", "MYSTERY"));
        assertEquals(List.of("K", "DEF", "IDP_FLEX"), report.otherStarterSlots());
        assertEquals(List.of("MYSTERY"), report.unknownSlots());
    }

    @Test
    void supportsCommonFlexAliasesWithoutAssigningThemToOnePosition() {
        var report = LeagueLineupRequirementsAnalyzer.interpret("l1",
            List.of("WRRB_FLEX", "REC_FLEX", "SUPERFLEX"));
        assertEquals(2, report.flexSlots());
        assertEquals(1, report.superFlexSlots());
        assertEquals(0, report.directStarterRequirements().get("RB"));
        assertEquals(0, report.directStarterRequirements().get("WR"));
    }

    @Test
    void emptyConfigurationIsUnavailableRatherThanInvented() {
        var report = LeagueLineupRequirementsAnalyzer.interpret("l1", List.of());
        assertFalse(report.available());
        assertEquals(0, report.flexSlots());
        assertEquals(0, report.superFlexSlots());
    }
}
