package io.butler.bet.cli;

import io.butler.bet.sleeper.SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperSeasonDuplicateRosterTransactionProvenanceDiagnosticCliTest {
    @Test
    void parsesExactlyLeagueAndSeason() {
        var options = ButlerSleeperSeasonDuplicateRosterTransactionProvenanceDiagnosticCli.parse(
            new String[]{" league-1 ", "2024"});

        assertEquals("league-1", options.leagueId());
        assertEquals(2024, options.season());
    }

    @Test
    void rejectsMalformedArguments() {
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperSeasonDuplicateRosterTransactionProvenanceDiagnosticCli.parse(
                new String[]{"league-1"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperSeasonDuplicateRosterTransactionProvenanceDiagnosticCli.parse(
                new String[]{"league-1", "x"}));
        assertThrows(IllegalArgumentException.class,
            () -> ButlerSleeperSeasonDuplicateRosterTransactionProvenanceDiagnosticCli.parse(
                new String[]{"league-1", "1900"}));
    }

    @Test
    void rendersExplicitTransactionAndNoCanonicalizationBoundary() {
        var transaction = new SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic.TransactionObservation(
            17, "tx-1", "trade", 17, 100L, 110L, List.of(7, 9), 7, 9);
        var player = new SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic.PlayerProvenance(
            "3198",
            List.of(17, 18),
            List.of(transaction),
            List.of(7),
            SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic.EvidenceState.MATCHING_COMPLETE_TRANSACTION_EVIDENCE);
        var report = new SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic.DiagnosticReport(
            SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic.POLICY_ID,
            "league-1", "League", 2024, "provider-1", 1, 18, List.of(player));

        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            ButlerSleeperSeasonDuplicateRosterTransactionProvenanceDiagnosticCli.print(report);
        } finally {
            System.setOut(original);
        }

        String rendered = output.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("player=3198 | duplicate weeks=[17, 18]"));
        assertTrue(rendered.contains("transaction=tx-1"));
        assertTrue(rendered.contains("add roster=7"));
        assertTrue(rendered.contains("drop roster=9"));
        assertTrue(rendered.contains("final provider rosters=[7]"));
        assertTrue(rendered.contains("do not choose a canonical historical roster"));
    }
}
