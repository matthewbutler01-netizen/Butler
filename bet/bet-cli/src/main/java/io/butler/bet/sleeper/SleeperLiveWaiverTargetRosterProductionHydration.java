package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.NflversePlayerSeasonProductionImporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** BF-612 guarded targeted hydration for BF-611 target-roster prior-production gaps. */
public final class SleeperLiveWaiverTargetRosterProductionHydration {
    public static final String POLICY_ID =
        "sleeper-live-waiver-target-roster-production-hydration-v1-bf611-missing-only-exact-nflverse-backup-rollback-verify";
    public static final int PRODUCTION_SEASON = 2025;

    private final AuditSource auditSource;
    private final HydrationAction hydrationAction;
    private final BackupStore backupStore;

    public SleeperLiveWaiverTargetRosterProductionHydration(Database database, Path databasePath) {
        Objects.requireNonNull(database, "database must not be null");
        this.auditSource = (leagueId, ownerId) ->
            new SleeperLiveWaiverTargetRosterProductionComparabilityAudit(database).audit(leagueId, ownerId);
        this.hydrationAction = ids -> new NflversePlayerSeasonProductionImporter(database)
            .refreshForSleeperIds(PRODUCTION_SEASON, ids);
        this.backupStore = new FileBackupStore(
            Objects.requireNonNull(databasePath, "databasePath must not be null").toAbsolutePath(),
            Clock.systemUTC());
    }

    SleeperLiveWaiverTargetRosterProductionHydration(
        AuditSource auditSource,
        HydrationAction hydrationAction,
        BackupStore backupStore) {
        this.auditSource = Objects.requireNonNull(auditSource, "auditSource must not be null");
        this.hydrationAction = Objects.requireNonNull(hydrationAction, "hydrationAction must not be null");
        this.backupStore = Objects.requireNonNull(backupStore, "backupStore must not be null");
    }

    public HydrationReport hydrate(String leagueId, String sleeperOwnerId)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");

        var pre = auditSource.audit(normalizedLeagueId, normalizedOwnerId);
        validatePreflight(pre, normalizedLeagueId, normalizedOwnerId);

        LinkedHashSet<String> missingIds = new LinkedHashSet<>();
        for (var coverage : pre.players()) {
            String sleeperId = requireText(coverage.target().sleeperPlayerId(), "target Sleeper player id");
            requireText(coverage.target().butlerPlayerId(), "target Butler player id");
            if (coverage.state()
                == SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_MISSING) {
                if (!missingIds.add(sleeperId)) {
                    throw new IllegalStateException("BF-612 BLOCKED: duplicate missing target Sleeper id " + sleeperId);
                }
            }
        }
        if (missingIds.size() != pre.priorProductionMissing()) {
            throw new IllegalStateException(
                "BF-612 BLOCKED: BF-611 missing identity count does not reconcile; expected="
                    + pre.priorProductionMissing() + " actual=" + missingIds.size());
        }

        if (missingIds.isEmpty()) {
            return new HydrationReport(
                POLICY_ID,
                normalizedLeagueId,
                normalizedOwnerId,
                pre.marketSnapshotId(),
                pre.waiverSnapshotId(),
                pre.sleeperLeagueId(),
                pre.rosterId(),
                "none",
                0,
                pre.priorProductionPresent(),
                pre.priorProductionMissing(),
                0, 0, 0, 0, 0, 0, 0,
                "not-run",
                List.of(),
                pre.priorProductionPresent(),
                pre.priorProductionMissing(),
                HydrationState.ALREADY_COMPLETE);
        }

        BackupHandle backup = backupStore.create();
        try {
            NflversePlayerSeasonProductionImporter.ImportResult imported =
                hydrationAction.hydrate(Set.copyOf(missingIds));
            Set<String> unmatchedIds = verifyImport(missingIds, imported);

            var post = auditSource.audit(normalizedLeagueId, normalizedOwnerId);
            verifyPost(pre, post, missingIds, unmatchedIds, imported.matchedPlayers());

            return new HydrationReport(
                POLICY_ID,
                normalizedLeagueId,
                normalizedOwnerId,
                pre.marketSnapshotId(),
                pre.waiverSnapshotId(),
                pre.sleeperLeagueId(),
                pre.rosterId(),
                backup.displayPath(),
                missingIds.size(),
                pre.priorProductionPresent(),
                pre.priorProductionMissing(),
                imported.providerRows(),
                imported.providerRowsForSeason(),
                imported.crosswalkEntries(),
                imported.providerRowsMapped(),
                imported.eligiblePlayers(),
                imported.matchedPlayers(),
                imported.snapshotsWritten(),
                imported.asOfDate().toString(),
                imported.unmatched(),
                post.priorProductionPresent(),
                post.priorProductionMissing(),
                HydrationState.HYDRATED_VERIFIED);
        } catch (Exception failure) {
            try {
                backupStore.restore(backup);
            } catch (Exception restoreFailure) {
                failure.addSuppressed(restoreFailure);
                throw new HydrationRollbackException(
                    "BF-612 hydration failed and automatic database restore also failed; backup="
                        + backup.displayPath(), failure, false, backup.displayPath());
            }
            throw new HydrationRollbackException(
                "BF-612 hydration failed; database restored from " + backup.displayPath(),
                failure, true, backup.displayPath());
        }
    }

    private static void validatePreflight(
        SleeperLiveWaiverTargetRosterProductionComparabilityAudit.AuditReport pre,
        String leagueId,
        String ownerId) {
        Objects.requireNonNull(pre, "BF-611 preflight report must not be null");
        if (!SleeperLiveWaiverTargetRosterProductionComparabilityAudit.POLICY_ID.equals(pre.policyId())) {
            throw new IllegalStateException("BF-612 BLOCKED: unexpected BF-611 policy");
        }
        if (!leagueId.equals(pre.leagueId()) || !ownerId.equals(pre.sleeperOwnerId())) {
            throw new IllegalStateException("BF-612 BLOCKED: BF-611 report does not match requested league/owner");
        }
        if (pre.productionSeason() != PRODUCTION_SEASON) {
            throw new IllegalStateException("BF-612 BLOCKED: BF-611 did not audit 2025 production");
        }
        if (pre.targetPlayerCount() <= 0 || pre.players().size() != pre.targetPlayerCount()) {
            throw new IllegalStateException("BF-612 BLOCKED: BF-611 target roster is empty or unreconciled");
        }
        if (pre.priorProductionPresent() + pre.priorProductionMissing() != pre.targetPlayerCount()) {
            throw new IllegalStateException("BF-612 BLOCKED: BF-611 coverage partition does not reconcile");
        }
        LinkedHashSet<String> allIds = new LinkedHashSet<>();
        for (var coverage : pre.players()) {
            var target = coverage.target();
            if (!"EXACT_CANONICAL".equals(target.mappingState())
                || target.butlerPlayerId() == null || target.butlerPlayerId().isBlank()) {
                throw new IllegalStateException(
                    "BF-612 BLOCKED: target-roster identity is not exact canonical: " + target.sleeperPlayerId());
            }
            String sleeperId = requireText(target.sleeperPlayerId(), "target Sleeper player id");
            if (!allIds.add(sleeperId)) {
                throw new IllegalStateException("BF-612 BLOCKED: duplicate target Sleeper id " + sleeperId);
            }
        }
    }

    private static Set<String> verifyImport(
        Set<String> targetIds,
        NflversePlayerSeasonProductionImporter.ImportResult imported) {
        Objects.requireNonNull(imported, "targeted nflverse import result must not be null");
        if (!imported.persisted()) {
            throw new IllegalStateException("BF-612 targeted nflverse hydration returned preview-only result");
        }
        if (imported.season() != PRODUCTION_SEASON) {
            throw new IllegalStateException("BF-612 targeted nflverse hydration returned wrong season");
        }
        if (imported.eligiblePlayers() != targetIds.size()) {
            throw new IllegalStateException(
                "BF-612 target frame drifted during nflverse hydration; expected=" + targetIds.size()
                    + " eligible=" + imported.eligiblePlayers());
        }
        if (imported.matchedPlayers() + imported.unmatchedPlayers() != targetIds.size()) {
            throw new IllegalStateException("BF-612 targeted nflverse matched/unmatched counts do not reconcile");
        }
        if (imported.snapshotsWritten() != imported.matchedPlayers()) {
            throw new IllegalStateException("BF-612 targeted nflverse snapshot writes do not equal exact matches");
        }
        if (imported.unmatched().size() != imported.unmatchedPlayers()) {
            throw new IllegalStateException("BF-612 unmatched detail count does not reconcile");
        }

        LinkedHashSet<String> unmatchedIds = new LinkedHashSet<>();
        for (var unmatched : imported.unmatched()) {
            String sleeperId = requireText(unmatched.sleeperId(), "unmatched Sleeper player id");
            if (!targetIds.contains(sleeperId)) {
                throw new IllegalStateException("BF-612 importer returned unmatched identity outside target frame: " + sleeperId);
            }
            if (!unmatchedIds.add(sleeperId)) {
                throw new IllegalStateException("BF-612 importer returned duplicate unmatched identity: " + sleeperId);
            }
        }
        return Set.copyOf(unmatchedIds);
    }

    private static void verifyPost(
        SleeperLiveWaiverTargetRosterProductionComparabilityAudit.AuditReport pre,
        SleeperLiveWaiverTargetRosterProductionComparabilityAudit.AuditReport post,
        Set<String> targetMissingIds,
        Set<String> unmatchedIds,
        int matchedCount) {
        Objects.requireNonNull(post, "BF-611 post report must not be null");
        requireSame("market snapshot", pre.marketSnapshotId(), post.marketSnapshotId());
        requireSame("waiver snapshot", pre.waiverSnapshotId(), post.waiverSnapshotId());
        requireSame("Sleeper league", pre.sleeperLeagueId(), post.sleeperLeagueId());
        requireSame("Sleeper owner", pre.sleeperOwnerId(), post.sleeperOwnerId());
        requireSame("Butler team", pre.butlerTeamId(), post.butlerTeamId());
        if (pre.rosterId() != post.rosterId() || pre.targetPlayerCount() != post.targetPlayerCount()) {
            throw new IllegalStateException("BF-612 target roster lineage changed during hydration");
        }
        if (!targetSignature(pre).equals(targetSignature(post))) {
            throw new IllegalStateException("BF-612 target player/slot frame changed during hydration");
        }

        int expectedPresent = pre.priorProductionPresent() + matchedCount;
        int expectedMissing = unmatchedIds.size();
        if (post.priorProductionPresent() != expectedPresent
            || post.priorProductionMissing() != expectedMissing) {
            throw new IllegalStateException(
                "BF-612 post BF-611 coverage does not reconcile with exact importer result; expected present/missing="
                    + expectedPresent + "/" + expectedMissing + " actual="
                    + post.priorProductionPresent() + "/" + post.priorProductionMissing());
        }

        Map<String, SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState> postStates =
            new LinkedHashMap<>();
        for (var coverage : post.players()) {
            postStates.put(coverage.target().sleeperPlayerId(), coverage.state());
        }
        for (String sleeperId : targetMissingIds) {
            var expected = unmatchedIds.contains(sleeperId)
                ? SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_MISSING
                : SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_PRESENT;
            if (postStates.get(sleeperId) != expected) {
                throw new IllegalStateException(
                    "BF-612 post BF-611 state disagrees for target " + sleeperId
                        + "; expected=" + expected + " actual=" + postStates.get(sleeperId));
            }
        }
        for (var coverage : pre.players()) {
            if (coverage.state()
                == SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_PRESENT
                && postStates.get(coverage.target().sleeperPlayerId())
                    != SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_PRESENT) {
                throw new IllegalStateException("BF-612 post BF-611 lost existing production coverage for "
                    + coverage.target().sleeperPlayerId());
            }
        }
    }

    private static List<String> targetSignature(
        SleeperLiveWaiverTargetRosterProductionComparabilityAudit.AuditReport report) {
        List<String> signature = new ArrayList<>();
        for (var coverage : report.players()) {
            var target = coverage.target();
            signature.add(target.sleeperPlayerId() + "|" + target.rosterSlot() + "|"
                + target.starterOrdinal() + "|" + target.lineupSlot() + "|" + target.butlerPlayerId());
        }
        return List.copyOf(signature);
    }

    private static void requireSame(String label, String before, String after) {
        if (!Objects.equals(before, after)) {
            throw new IllegalStateException("BF-612 " + label + " changed during hydration; pre="
                + before + " post=" + after);
        }
    }

    @FunctionalInterface
    interface AuditSource {
        SleeperLiveWaiverTargetRosterProductionComparabilityAudit.AuditReport audit(String leagueId, String ownerId)
            throws SQLException, IOException, InterruptedException;
    }

    @FunctionalInterface
    interface HydrationAction {
        NflversePlayerSeasonProductionImporter.ImportResult hydrate(Set<String> sleeperIds)
            throws IOException, InterruptedException, SQLException;
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
            Path backupPath = parent.resolve(stem + "-before-bf612-" + epochMillis + ".db");
            Files.copy(databasePath, backupPath);
            return new BackupHandle(backupPath.toString());
        }

        @Override
        public void restore(BackupHandle backup) throws IOException {
            Path backupPath = Path.of(backup.displayPath()).toAbsolutePath();
            if (!Files.isRegularFile(backupPath)) {
                throw new IOException("BF-612 backup does not exist: " + backupPath);
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

    public enum HydrationState {
        HYDRATED_VERIFIED,
        ALREADY_COMPLETE
    }

    public record HydrationReport(
        String policyId,
        String leagueId,
        String sleeperOwnerId,
        String marketSnapshotId,
        String waiverSnapshotId,
        String sleeperLeagueId,
        int rosterId,
        String backupPath,
        int missingTargetsHydrated,
        int prePresent,
        int preMissing,
        int providerRows,
        int providerRowsForSeason,
        int crosswalkEntries,
        int targetProviderRowsMapped,
        int targetEligiblePlayers,
        int targetMatchedPlayers,
        int snapshotsWritten,
        String productionAsOfDate,
        List<NflversePlayerSeasonProductionImporter.UnmatchedPlayer> unmatched,
        int postPresent,
        int postMissing,
        HydrationState state) {
        public HydrationReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            leagueId = requireText(leagueId, "leagueId");
            sleeperOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");
            marketSnapshotId = requireText(marketSnapshotId, "marketSnapshotId");
            waiverSnapshotId = requireText(waiverSnapshotId, "waiverSnapshotId");
            sleeperLeagueId = requireText(sleeperLeagueId, "sleeperLeagueId");
            backupPath = requireText(backupPath, "backupPath");
            productionAsOfDate = requireText(productionAsOfDate, "productionAsOfDate");
            unmatched = List.copyOf(Objects.requireNonNull(unmatched, "unmatched must not be null"));
            Objects.requireNonNull(state, "state must not be null");
        }
    }

    public static final class HydrationRollbackException extends IllegalStateException {
        private final boolean restored;
        private final String backupPath;

        HydrationRollbackException(String message, Throwable cause, boolean restored, String backupPath) {
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
