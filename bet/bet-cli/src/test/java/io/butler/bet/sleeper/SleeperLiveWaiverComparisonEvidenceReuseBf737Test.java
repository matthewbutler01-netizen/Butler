package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverComparisonEvidenceReuseBf737Test {
    @Test
    void reusesOneCandidateAndRosterEvidenceLoadAcrossMethodologyAndComparison() throws Exception {
        var candidateEvidence = new SleeperLiveWaiverComparisonEvidenceReuse.CandidateEvidence(
            new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateFrame(
                "L", "M", 1, 0, 0, 0, 1,
                List.of(new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateEntry(
                    "c1", "Candidate", "RB", true, false))),
            new SleeperLiveWaiverComparisonExecutionBundle.CandidateFrame(
                "L", "M", 1, 1,
                List.of(new SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry(
                    "c1", "Candidate", "RB", "BC1", false,
                    10, 2, 8, "ADD_ONLY", "TM", "Active", null, "RB", 2))));
        var rosterEvidence = new SleeperLiveWaiverComparisonEvidenceReuse.RosterEvidence(
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

        AtomicInteger candidateCalls = new AtomicInteger();
        AtomicInteger rosterCalls = new AtomicInteger();
        AtomicInteger scoringCalls = new AtomicInteger();
        AtomicInteger productionCalls = new AtomicInteger();

        var reuse = new SleeperLiveWaiverComparisonEvidenceReuse(
            leagueId -> {
                candidateCalls.incrementAndGet();
                return candidateEvidence;
            },
            (leagueId, ownerId) -> {
                rosterCalls.incrementAndGet();
                return rosterEvidence;
            },
            leagueId -> {
                scoringCalls.incrementAndGet();
                return scoring();
            },
            (playerIds, season) -> {
                productionCalls.incrementAndGet();
                return List.of();
            });

        var measured = reuse.runMeasured("L", "owner");

        assertEquals(1, candidateCalls.get());
        assertEquals(1, rosterCalls.get());
        assertEquals(1, scoringCalls.get());
        assertEquals(0, productionCalls.get());
        assertEquals("M", measured.bundle().methodology().marketSnapshotId());
        assertEquals("W", measured.bundle().methodology().waiverSnapshotId());
        assertEquals(1, measured.bundle().comparisons().pairCount());
        assertEquals(1, measured.bundle().comparisons().pairCounts().newcomerNonnumeric());
        assertTrue(measured.timing().candidateEvidenceMs() >= 0);
        assertTrue(measured.timing().rosterEvidenceMs() >= 0);
        assertTrue(measured.timing().methodologyMs() >= 0);
        assertTrue(measured.timing().residualMs() >= 0);
        assertTrue(measured.timing().totalMs() >= 0);
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
