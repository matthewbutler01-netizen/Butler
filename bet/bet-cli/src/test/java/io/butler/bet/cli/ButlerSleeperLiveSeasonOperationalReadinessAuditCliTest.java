package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperLiveSeasonOperationalReadinessAudit;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveSeasonOperationalReadinessAuditCliTest {
    @Test
    void parsesExactlyOneLeagueId() {
        var options = ButlerSleeperLiveSeasonOperationalReadinessAuditCli.parse(
            new String[]{" league-1 "});

        assertEquals("league-1", options.leagueId());
    }

    @Test
    void rejectsMalformedArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperLiveSeasonOperationalReadinessAuditCli.parse(new String[]{}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperLiveSeasonOperationalReadinessAuditCli.parse(new String[]{"l1", "extra"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperLiveSeasonOperationalReadinessAuditCli.parse(new String[]{" "}));
    }

    @Test
    void rendersCapabilityStatesBlockersAndReadOnlyBoundary() {
        var ready = new SleeperLiveSeasonOperationalReadinessAudit.CapabilityReadiness(
            SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.READY, List.of());
        var notAudited = new SleeperLiveSeasonOperationalReadinessAudit.CapabilityReadiness(
            SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.NOT_YET_AUDITED,
            List.of("fresh free-agent universe not yet proven"));
        var report = new SleeperLiveSeasonOperationalReadinessAudit.AuditReport(
            SleeperLiveSeasonOperationalReadinessAudit.POLICY_ID,
            "league-1",
            "League",
            "provider-1",
            2026,
            2026,
            "in_season",
            1,
            2,
            4,
            2,
            2,
            2,
            2,
            2,
            2,
            0,
            List.of(),
            List.of(),
            List.of(),
            0,
            List.of(),
            List.of(
                new SleeperLiveSeasonOperationalReadinessAudit.RosterOwnerObservation(1, "u1", true, 1, 1),
                new SleeperLiveSeasonOperationalReadinessAudit.RosterOwnerObservation(2, "u2", true, 1, 1)),
            ready,
            ready,
            notAudited,
            ready,
            Instant.parse("2026-09-07T22:45:00Z"));

        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            ButlerSleeperLiveSeasonOperationalReadinessAuditCli.print(report);
        } finally {
            System.setOut(original);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("Target/provider season: 2026/2026"));
        assertTrue(rendered.contains("Current-roster context: READY"));
        assertTrue(rendered.contains("Waiver/free-agent inventory prerequisites: NOT_YET_AUDITED"));
        assertTrue(rendered.contains("fresh free-agent universe not yet proven"));
        assertTrue(rendered.contains("does not infer the user's team by name"));
        assertTrue(rendered.contains("does not make start/sit"));
    }
}
