package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueValueFormat;
import io.butler.bet.domain.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperCurrentSeasonRosterBootstrapTest {
    @TempDir Path tempDir;

    @Test
    void successfulBootstrapRetainsBackupAndRequiresVerifiedPostAudit() throws Exception {
        TrackingBackupStore backups = new TrackingBackupStore();
        var bootstrap = new SleeperCurrentSeasonRosterBootstrap(
            ignored -> readyPreflight(2, 4, 2),
            ignored -> importResult(2, 4, 4),
            ignored -> readyPostAudit(2, 4, 4, 0),
            backups);

        var report = bootstrap.bootstrap("l1", "provider-2026");

        assertEquals(SleeperCurrentSeasonRosterBootstrap.BootstrapState.HYDRATED_VERIFIED, report.state());
        assertEquals(1, backups.created.get());
        assertEquals(0, backups.restored.get());
        assertEquals(2, report.preflightUnmappedPlayerIds());
        assertEquals(4, report.postExactMappedCurrentPlayers());
        assertEquals(0, report.postUnmappedCurrentPlayers());
    }

    @Test
    void blockedPreflightDoesNotCreateBackupOrInvokeImporter() throws Exception {
        TrackingBackupStore backups = new TrackingBackupStore();
        AtomicInteger imports = new AtomicInteger();
        var bootstrap = new SleeperCurrentSeasonRosterBootstrap(
            ignored -> blockedPreflight(),
            ignored -> {
                imports.incrementAndGet();
                return importResult(2, 4, 4);
            },
            ignored -> readyPostAudit(2, 4, 4, 0),
            backups);

        assertThrows(IllegalStateException.class,
            () -> bootstrap.bootstrap("l1", "provider-2026"));
        assertEquals(0, backups.created.get());
        assertEquals(0, backups.restored.get());
        assertEquals(0, imports.get());
    }

    @Test
    void wrongExpectedProviderDoesNotCreateBackupOrInvokeImporter() throws Exception {
        TrackingBackupStore backups = new TrackingBackupStore();
        AtomicInteger imports = new AtomicInteger();
        var bootstrap = new SleeperCurrentSeasonRosterBootstrap(
            ignored -> readyPreflight(2, 4, 0),
            ignored -> {
                imports.incrementAndGet();
                return importResult(2, 4, 4);
            },
            ignored -> readyPostAudit(2, 4, 4, 0),
            backups);

        assertThrows(IllegalStateException.class,
            () -> bootstrap.bootstrap("l1", "wrong-provider"));
        assertEquals(0, backups.created.get());
        assertEquals(0, imports.get());
    }

    @Test
    void importerFrameDriftRestoresBackup() throws Exception {
        TrackingBackupStore backups = new TrackingBackupStore();
        var bootstrap = new SleeperCurrentSeasonRosterBootstrap(
            ignored -> readyPreflight(2, 4, 0),
            ignored -> importResult(2, 4, 3),
            ignored -> readyPostAudit(2, 4, 4, 0),
            backups);

        var error = assertThrows(SleeperCurrentSeasonRosterBootstrap.BootstrapRollbackException.class,
            () -> bootstrap.bootstrap("l1", "provider-2026"));

        assertTrue(error.restored());
        assertEquals(1, backups.created.get());
        assertEquals(1, backups.restored.get());
        assertTrue(error.getCause().getMessage().contains("roster-entry count drifted"));
    }

    @Test
    void importerExceptionRestoresBackup() throws Exception {
        TrackingBackupStore backups = new TrackingBackupStore();
        var bootstrap = new SleeperCurrentSeasonRosterBootstrap(
            ignored -> readyPreflight(2, 4, 0),
            ignored -> { throw new IOException("provider interrupted import"); },
            ignored -> readyPostAudit(2, 4, 4, 0),
            backups);

        var error = assertThrows(SleeperCurrentSeasonRosterBootstrap.BootstrapRollbackException.class,
            () -> bootstrap.bootstrap("l1", "provider-2026"));

        assertTrue(error.restored());
        assertEquals(1, backups.restored.get());
        assertEquals("provider interrupted import", error.getCause().getMessage());
    }

    @Test
    void blockedPostAuditRestoresBackup() throws Exception {
        TrackingBackupStore backups = new TrackingBackupStore();
        var bootstrap = new SleeperCurrentSeasonRosterBootstrap(
            ignored -> readyPreflight(2, 4, 0),
            ignored -> importResult(2, 4, 4),
            ignored -> blockedPostAudit(2, 4),
            backups);

        var error = assertThrows(SleeperCurrentSeasonRosterBootstrap.BootstrapRollbackException.class,
            () -> bootstrap.bootstrap("l1", "provider-2026"));

        assertTrue(error.restored());
        assertEquals(1, backups.restored.get());
        assertTrue(error.getCause().getMessage().contains("Post-import lineup readiness"));
    }

    @Test
    void realImporterCreatesPreviouslyUnmappedExactSleeperPlayerDuringBootstrap() throws Exception {
        Database database = new Database(tempDir.resolve("bf600-integration.db"));
        database.initialize();
        new LeagueRepository(database).save(new League("l1", "provider-2026", "Best", 2026));
        new TeamRepository(database).save(new Team("t1", "1", "l1", "One"));
        new TeamRepository(database).save(new Team("t2", "2", "l1", "Two"));

        SleeperGateway gateway = new SleeperGateway() {
            @Override public SleeperJsonParser.SleeperLeague fetchLeague(String leagueId) {
                return new SleeperJsonParser.SleeperLeague(
                    "provider-2026", "Best", List.of("QB", "RB", "BN"), 2026, 2, 4,
                    Map.of("rec", 1.0));
            }
            @Override public List<SleeperJsonParser.SleeperUser> fetchUsers(String leagueId) {
                return List.of(
                    new SleeperJsonParser.SleeperUser("u1", "One", null),
                    new SleeperJsonParser.SleeperUser("u2", "Two", null));
            }
            @Override public List<SleeperJsonParser.SleeperRoster> fetchRosters(String leagueId) {
                return List.of(
                    new SleeperJsonParser.SleeperRoster(1, "u1", List.of("known"), List.of("known"), List.of(), List.of()),
                    new SleeperJsonParser.SleeperRoster(2, "u2", List.of("rookie"), List.of("rookie"), List.of(), List.of()));
            }
            @Override public Map<String, SleeperJsonParser.SleeperPlayer> fetchPlayers() {
                return Map.of(
                    "known", new SleeperJsonParser.SleeperPlayer("known", "Known", "QB", "CHI"),
                    "rookie", new SleeperJsonParser.SleeperPlayer("rookie", "Rookie", "RB", "KC"));
            }
        };
        SleeperLeagueImporter importer = new SleeperLeagueImporter(gateway, database);
        TrackingBackupStore backups = new TrackingBackupStore();
        var bootstrap = new SleeperCurrentSeasonRosterBootstrap(
            ignored -> readyPreflight(2, 2, 1),
            importer::importLeague,
            ignored -> readyPostAudit(2, 2, 2, 0),
            backups);

        var report = bootstrap.bootstrap("l1", "provider-2026");

        var rookie = new PlayerRepository(database).findByExternalId("rookie").orElseThrow();
        var team2 = new TeamRepository(database).findByExternalId("l1", "2").orElseThrow();
        var roster = new RosterRepository(database).findByTeamAndPlayer(team2.getId(), rookie.getId()).orElseThrow();
        assertEquals("STARTER", roster.getSlot());
        assertEquals(SleeperCurrentSeasonRosterBootstrap.BootstrapState.HYDRATED_VERIFIED, report.state());
        assertFalse(rookie.getDisplayName().isBlank());
    }

    private static SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityReport readyPreflight(
        int rosterCount,
        int rosterEntries,
        int unmapped) {
        return new SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityReport(
            SleeperCurrentSeasonHydrationEligibilityAudit.POLICY_ID,
            "l1",
            "Best",
            "provider-2026",
            2026,
            "in_season",
            1,
            rosterCount,
            rosterCount,
            rosterCount,
            rosterEntries,
            rosterEntries,
            Math.max(0, rosterEntries - unmapped),
            unmapped,
            unmapped == 0 ? List.of() : List.of("rookie"),
            List.of(),
            List.of(),
            List.of(),
            SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityState.READY_TO_HYDRATE,
            Instant.parse("2026-09-08T01:00:00Z"));
    }

    private static SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityReport blockedPreflight() {
        return new SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityReport(
            SleeperCurrentSeasonHydrationEligibilityAudit.POLICY_ID,
            "l1",
            "Best",
            "provider-2026",
            2026,
            "drafting",
            1,
            2,
            2,
            2,
            0,
            0,
            0,
            0,
            List.of(),
            List.of(1, 2),
            List.of(1, 2),
            List.of("Provider league status is drafting"),
            SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityState.BLOCKED,
            Instant.parse("2026-09-08T01:00:00Z"));
    }

    private static SleeperLeagueImporter.ImportResult importResult(int teams, int players, int rosterEntries) {
        return new SleeperLeagueImporter.ImportResult(
            "l1", teams, players, rosterEntries, teams, LeagueValueFormat.ONE_QB);
    }

    private static SleeperLiveSeasonOperationalReadinessAudit.AuditReport readyPostAudit(
        int rosterCount,
        int rosterEntries,
        int exactMapped,
        int unmapped) {
        List<SleeperLiveSeasonOperationalReadinessAudit.RosterOwnerObservation> rosters = rosterCount == 2
            ? List.of(
                new SleeperLiveSeasonOperationalReadinessAudit.RosterOwnerObservation(1, "u1", true, Math.max(1, rosterEntries / 2), 1),
                new SleeperLiveSeasonOperationalReadinessAudit.RosterOwnerObservation(2, "u2", true, Math.max(1, rosterEntries - Math.max(1, rosterEntries / 2)), 1))
            : List.of();
        return new SleeperLiveSeasonOperationalReadinessAudit.AuditReport(
            SleeperLiveSeasonOperationalReadinessAudit.POLICY_ID,
            "l1",
            "Best",
            "provider-2026",
            2026,
            2026,
            "in_season",
            1,
            rosterCount,
            9,
            20,
            rosterCount,
            rosterCount,
            rosterEntries,
            exactMapped + unmapped,
            exactMapped,
            unmapped,
            unmapped == 0 ? List.of() : List.of("unmapped"),
            List.of(),
            List.of(),
            0,
            List.of(),
            rosters,
            readyCapability(),
            readyCapability(),
            notAuditedCapability(),
            readyCapability(),
            Instant.parse("2026-09-08T01:01:00Z"));
    }

    private static SleeperLiveSeasonOperationalReadinessAudit.AuditReport blockedPostAudit(
        int rosterCount,
        int rosterEntries) {
        var base = readyPostAudit(rosterCount, rosterEntries, rosterEntries, 0);
        return new SleeperLiveSeasonOperationalReadinessAudit.AuditReport(
            base.policyId(), base.leagueId(), base.leagueName(), base.sleeperLeagueId(),
            base.targetSeason(), base.providerSeason(), base.providerStatus(), base.providerLeg(),
            base.providerDeclaredRosterCount(), base.rosterPositionCount(), base.scoringSettingCount(),
            base.persistedTeamCount(), base.providerRosterCount(), base.providerRosterEntries(),
            base.distinctCurrentPlayerIds(), base.exactMappedCurrentPlayerIds(), base.unmappedCurrentPlayerIds(),
            base.unmappedPlayerExamples(), base.providerRosterIdsMissingPersistedTeam(),
            base.persistedTeamRosterIdsMissingProvider(), base.ownerlessRosters(), base.unknownOwnerIds(),
            base.rosterOwners(), readyCapability(), blockedCapability("starter evidence incomplete"),
            notAuditedCapability(), readyCapability(), base.observedAtUtc());
    }

    private static SleeperLiveSeasonOperationalReadinessAudit.CapabilityReadiness readyCapability() {
        return new SleeperLiveSeasonOperationalReadinessAudit.CapabilityReadiness(
            SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.READY,
            List.of());
    }

    private static SleeperLiveSeasonOperationalReadinessAudit.CapabilityReadiness blockedCapability(String reason) {
        return new SleeperLiveSeasonOperationalReadinessAudit.CapabilityReadiness(
            SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.BLOCKED,
            List.of(reason));
    }

    private static SleeperLiveSeasonOperationalReadinessAudit.CapabilityReadiness notAuditedCapability() {
        return new SleeperLiveSeasonOperationalReadinessAudit.CapabilityReadiness(
            SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.NOT_YET_AUDITED,
            List.of("waiver universe not audited"));
    }

    private static final class TrackingBackupStore implements SleeperCurrentSeasonRosterBootstrap.BackupStore {
        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger restored = new AtomicInteger();

        @Override public SleeperCurrentSeasonRosterBootstrap.BackupHandle create() {
            created.incrementAndGet();
            return new SleeperCurrentSeasonRosterBootstrap.BackupHandle("backup.db");
        }

        @Override public void restore(SleeperCurrentSeasonRosterBootstrap.BackupHandle backup) {
            restored.incrementAndGet();
        }
    }
}
