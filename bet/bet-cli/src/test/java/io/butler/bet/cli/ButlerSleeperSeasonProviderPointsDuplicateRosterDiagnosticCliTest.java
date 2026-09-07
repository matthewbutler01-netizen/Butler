package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperSeasonProviderPointsDuplicateRosterDiagnostic;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperSeasonProviderPointsDuplicateRosterDiagnosticCliTest {
    @Test
    void parsesExactlyLeagueAndSeason() {
        var options = ButlerSleeperSeasonProviderPointsDuplicateRosterDiagnosticCli.parse(
            new String[]{" league-1 ", "2024"});

        assertEquals("league-1", options.leagueId());
        assertEquals(2024, options.season());
    }

    @Test
    void rejectsMalformedArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperSeasonProviderPointsDuplicateRosterDiagnosticCli.parse(new String[]{"league-1"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperSeasonProviderPointsDuplicateRosterDiagnosticCli.parse(
                new String[]{"league-1", "not-a-season"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperSeasonProviderPointsDuplicateRosterDiagnosticCli.parse(
                new String[]{"league-1", "1900"}));
    }

    @Test
    void rendersBothSourceRowsAndReadOnlyBoundary() {
        var duplicate = new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DuplicateObservation(
            17,
            "3198",
            SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DuplicateKind.CROSS_ROSTER,
            List.of(
                new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.RowObservation(
                    3, 1, 1, 1, new BigDecimal("12.5")),
                new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.RowObservation(
                    8, 1, 1, 0, new BigDecimal("0.0"))));
        var report = new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DiagnosticReport(
            SleeperSeasonProviderPointsDuplicateRosterDiagnostic.POLICY_ID,
            "league-1",
            "League",
            2024,
            "provider-1",
            1,
            18,
            List.of(duplicate));

        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            ButlerSleeperSeasonProviderPointsDuplicateRosterDiagnosticCli.print(report);
        } finally {
            System.setOut(original);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("week 17 | player=3198 | kind=CROSS_ROSTER"));
        assertTrue(rendered.contains("roster=3"));
        assertTrue(rendered.contains("roster=8"));
        assertTrue(rendered.contains("does not choose a canonical roster"));
        assertTrue(rendered.contains("does not choose a canonical roster") || rendered.contains("does not choose"));
    }
}
