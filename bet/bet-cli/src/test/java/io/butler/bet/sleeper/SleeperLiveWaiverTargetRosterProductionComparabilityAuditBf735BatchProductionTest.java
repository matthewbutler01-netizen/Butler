package io.butler.bet.sleeper;

import io.butler.bet.domain.PlayerSeasonProduction;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SleeperLiveWaiverTargetRosterProductionComparabilityAuditBf735BatchProductionTest {
    @Test
    void loadsExactTargetRosterProductionOnceFor2025() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Set<String>> requestedIds = new AtomicReference<>();

        var audit = new SleeperLiveWaiverTargetRosterProductionComparabilityAudit(
            (leagueId, ownerId) -> exactContext(),
            (playerIds, season) -> {
                calls.incrementAndGet();
                requestedIds.set(new LinkedHashSet<>(playerIds));
                assertEquals(2025, season);
                return List.of(
                    production("B1", 100),
                    production("B2", 200));
            });

        var report = audit.audit("L", "owner-1");

        assertEquals(1, calls.get());
        assertEquals(Set.of("B1", "B2", "B3"), requestedIds.get());
        assertEquals(2, report.priorProductionPresent());
        assertEquals(1, report.priorProductionMissing());
        assertEquals(
            SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_MISSING,
            report.players().get(2).state());
    }

    private static PlayerSeasonProduction production(String playerId, int rushingYards) {
        return PlayerSeasonProduction.create(
            playerId, 2025, 10,
            0, 0, 0,
            rushingYards, 1,
            10, 100, 1,
            0, "nflverse", LocalDate.of(2026, 2, 1));
    }

    private static SleeperLiveWaiverTargetRosterContextAudit.AuditReport exactContext() {
        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> players = List.of(
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "p1", "STARTER", 0, "QB", "B1", "Player One", "QB", "A", "EXACT_CANONICAL"),
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "p2", "BENCH", null, null, "B2", "Player Two", "RB", "B", "EXACT_CANONICAL"),
            new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
                "p3", "RESERVE", null, null, "B3", "Player Three", "WR", "C", "EXACT_CANONICAL"));
        return new SleeperLiveWaiverTargetRosterContextAudit.AuditReport(
            SleeperLiveWaiverTargetRosterContextAudit.POLICY_ID,
            "L", "M", "W", "S", 2026, "in_season", 1,
            "owner-1", "Owner", "Team", 1, "T", "Team",
            List.of("QB", "BN"), List.of("QB"),
            51, 42, 3, 1, 1, 1, 0, 3, 0, players);
    }
}
