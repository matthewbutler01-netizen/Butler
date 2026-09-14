package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SleeperLiveWaiverComparisonReadinessReuseBf761Test {
    @Test
    void scopesExactCandidateReadinessAcrossRosterEvidenceLoadAndClearsAfterward() throws Exception {
        var readinessReport = readinessReport("L");
        var candidateEvidence = new SleeperLiveWaiverComparisonEvidenceReuse.CandidateEvidence(
            candidateReadinessFrame(), candidateComparisonFrame(), readinessReport);
        var rosterEvidence = rosterEvidence();
        AtomicInteger rosterCalls = new AtomicInteger();

        var reuse = new SleeperLiveWaiverComparisonEvidenceReuse(
            leagueId -> candidateEvidence,
            (leagueId, ownerId) -> {
                rosterCalls.incrementAndGet();
                assertSame(readinessReport,
                    SleeperLiveWaiverPregameEvidenceReadinessAudit.scopedReadiness(leagueId));
                return rosterEvidence;
            },
            leagueId -> scoring(),
            (playerIds, season) -> List.of());

        var result = reuse.runMeasured("L", "owner");

        assertEquals(1, rosterCalls.get());
        assertEquals(1, result.bundle().comparisons().pairCount());
        assertNull(SleeperLiveWaiverPregameEvidenceReadinessAudit.scopedReadiness("L"));
    }

    @Test
    void scopedReadinessRejectsLeagueDriftAndAlwaysClearsOnFailure() throws Exception {
        var readinessReport = readinessReport("L");

        assertThrows(IllegalStateException.class, () ->
            SleeperLiveWaiverPregameEvidenceReadinessAudit.withScopedReuse(
                readinessReport, "OTHER", () -> null));
        assertNull(SleeperLiveWaiverPregameEvidenceReadinessAudit.scopedReadiness("L"));

        assertThrows(IOException.class, () ->
            SleeperLiveWaiverPregameEvidenceReadinessAudit.withScopedReuse(
                readinessReport, "L", () -> {
                    throw new IOException("expected");
                }));
        assertNull(SleeperLiveWaiverPregameEvidenceReadinessAudit.scopedReadiness("L"));
    }

    private static SleeperLiveWaiverPregameEvidenceReadinessAudit.ReadinessReport readinessReport(String leagueId) {
        var summaries = new EnumMap<
            SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum,
            SleeperLiveWaiverPregameEvidenceReadinessAudit.StratumSummary>(
                SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.class);
        for (var stratum : SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.values()) {
            summaries.put(stratum,
                new SleeperLiveWaiverPregameEvidenceReadinessAudit.StratumSummary(0, 0, 0, 0, 0, 0));
        }
        return new SleeperLiveWaiverPregameEvidenceReadinessAudit.ReadinessReport(
            SleeperLiveWaiverPregameEvidenceReadinessAudit.POLICY_ID,
            SleeperLiveWaiverPregameEvidenceDossier.POLICY_ID,
            leagueId,
            "M",
            "2026-09-14T00:00:00Z",
            "A",
            "2026-09-14T00:00:00Z",
            "C",
            "2026-09-14T00:00:00Z",
            "CURRENT_WEEK_OBSERVED",
            2026,
            1,
            "Regular",
            0,
            summaries,
            List.of());
    }

    private static SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateFrame candidateReadinessFrame() {
        return new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateFrame(
            "L", "M", 1, 0, 0, 0, 1,
            List.of(new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateEntry(
                "c1", "Candidate", "RB", true, false)));
    }

    private static SleeperLiveWaiverComparisonExecutionBundle.CandidateFrame candidateComparisonFrame() {
        return new SleeperLiveWaiverComparisonExecutionBundle.CandidateFrame(
            "L", "M", 1, 1,
            List.of(new SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry(
                "c1", "Candidate", "RB", "BC1", false,
                10, 2, 8, "ADD_ONLY", "TM", "Active", null, "RB", 2)));
    }

    private static SleeperLiveWaiverComparisonEvidenceReuse.RosterEvidence rosterEvidence() {
        return new SleeperLiveWaiverComparisonEvidenceReuse.RosterEvidence(
            new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterFrame(
                "L", "M", "W", "S", 2026, "in_season", 1, "owner", 1,
                1, 0, 1, 0, 0, 0, 1,
                List.of(new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterEntry(
                    "r1", "Roster", "RB", "BENCH", "EXACT_CANONICAL", false))),
            new SleeperLiveWaiverComparisonExecutionBundle.RosterFrame(
                "L", "M", "W", "S", "owner", 1,
                1, 0, 1, 0, 0,
                List.of(new SleeperLiveWaiverComparisonExecutionBundle.RosterEntry(
                    "r1", "Roster", "RB", "BENCH", "BR1", false))));
    }

    private static Map<String, Double> scoring() {
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("pass_yd", 0.04);
        values.put("pass_td", 4.0);
        values.put("rush_yd", 0.1);
        values.put("rush_td", 6.0);
        values.put("rec", 1.0);
        values.put("rec_yd", 0.1);
        values.put("rec_td", 6.0);
        return values;
    }
}
