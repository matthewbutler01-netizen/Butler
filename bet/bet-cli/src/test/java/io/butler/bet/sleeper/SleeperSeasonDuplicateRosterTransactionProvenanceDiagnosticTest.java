package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperSeasonDuplicateRosterTransactionProvenanceDiagnosticTest {
    @Test
    void retainsExplicitTransactionHistoryAndFinalRosterWithoutCanonicalizing() throws Exception {
        var duplicateReport = duplicateReport();
        var diagnostic = new SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic(
            (leagueId, season) -> duplicateReport,
            new SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic.Source() {
                @Override
                public String transactions(String sleeperLeagueId, int round) {
                    if (round == 4) {
                        return """
                            [{"transaction_id":"tx-early","type":"trade","status":"complete","leg":4,
                              "created":100,"status_updated":110,"roster_ids":[9,7],
                              "adds":{"3198":9},"drops":{"3198":7}}]
                            """;
                    }
                    if (round == 17) {
                        return """
                            [
                              {"transaction_id":"tx-late","type":"waiver","status":"complete","leg":17,
                               "created":200,"status_updated":210,"roster_ids":[7,9],
                               "adds":{"3198":7},"drops":{"3198":9}},
                              {"transaction_id":"pending","type":"waiver","status":"pending","leg":17,
                               "roster_ids":[11,7],"adds":{"9509":7},"drops":{"9509":11}}
                            ]
                            """;
                    }
                    return "[]";
                }

                @Override
                public String rosters(String sleeperLeagueId) {
                    return """
                        [
                          {"roster_id":7,"players":["3198","x"]},
                          {"roster_id":9,"players":["y"]},
                          {"roster_id":11,"players":["9509"]}
                        ]
                        """;
                }
            });

        var report = diagnostic.diagnose("league-1", 2024);

        assertEquals("provider-1", report.sleeperLeagueId());
        assertEquals(2, report.players().size());

        var p3198 = report.players().get(0);
        assertEquals("3198", p3198.playerId());
        assertEquals(List.of(17, 18), p3198.duplicateWeeks());
        assertEquals(SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic.EvidenceState.MATCHING_COMPLETE_TRANSACTION_EVIDENCE,
            p3198.state());
        assertEquals(2, p3198.transactions().size());
        assertEquals("tx-early", p3198.transactions().get(0).transactionId());
        assertEquals(9, p3198.transactions().get(0).addedRosterId());
        assertEquals(7, p3198.transactions().get(0).droppedRosterId());
        assertEquals("tx-late", p3198.transactions().get(1).transactionId());
        assertEquals(List.of(7), p3198.finalRosterIds());

        var p9509 = report.players().get(1);
        assertEquals("9509", p9509.playerId());
        assertEquals(List.of(18), p9509.duplicateWeeks());
        assertEquals(SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic.EvidenceState.NO_MATCHING_COMPLETE_TRANSACTION,
            p9509.state());
        assertTrue(p9509.transactions().isEmpty());
        assertEquals(List.of(11), p9509.finalRosterIds());
    }

    @Test
    void ignoresIntraRosterOnlyDuplicates() throws Exception {
        var intra = new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DuplicateObservation(
            5,
            "p3",
            SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DuplicateKind.INTRA_ROSTER,
            List.of(new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.RowObservation(
                1, 1, 2, 0, BigDecimal.ZERO)));
        var duplicateReport = new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DiagnosticReport(
            SleeperSeasonProviderPointsDuplicateRosterDiagnostic.POLICY_ID,
            "league-1", "League", 2024, "provider-1", 1, 18, List.of(intra));
        var diagnostic = new SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic(
            (leagueId, season) -> duplicateReport,
            new SleeperSeasonDuplicateRosterTransactionProvenanceDiagnostic.Source() {
                @Override public String transactions(String sleeperLeagueId, int round) { return "[]"; }
                @Override public String rosters(String sleeperLeagueId) { return "[]"; }
            });

        assertTrue(diagnostic.diagnose("league-1", 2024).players().isEmpty());
    }

    private static SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DiagnosticReport duplicateReport() {
        var p3198w17 = new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DuplicateObservation(
            17,
            "3198",
            SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DuplicateKind.CROSS_ROSTER,
            List.of(
                new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.RowObservation(7, 7, 1, 1, new BigDecimal("24.5")),
                new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.RowObservation(9, 9, 1, 1, new BigDecimal("24.5"))));
        var p3198w18 = new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DuplicateObservation(
            18,
            "3198",
            SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DuplicateKind.CROSS_ROSTER,
            List.of(
                new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.RowObservation(7, 3, 1, 1, new BigDecimal("30.1")),
                new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.RowObservation(9, 5, 1, 1, new BigDecimal("30.1"))));
        var p9509w18 = new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DuplicateObservation(
            18,
            "9509",
            SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DuplicateKind.CROSS_ROSTER,
            List.of(
                new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.RowObservation(7, 3, 1, 1, new BigDecimal("31.3")),
                new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.RowObservation(11, 3, 1, 1, new BigDecimal("31.3"))));
        return new SleeperSeasonProviderPointsDuplicateRosterDiagnostic.DiagnosticReport(
            SleeperSeasonProviderPointsDuplicateRosterDiagnostic.POLICY_ID,
            "league-1", "League", 2024, "provider-1", 1, 18,
            List.of(p3198w17, p3198w18, p9509w18));
    }
}
