package io.butler.bet.sleeper;

import io.butler.bet.intelligence.NflversePlayerSeasonProductionImporter;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverTargetRosterProductionHydrationTest {

    @Test
    void hydratesOnlyMissingTargetsAndPreservesExactUnmatchedAsEvidenceGap() throws Exception {
        var pre = report("market-1", List.of(
            coverage("1001", "STARTER", present()),
            coverage("1002", "BENCH", missing()),
            coverage("1003", "BENCH", missing())));
        var post = report("market-1", List.of(
            coverage("1001", "STARTER", present()),
            coverage("1002", "BENCH", present()),
            coverage("1003", "BENCH", missing())));
        AtomicInteger audits = new AtomicInteger();
        AtomicReference<Set<String>> importedIds = new AtomicReference<>();
        FakeBackupStore backup = new FakeBackupStore();

        var subject = new SleeperLiveWaiverTargetRosterProductionHydration(
            (league, owner) -> audits.getAndIncrement() == 0 ? pre : post,
            ids -> {
                importedIds.set(ids);
                return importResult(2, 1, List.of(unmatched("1003")));
            },
            backup);

        var result = subject.hydrate("league-1", "owner-1");

        assertEquals(Set.of("1002", "1003"), importedIds.get());
        assertFalse(importedIds.get().contains("1001"));
        assertEquals(2, result.missingTargetsHydrated());
        assertEquals(1, result.targetMatchedPlayers());
        assertEquals(1, result.unmatched().size());
        assertEquals(2, result.postPresent());
        assertEquals(1, result.postMissing());
        assertEquals(SleeperLiveWaiverTargetRosterProductionHydration.HydrationState.HYDRATED_VERIFIED, result.state());
        assertFalse(backup.restored.get());
    }

    @Test
    void rollsBackWhenPostFrameChanges() throws Exception {
        var pre = report("market-1", List.of(
            coverage("1001", "STARTER", present()),
            coverage("1002", "BENCH", missing())));
        var post = report("market-2", List.of(
            coverage("1001", "STARTER", present()),
            coverage("1002", "BENCH", present())));
        AtomicInteger audits = new AtomicInteger();
        FakeBackupStore backup = new FakeBackupStore();

        var subject = new SleeperLiveWaiverTargetRosterProductionHydration(
            (league, owner) -> audits.getAndIncrement() == 0 ? pre : post,
            ids -> importResult(1, 1, List.of()),
            backup);

        var error = assertThrows(
            SleeperLiveWaiverTargetRosterProductionHydration.HydrationRollbackException.class,
            () -> subject.hydrate("league-1", "owner-1"));

        assertTrue(error.restored());
        assertTrue(backup.restored.get());
        assertTrue(error.getCause().getMessage().contains("market snapshot changed"));
    }

    @Test
    void rollsBackWhenImporterFails() throws Exception {
        var pre = report("market-1", List.of(
            coverage("1001", "STARTER", present()),
            coverage("1002", "BENCH", missing())));
        FakeBackupStore backup = new FakeBackupStore();
        var subject = new SleeperLiveWaiverTargetRosterProductionHydration(
            (league, owner) -> pre,
            ids -> { throw new IllegalStateException("crosswalk ambiguity"); },
            backup);

        var error = assertThrows(
            SleeperLiveWaiverTargetRosterProductionHydration.HydrationRollbackException.class,
            () -> subject.hydrate("league-1", "owner-1"));

        assertTrue(error.restored());
        assertTrue(backup.restored.get());
        assertTrue(error.getCause().getMessage().contains("crosswalk ambiguity"));
    }

    @Test
    void noWriteWhenEveryTargetAlreadyHasPriorProduction() throws Exception {
        var complete = report("market-1", List.of(
            coverage("1001", "STARTER", present()),
            coverage("1002", "BENCH", present())));
        AtomicBoolean importerCalled = new AtomicBoolean();
        FakeBackupStore backup = new FakeBackupStore();
        var subject = new SleeperLiveWaiverTargetRosterProductionHydration(
            (league, owner) -> complete,
            ids -> {
                importerCalled.set(true);
                return importResult(ids.size(), ids.size(), List.of());
            },
            backup);

        var result = subject.hydrate("league-1", "owner-1");

        assertEquals(SleeperLiveWaiverTargetRosterProductionHydration.HydrationState.ALREADY_COMPLETE, result.state());
        assertFalse(importerCalled.get());
        assertFalse(backup.created.get());
    }

    private static SleeperLiveWaiverTargetRosterProductionComparabilityAudit.AuditReport report(
        String marketSnapshot,
        List<SleeperLiveWaiverTargetRosterProductionComparabilityAudit.TargetPlayerCoverage> players) {
        int present = (int) players.stream().filter(p -> p.state()
            == SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_PRESENT).count();
        int missing = players.size() - present;
        int starters = (int) players.stream().filter(p -> "STARTER".equals(p.target().rosterSlot())).count();
        int bench = players.size() - starters;
        return new SleeperLiveWaiverTargetRosterProductionComparabilityAudit.AuditReport(
            SleeperLiveWaiverTargetRosterProductionComparabilityAudit.POLICY_ID,
            SleeperLiveWaiverTargetRosterContextAudit.POLICY_ID,
            "league-1", marketSnapshot, "waiver-1", "sleeper-league-1",
            2026, "in_season", 1,
            "owner-1", "Owner", "Team", 1, "team-1", "Team",
            51, 42, 2025,
            players.size(), starters, bench, 0, 0,
            present, missing, Map.of(), players);
    }

    private static SleeperLiveWaiverTargetRosterProductionComparabilityAudit.TargetPlayerCoverage coverage(
        String sleeperId,
        String rosterSlot,
        SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState state) {
        Integer ordinal = "STARTER".equals(rosterSlot) ? 0 : null;
        String lineup = "STARTER".equals(rosterSlot) ? "QB" : null;
        var target = new SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer(
            sleeperId, rosterSlot, ordinal, lineup, "player-" + sleeperId,
            "Player " + sleeperId, "RB", "KC", "EXACT_CANONICAL");
        List<SleeperLiveWaiverTargetRosterProductionComparabilityAudit.ProductionObservation> production =
            state == SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_PRESENT
                ? List.of(new SleeperLiveWaiverTargetRosterProductionComparabilityAudit.ProductionObservation(
                    "nflverse", LocalDate.of(2026, 9, 6), 17, 0, 0, 500, 4, 20, 150, 1))
                : List.of();
        return new SleeperLiveWaiverTargetRosterProductionComparabilityAudit.TargetPlayerCoverage(
            target, state, production);
    }

    private static SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState present() {
        return SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_PRESENT;
    }

    private static SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState missing() {
        return SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_MISSING;
    }

    private static NflversePlayerSeasonProductionImporter.UnmatchedPlayer unmatched(String sleeperId) {
        return new NflversePlayerSeasonProductionImporter.UnmatchedPlayer(
            "player-" + sleeperId, sleeperId, "Player " + sleeperId);
    }

    private static NflversePlayerSeasonProductionImporter.ImportResult importResult(
        int eligible,
        int matched,
        List<NflversePlayerSeasonProductionImporter.UnmatchedPlayer> unmatched) {
        return new NflversePlayerSeasonProductionImporter.ImportResult(
            2025, LocalDate.of(2026, 9, 8), true,
            100, 100, 5000, matched, eligible, matched, unmatched.size(), matched, unmatched);
    }

    private static final class FakeBackupStore implements SleeperLiveWaiverTargetRosterProductionHydration.BackupStore {
        private final AtomicBoolean created = new AtomicBoolean();
        private final AtomicBoolean restored = new AtomicBoolean();

        @Override
        public SleeperLiveWaiverTargetRosterProductionHydration.BackupHandle create() {
            created.set(true);
            return new SleeperLiveWaiverTargetRosterProductionHydration.BackupHandle("backup.db");
        }

        @Override
        public void restore(SleeperLiveWaiverTargetRosterProductionHydration.BackupHandle backup) {
            restored.set(true);
        }
    }
}
