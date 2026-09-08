package io.butler.bet.sleeper;

import io.butler.bet.data.Database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** BF-600 guarded write path for bootstrapping a populated linked 2026 Sleeper league into Butler. */
public final class SleeperCurrentSeasonRosterBootstrap {
    public static final String POLICY_ID =
        "sleeper-current-season-roster-bootstrap-v1-bf599-ready-backup-import-bf598-verify-rollback";
    public static final int TARGET_SEASON = 2026;

    private final EligibilitySource eligibilitySource;
    private final ImportAction importAction;
    private final PostAuditSource postAuditSource;
    private final BackupStore backupStore;

    public SleeperCurrentSeasonRosterBootstrap(Database database, Path databasePath) {
        Objects.requireNonNull(database, "database must not be null");
        Path normalizedPath = Objects.requireNonNull(databasePath, "databasePath must not be null").toAbsolutePath();
        SleeperCurrentSeasonHydrationEligibilityAudit eligibility =
            new SleeperCurrentSeasonHydrationEligibilityAudit(database);
        SleeperLeagueImporter importer = new SleeperLeagueImporter(database);
        SleeperLiveSeasonOperationalReadinessAudit postAudit =
            new SleeperLiveSeasonOperationalReadinessAudit(database);
        this.eligibilitySource = eligibility::audit;
        this.importAction = importer::importLeague;
        this.postAuditSource = postAudit::audit;
        this.backupStore = new FileBackupStore(normalizedPath, Clock.systemUTC());
    }

    SleeperCurrentSeasonRosterBootstrap(
        EligibilitySource eligibilitySource,
        ImportAction importAction,
        PostAuditSource postAuditSource,
        BackupStore backupStore) {
        this.eligibilitySource = Objects.requireNonNull(eligibilitySource, "eligibilitySource must not be null");
        this.importAction = Objects.requireNonNull(importAction, "importAction must not be null");
        this.postAuditSource = Objects.requireNonNull(postAuditSource, "postAuditSource must not be null");
        this.backupStore = Objects.requireNonNull(backupStore, "backupStore must not be null");
    }

    public BootstrapReport bootstrap(String leagueId, String expectedSleeperLeagueId)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String expectedProviderId = requireText(expectedSleeperLeagueId, "expectedSleeperLeagueId");

        SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityReport preflight =
            eligibilitySource.audit(normalizedLeagueId);
        if (preflight.state()
            != SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityState.READY_TO_HYDRATE) {
            throw new IllegalStateException(
                "BF-599 hydration eligibility is " + preflight.state() + ": " + preflight.blockers());
        }
        if (!expectedProviderId.equals(preflight.sleeperLeagueId())) {
            throw new IllegalStateException(
                "Expected Sleeper league " + expectedProviderId
                    + " does not match BF-599 linked league " + preflight.sleeperLeagueId());
        }
        if (preflight.providerSeason() != TARGET_SEASON || !"in_season".equals(preflight.providerStatus())) {
            throw new IllegalStateException(
                "BF-599 preflight is not an in-season 2026 surface despite READY_TO_HYDRATE");
        }

        BackupHandle backup = backupStore.create();
        try {
            SleeperLeagueImporter.ImportResult imported = importAction.importLeague(expectedProviderId);
            verifyImportFrame(preflight, imported);

            SleeperLiveSeasonOperationalReadinessAudit.AuditReport post =
                postAuditSource.audit(normalizedLeagueId);
            verifyPostAudit(preflight, expectedProviderId, imported, post);

            return new BootstrapReport(
                POLICY_ID,
                normalizedLeagueId,
                expectedProviderId,
                backup.displayPath(),
                preflight.providerRosterCount(),
                preflight.providerRosterEntries(),
                preflight.unmappedCurrentPlayerIds(),
                imported.teamsImported(),
                imported.playersImported(),
                imported.rosterEntriesImported(),
                imported.performanceSnapshotsImported(),
                post.exactMappedCurrentPlayerIds(),
                post.unmappedCurrentPlayerIds(),
                BootstrapState.HYDRATED_VERIFIED);
        } catch (Exception failure) {
            try {
                backupStore.restore(backup);
            } catch (Exception restoreFailure) {
                failure.addSuppressed(restoreFailure);
                throw new BootstrapRollbackException(
                    "BF-600 bootstrap failed and automatic database restore also failed; backup="
                        + backup.displayPath(),
                    failure,
                    false,
                    backup.displayPath());
            }
            throw new BootstrapRollbackException(
                "BF-600 bootstrap failed; database restored from " + backup.displayPath(),
                failure,
                true,
                backup.displayPath());
        }
    }

    private static void verifyImportFrame(
        SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityReport preflight,
        SleeperLeagueImporter.ImportResult imported) {
        if (!preflight.leagueId().equals(imported.leagueId())) {
            throw new IllegalStateException(
                "Importer returned Butler league " + imported.leagueId()
                    + " but preflight league is " + preflight.leagueId());
        }
        if (imported.teamsImported() != preflight.providerRosterCount()) {
            throw new IllegalStateException(
                "Importer team count drifted from BF-599 frame: expected "
                    + preflight.providerRosterCount() + " imported " + imported.teamsImported());
        }
        if (imported.rosterEntriesImported() != preflight.providerRosterEntries()) {
            throw new IllegalStateException(
                "Importer roster-entry count drifted from BF-599 frame: expected "
                    + preflight.providerRosterEntries() + " imported " + imported.rosterEntriesImported());
        }
    }

    private static void verifyPostAudit(
        SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityReport preflight,
        String expectedProviderId,
        SleeperLeagueImporter.ImportResult imported,
        SleeperLiveSeasonOperationalReadinessAudit.AuditReport post) {
        if (!preflight.leagueId().equals(post.leagueId())) {
            throw new IllegalStateException("Post-import BF-598 audited a different Butler league");
        }
        if (!expectedProviderId.equals(post.sleeperLeagueId())) {
            throw new IllegalStateException(
                "Post-import linked Sleeper league is " + post.sleeperLeagueId()
                    + " instead of expected " + expectedProviderId);
        }
        if (post.providerSeason() != TARGET_SEASON) {
            throw new IllegalStateException(
                "Post-import provider season is " + post.providerSeason() + " instead of " + TARGET_SEASON);
        }
        if (post.providerRosterCount() != imported.teamsImported()) {
            throw new IllegalStateException(
                "Post-import provider roster count drifted from imported team count");
        }
        if (post.providerRosterEntries() != imported.rosterEntriesImported()) {
            throw new IllegalStateException(
                "Post-import provider roster-entry count drifted from imported roster-entry count");
        }
        requireReady("current-roster", post.currentRosterContext());
        requireReady("lineup", post.lineupContextPrerequisites());
        requireReady("trade", post.tradeContextPrerequisites());
    }

    private static void requireReady(
        String capability,
        SleeperLiveSeasonOperationalReadinessAudit.CapabilityReadiness readiness) {
        if (readiness.state() != SleeperLiveSeasonOperationalReadinessAudit.CapabilityState.READY) {
            throw new IllegalStateException(
                "Post-import " + capability + " readiness is " + readiness.state()
                    + ": " + readiness.blockers());
        }
    }

    @FunctionalInterface
    interface EligibilitySource {
        SleeperCurrentSeasonHydrationEligibilityAudit.EligibilityReport audit(String leagueId)
            throws SQLException, IOException, InterruptedException;
    }

    @FunctionalInterface
    interface ImportAction {
        SleeperLeagueImporter.ImportResult importLeague(String sleeperLeagueId)
            throws SQLException, IOException, InterruptedException;
    }

    @FunctionalInterface
    interface PostAuditSource {
        SleeperLiveSeasonOperationalReadinessAudit.AuditReport audit(String leagueId)
            throws SQLException, IOException, InterruptedException;
    }

    interface BackupStore {
        BackupHandle create() throws IOException;
        void restore(BackupHandle backup) throws IOException;
    }

    record BackupHandle(String displayPath) {
        BackupHandle {
            displayPath = requireText(displayPath, "displayPath");
        }
    }

    static final class FileBackupStore implements BackupStore {
        private final Path databasePath;
        private final Clock clock;

        FileBackupStore(Path databasePath, Clock clock) {
            this.databasePath = Objects.requireNonNull(databasePath, "databasePath must not be null").toAbsolutePath();
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
        }

        @Override
        public BackupHandle create() throws IOException {
            if (!Files.isRegularFile(databasePath)) {
                throw new IOException("Butler database does not exist: " + databasePath);
            }
            long epochMillis = clock.instant().toEpochMilli();
            Path parent = databasePath.getParent();
            String fileName = databasePath.getFileName().toString();
            String stem = fileName.endsWith(".db") ? fileName.substring(0, fileName.length() - 3) : fileName;
            Path backupPath = parent.resolve(stem + "-before-bf600-" + epochMillis + ".db");
            Files.copy(databasePath, backupPath);
            return new BackupHandle(backupPath.toString());
        }

        @Override
        public void restore(BackupHandle backup) throws IOException {
            Path backupPath = Path.of(backup.displayPath()).toAbsolutePath();
            if (!Files.isRegularFile(backupPath)) {
                throw new IOException("BF-600 backup does not exist: " + backupPath);
            }
            deleteSqliteSidecars(databasePath);
            Files.copy(backupPath, databasePath, StandardCopyOption.REPLACE_EXISTING);
        }

        private static void deleteSqliteSidecars(Path databasePath) throws IOException {
            String base = databasePath.toString();
            Files.deleteIfExists(Path.of(base + "-journal"));
            Files.deleteIfExists(Path.of(base + "-wal"));
            Files.deleteIfExists(Path.of(base + "-shm"));
        }
    }

    public enum BootstrapState {
        HYDRATED_VERIFIED
    }

    public record BootstrapReport(
        String policyId,
        String leagueId,
        String sleeperLeagueId,
        String backupPath,
        int preflightRosterCount,
        int preflightRosterEntries,
        int preflightUnmappedPlayerIds,
        int teamsImported,
        int playersImported,
        int rosterEntriesImported,
        int performanceSnapshotsImported,
        int postExactMappedCurrentPlayers,
        int postUnmappedCurrentPlayers,
        BootstrapState state) {
        public BootstrapReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            leagueId = requireText(leagueId, "leagueId");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            backupPath = requireText(backupPath, "backupPath");
            Objects.requireNonNull(state, "state must not be null");
            List.of(
                preflightRosterCount,
                preflightRosterEntries,
                preflightUnmappedPlayerIds,
                teamsImported,
                playersImported,
                rosterEntriesImported,
                performanceSnapshotsImported,
                postExactMappedCurrentPlayers,
                postUnmappedCurrentPlayers).forEach(value -> {
                    if (value < 0) throw new IllegalArgumentException("bootstrap counts must not be negative");
                });
        }
    }

    public static final class BootstrapRollbackException extends IllegalStateException {
        private final boolean restored;
        private final String backupPath;

        BootstrapRollbackException(String message, Throwable cause, boolean restored, String backupPath) {
            super(message, cause);
            this.restored = restored;
            this.backupPath = requireText(backupPath, "backupPath");
        }

        public boolean restored() { return restored; }
        public String backupPath() { return backupPath; }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
