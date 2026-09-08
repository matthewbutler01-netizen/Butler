package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperCurrentSeasonHydrationEligibilityAudit;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperCurrentSeasonHydrationEligibilityAuditCliTest {
    @Test
    void parsesExactlyOneLeagueId() {
        var options = ButlerSleeperCurrentSeasonHydrationEligibilityAuditCli.parse(new String[]{" l1 "});
        assertEquals("l1", options.leagueId());
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentSeasonHydrationEligibilityAuditCli.parse(new String[]{}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperCurrentSeasonHydrationEligibilityAuditCli.parse(new String[]{"l1", "extra"}));
    }

    @Test
    void rendersBootstrapWorkSeparatelyFromEligibilityBlockers() {
        var report = new SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityReport(
            SleeperCurrentSeasonHydrationEligibilityAudit.POLICY_ID,
            "l1",
            "Best",
            "provider-2026",
            2026,
            "in_season",
            1,
            2,
            2,
            2,
            7,
            7,
            5,
            2,
            List.of("rookie-a", "rookie-b"),
            List.of(),
            List.of(),
            List.of(),
            SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityState.READY_TO_HYDRATE,
            Instant.parse("2026-09-08T00:00:00Z"));

        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            ButlerSleeperCurrentSeasonHydrationEligibilityAuditCli.print(report);
        } finally {
            System.setOut(original);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("Hydration eligibility: READY_TO_HYDRATE"));
        assertTrue(rendered.contains("Current player identities to bootstrap: 2"));
        assertTrue(rendered.contains("Unmapped current player identities are expected bootstrap work"));
        assertTrue(rendered.contains("BF-599 is read-only"));
    }
}
