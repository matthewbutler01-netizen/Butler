package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperCurrentSeasonHydrationEligibilityAuditTest {
    @Test
    void blocksDraftingEmptyRostersWithExplicitStatusPlayerAndStarterReasons() throws Exception {
        var live = liveReport(
            "drafting",
            List.of(
                roster(1, "u1", 0, 0),
                roster(2, "u2", 0, 0)),
            0,
            List.of(),
            List.of(),
            0,
            List.of());
        var audit = new SleeperCurrentSeasonHydrationEligibilityAudit(ignored -> live);

        var report = audit.audit("l1");

        assertEquals(SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityState.BLOCKED, report.state());
        assertEquals(List.of(1, 2), report.playerlessRosterIds());
        assertEquals(List.of(1, 2), report.starterlessRosterIds());
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("status is drafting")));
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("no current player identities")));
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("no starter identities")));
    }

    @Test
    void allowsExactStructuralHydrationEvenWhenSomeCurrentPlayersAreNotMappedYet() throws Exception {
        var live = liveReport(
            "in_season",
            List.of(
                roster(1, "u1", 3, 2),
                roster(2, "u2", 4, 2)),
            2,
            List.of("rookie-a", "rookie-b"),
            List.of(),
            0,
            List.of());
        var audit = new SleeperCurrentSeasonHydrationEligibilityAudit(ignored -> live);

        var report = audit.audit("l1");

        assertEquals(SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityState.READY_TO_HYDRATE, report.state());
        assertEquals(2, report.unmappedCurrentPlayerIds());
        assertEquals(List.of("rookie-a", "rookie-b"), report.unmappedPlayerExamples());
        assertTrue(report.blockers().isEmpty());
    }

    @Test
    void blocksTeamRosterOwnerAndConfigurationMismatches() throws Exception {
        var live = new SleeperLiveSeasonOperationalReadinessAudit.AuditReport(
            SleeperLiveSeasonOperationalReadinessAudit.POLICY_ID,
            "l1",
            "Best",
            "provider-2026",
            2026,
            2026,
            "in_season",
            1,
            2,
            0,
            0,
            1,
            2,
            4,
            4,
            4,
            0,
            List.of(),
            List.of("2"),
            List.of("3"),
            1,
            List.of("missing-owner"),
            List.of(roster(1, null, 2, 1), roster(2, "missing-owner", 2, 1)),
            blocked("structural mismatch"),
            blocked("structural mismatch"),
            notAudited(),
            blocked("structural mismatch"),
            Instant.parse("2026-09-08T00:00:00Z"));
        var audit = new SleeperCurrentSeasonHydrationEligibilityAudit(ignored -> live);

        var report = audit.audit("l1");

        assertEquals(SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityState.BLOCKED, report.state());
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("team count")));
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("roster_positions")));
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("scoring_settings")));
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("missing persisted Butler teams")));
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("no owner_id")));
        assertTrue(report.blockers().stream().anyMatch(value -> value.contains("absent from provider user list")));
    }

    private static SleeperLiveSeasonOperationalReadinessAudit.AuditReport liveReport(
        String status,
        List<SleeperLiveSeasonOperationalReadinessAudit.RosterOwnerObservation> rosters,
        int unmappedCount,
        List<String> unmappedExamples,
        List<String> providerMissingTeams,
        int ownerless,
        List<String> unknownOwners) {
        int entries = rosters.stream().mapToInt(SleeperLiveSeasonOperationalReadinessAudit.RosterOwnerObservation::playerCount).sum();
        int distinct = entries;
        int exactMapped = Math.max(0, distinct - unmappedCount);
        var baseReadiness = "in_season".equals(status)
            ? ready()
            : blocked("provider not in season");
        return new SleeperLiveSeasonOperationalReadinessAudit.AuditReport(
            SleeperLiveSeasonOperationalReadinessAudit.POLICY_ID,
            "l1",
            "Best",
            "provider-2026",
            2026,
            2026,
            status,
            1,
            rosters.size(),
            9,
            20,
            rosters.size(),
            rosters.size(),
            entries,
            distinct,
            exactMapped,
            unmappedCount,
            unmappedExamples,
            providerMissingTeams,
            List.of(),
            ownerless,
            unknownOwners,
            rosters,
            baseReadiness,
            baseReadiness,
            notAudited(),
            baseReadiness,
            Instant.parse("2026-09-08T00:00:00Z"));
    }

    private static SleeperLiveSeasonOperationalReadinessAudit.RosterOwnerObservation roster(
        int id,
        String ownerId,
        int players,
        int starters) {
        return new SleeperLiveSeasonOperationalReadinessAudit.RosterOwnerObservation(
            id, ownerId, ownerId != null, players, starters);
    }

    private static SleeperLiveSeasonOperationalReadinessAudit.CapabilityReadiness ready() {
        return new SleeperLiveSeasonOperationalReadinessAudit.CapabilityReadiness(
            SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.READY,
            List.of());
    }

    private static SleeperLiveSeasonOperationalReadinessAudit.CapabilityReadiness blocked(String reason) {
        return new SleeperLiveSeasonOperationalReadinessAudit.CapabilityReadiness(
            SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.BLOCKED,
            List.of(reason));
    }

    private static SleeperLiveSeasonOperationalReadinessAudit.CapabilityReadiness notAudited() {
        return new SleeperLiveSeasonOperationalReadinessAudit.CapabilityReadiness(
            SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.NOT_YET_AUDITED,
            List.of("not audited"));
    }
}
