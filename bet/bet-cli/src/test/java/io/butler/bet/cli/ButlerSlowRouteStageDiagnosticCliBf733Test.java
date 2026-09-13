package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButlerSlowRouteStageDiagnosticCliBf733Test {
    @Test
    void timingMarkerPinsNamedReadOnlyRouteStages() {
        var timing = new ButlerSlowRouteStageDiagnosticCli.StageTiming(
            1L,
            2L,
            3L,
            4L,
            5L,
            6L,
            7L,
            8L,
            9L,
            10L,
            11L,
            12L,
            13L);

        assertEquals(
            "===BUTLER_SLOW_ROUTE_TIMING:"
                + "database_ms=1;target_ms=2;summary_ms=3;comparison_ms=4;roster_context_ms=5;"
                + "waiver_wall_ms=6;home_evidence_ms=7;waiver_evidence_ms=8;"
                + "league_action_plan_ms=9;league_rankings_ms=10;league_movement_ms=11;"
                + "league_evidence_ms=12;total_ms=13===",
            ButlerSlowRouteStageDiagnosticCli.timingMarker(timing));
    }
}
