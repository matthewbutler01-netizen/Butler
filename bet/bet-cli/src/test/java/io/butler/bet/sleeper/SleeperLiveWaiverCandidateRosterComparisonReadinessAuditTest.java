package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverCandidateRosterComparisonReadinessAuditTest {

    @Test
    void exactAlignedFramesAuthorizeComparisonMethodologyAndRetainMissingRosterProduction() {
        var report = subject(candidateFrame("market-1"), rosterFrame("market-1"))
            .audit("league-1", "owner-1");

        assertEquals(
            SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.AuthorizationState.READY_FOR_COMPARISON_METHODOLOGY,
            report.state());
        assertEquals(5, report.candidateCount());
        assertEquals(3, report.reviewableCandidateCount());
        assertEquals(2, report.reviewableWithPriorProduction());
        assertEquals(1, report.reviewableWithoutPriorProduction());
        assertEquals(3, report.targetPlayerCount());
        assertEquals(2, report.targetPriorProductionPresent());
        assertEquals(1, report.targetPriorProductionMissing());
        assertEquals(1, report.missingRosterProduction().size());
        assertEquals("r3", report.missingRosterProduction().getFirst().sleeperPlayerId());
        assertEquals(1, report.candidatePositionCoverage().get("RB").withPriorProduction());
        assertEquals(1, report.candidatePositionCoverage().get("WR").withoutPriorProduction());
        assertEquals(1, report.rosterPositionCoverage().get("QB").starter());
        assertEquals(1, report.rosterPositionCoverage().get("WR").withoutPriorProduction());
    }

    @Test
    void marketSnapshotMismatchFailsClosed() {
        var error = assertThrows(IllegalStateException.class,
            () -> subject(candidateFrame("market-1"), rosterFrame("market-2"))
                .audit("league-1", "owner-1"));
        assertTrue(error.getMessage().contains("different BF-603 market snapshots"));
    }

    @Test
    void noReviewableCandidatesFailsClosed() {
        var frame = new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateFrame(
            "league-1", "market-1", 2,
            1, 1, 0, 0,
            List.of(
                new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateEntry(
                    "c1", "Blocked One", null, false, false),
                new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateEntry(
                    "c2", "Blocked Two", null, false, false)));

        var error = assertThrows(IllegalStateException.class,
            () -> subject(frame, rosterFrame("market-1")).audit("league-1", "owner-1"));
        assertTrue(error.getMessage().contains("no evidence-reviewable candidates"));
    }

    @Test
    void targetProductionPartitionMismatchFailsClosed() {
        var roster = new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterFrame(
            "league-1", "market-1", "waiver-1", "sleeper-1",
            2026, "in_season", 1, "owner-1", 1,
            3, 2, 1, 0, 0,
            1, 1,
            rosterEntries());

        var error = assertThrows(IllegalStateException.class,
            () -> subject(candidateFrame("market-1"), roster).audit("league-1", "owner-1"));
        assertTrue(error.getMessage().contains("target production partition does not reconcile"));
    }

    @Test
    void unsupportedReviewableCandidatePositionFailsClosed() {
        var frame = new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateFrame(
            "league-1", "market-1", 1,
            0, 0, 1, 0,
            List.of(new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateEntry(
                "c1", "Unsupported", "K", true, true)));

        var error = assertThrows(IllegalStateException.class,
            () -> subject(frame, rosterFrame("market-1")).audit("league-1", "owner-1"));
        assertTrue(error.getMessage().contains("unsupported position K"));
    }

    @Test
    void nonExactTargetIdentityFailsClosed() {
        var entries = List.of(
            new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterEntry(
                "r1", "Roster QB", "QB", "STARTER", "EXACT_CANONICAL", true),
            new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterEntry(
                "r2", "Roster RB", "RB", "STARTER", "UNMAPPED_CANONICAL", true),
            new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterEntry(
                "r3", "Roster WR", "WR", "BENCH", "EXACT_CANONICAL", false));
        var roster = new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterFrame(
            "league-1", "market-1", "waiver-1", "sleeper-1",
            2026, "in_season", 1, "owner-1", 1,
            3, 2, 1, 0, 0,
            2, 1,
            entries);

        var error = assertThrows(IllegalStateException.class,
            () -> subject(candidateFrame("market-1"), roster).audit("league-1", "owner-1"));
        assertTrue(error.getMessage().contains("non-exact target-roster identity r2"));
    }

    private static SleeperLiveWaiverCandidateRosterComparisonReadinessAudit subject(
        SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateFrame candidates,
        SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterFrame roster) {
        return new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit(
            ignored -> candidates,
            (ignoredLeague, ignoredOwner) -> roster);
    }

    private static SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateFrame candidateFrame(
        String marketSnapshot) {
        return new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateFrame(
            "league-1", marketSnapshot, 5,
            1, 1, 2, 1,
            List.of(
                new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateEntry(
                    "c1", "Team Unknown", null, false, false),
                new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateEntry(
                    "c2", "Depth Missing", null, false, false),
                new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateEntry(
                    "c3", "Review RB", "RB", true, true),
                new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateEntry(
                    "c4", "Review QB", "QB", true, true),
                new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateEntry(
                    "c5", "Review WR New", "WR", true, false)));
    }

    private static SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterFrame rosterFrame(
        String marketSnapshot) {
        return new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterFrame(
            "league-1", marketSnapshot, "waiver-1", "sleeper-1",
            2026, "in_season", 1, "owner-1", 1,
            3, 2, 1, 0, 0,
            2, 1,
            rosterEntries());
    }

    private static List<SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterEntry> rosterEntries() {
        return List.of(
            new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterEntry(
                "r1", "Roster QB", "QB", "STARTER", "EXACT_CANONICAL", true),
            new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterEntry(
                "r2", "Roster RB", "RB", "STARTER", "EXACT_CANONICAL", true),
            new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterEntry(
                "r3", "Roster WR", "WR", "BENCH", "EXACT_CANONICAL", false));
    }
}
