package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverCoalescedComparisonEvidenceBf762Test {
    @Test
    void executesOneRosterContextAndFeedsExactReportToComparisonAndCaller() throws Exception {
        var candidateEvidence = candidateEvidence();
        var rosterEvidence = rosterEvidence();
        var context = context("L", "owner");
        AtomicInteger contextCalls = new AtomicInteger();
        AtomicInteger rosterEvidenceCalls = new AtomicInteger();
        AtomicReference<SleeperLiveWaiverTargetRosterContextAudit.AuditReport> receivedContext =
            new AtomicReference<>();

        var coalesced = new SleeperLiveWaiverCoalescedComparisonEvidence(
            leagueId -> candidateEvidence,
            (leagueId, ownerId) -> {
                contextCalls.incrementAndGet();
                return context;
            },
            (leagueId, ownerId, suppliedContext) -> {
                rosterEvidenceCalls.incrementAndGet();
                receivedContext.set(suppliedContext);
                return rosterEvidence;
            },
            leagueId -> scoring(),
            (playerIds, season) -> List.of());

        var measured = coalesced.runMeasured("L", "owner");

        assertEquals(1, contextCalls.get());
        assertEquals(1, rosterEvidenceCalls.get());
        assertSame(context, receivedContext.get());
        assertSame(context, measured.rosterContext());
        assertEquals(1, measured.bundle().comparisons().pairCount());
        assertTrue(measured.rosterContextMs() >= 0);
    }

    @Test
    void failsClosedBeforeRosterEvidenceWhenContextLeagueDrifts() {
        AtomicInteger rosterEvidenceCalls = new AtomicInteger();
        var coalesced = new SleeperLiveWaiverCoalescedComparisonEvidence(
            leagueId -> candidateEvidence(),
            (leagueId, ownerId) -> context("OTHER", ownerId),
            (leagueId, ownerId, suppliedContext) -> {
                rosterEvidenceCalls.incrementAndGet();
                return rosterEvidence();
            },
            leagueId -> scoring(),
            (playerIds, season) -> List.of());

        IllegalStateException error = assertThrows(
            IllegalStateException.class,
            () -> coalesced.runMeasured("L", "owner"));

        assertTrue(error.getMessage().contains("BF-762 BLOCKED"));
        assertEquals(0, rosterEvidenceCalls.get());
    }

    private static SleeperLiveWaiverComparisonEvidenceReuse.CandidateEvidence candidateEvidence() {
        return new SleeperLiveWaiverComparisonEvidenceReuse.CandidateEvidence(
            new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateFrame(
                "L", "M", 1, 0, 0, 0, 1,
                List.of(new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateEntry(
                    "c1", "Candidate", "RB", true, false))),
            new SleeperLiveWaiverComparisonExecutionBundle.CandidateFrame(
                "L", "M", 1, 1,
                List.of(new SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry(
                    "c1", "Candidate", "RB", "BC1", false,
                    10, 2, 8, "ADD_ONLY", "TM", "Active", null, "RB", 2))));
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

    private static SleeperLiveWaiverTargetRosterContextAudit.AuditReport context(
        String leagueId,
        String ownerId) {
        return new SleeperLiveWaiverTargetRosterContextAudit.AuditReport(
            SleeperLiveWaiverTargetRosterContextAudit.POLICY_ID,
            leagueId,
            "M",
            "W",
            "S",
            2026,
            "in_season",
            1,
            ownerId,
            "Owner",
            "Team",
            1,
            "BT1",
            "Butler Team",
            List.of("BN"),
            List.of(),
            1,
            1,
            1,
            0,
            1,
            0,
            0,
            1,
            0,
            List.of(new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "r1", "BENCH", null, null, "BR1", "Roster", "RB", "TM", "EXACT_CANONICAL")));
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
