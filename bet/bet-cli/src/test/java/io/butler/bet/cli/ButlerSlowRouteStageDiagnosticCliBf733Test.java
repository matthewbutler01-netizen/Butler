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
            13L,
            14L,
            15L,
            16L,
            17L,
            18L);

        assertEquals(
            "===BUTLER_SLOW_ROUTE_TIMING:"
                + "database_ms=1;target_ms=2;summary_ms=3;comparison_ms=4;"
                + "comparison_methodology_ms=5;comparison_candidate_frame_ms=6;"
                + "comparison_roster_frame_ms=7;comparison_production_load_ms=8;"
                + "comparison_residual_ms=9;roster_context_ms=10;waiver_wall_ms=11;"
                + "home_evidence_ms=12;waiver_evidence_ms=13;league_action_plan_ms=14;"
                + "league_rankings_ms=15;league_movement_ms=16;league_evidence_ms=17;total_ms=18===",
            ButlerSlowRouteStageDiagnosticCli.timingMarker(timing));
    }
}
