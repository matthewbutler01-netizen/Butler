package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.domain.Player;
import io.butler.bet.intelligence.NflversePlayerSeasonProductionImporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** BF-605 guarded exact-identity bootstrap and targeted prior-season production hydration. */
public final class SleeperLiveWaiverProductionHydration {
    public static final String POLICY_ID =
        "sleeper-live-waiver-production-hydration-v1-latest-bf603-exact-bootstrap-targeted-nflverse-backup-rollback-bf604-verify";
    public static final int PRODUCTION_SEASON = 2025;

    private final Database database;
    private final PlayerRepository players;
    private final CoverageSource coverageSource;
    private final HydrationAction hydrationAction;
    private final BackupStore backupStore;

    public SleeperLiveWaiverProductionHydration(Database database, Path databasePath) {
        this(
            database,
            new SleeperLiveWaiverProductionCoverageAudit(database)::audit,
            ids -> new NflversePlayerSeasonProductionImporter(database)
                .refreshForSleeperIds(PRODUCTION_SEASON, ids),
            new FileBackupStore(
                Objects.requireNonNull(databasePath, "databasePath must not be null").toAbsolutePath(),
                Clock.systemUTC()));
    }

    SleeperLiveWaiverProductionHydration(
        Database database,
        CoverageSource coverageSource,
        HydrationAction hydrationAction,
        BackupStore backupStore) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.players = new PlayerRepository(database);
        this.coverageSource = Objects.requireNonNull(coverageSource, "coverageSource must not be null");
        this.hydrationAction = Objects.requireNonNull(hydrationAction, "hydrationAction must not be null");
        this.backupStore = Objects.requireNonNull(backupStore, "backupStore must not be null");
    }

    public HydrationReport hydrate(String leagueId) throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        SleeperLiveWaiverProductionCoverageAudit.AuditReport pre = coverageSource.audit(normalizedLeagueId);
        validatePreflight(pre, normalizedLeagueId);

        LinkedHashSet<String> targetIds = new LinkedHashSet<>();
        for (var candidate : pre.candidates()) {
            String sleeperId = requireText(candidate.market().sleeperPlayerId(), "market Sleeper player id");
            if (!targetIds.add(sleeperId)) {
                throw new IllegalStateException("BF-605 BLOCKED: duplicate market-active Sleeper id " + sleeperId);
            }
            if (candidate.state() == SleeperLiveWaiverProductionCoverageAudit.CoverageState.UNMAPPED_CANONICAL) {
                requireText(candidate.market().displayName(), "market display name");
                requireText(candidate.market().position(), "market position");
            }
        }
        if (targetIds.size() != pre.marketActiveCandidates()) {
            throw new IllegalStateException(
                "BF-605 BLOCKED: exact market-active identity frame does not reconcile; expected="
                    + pre.marketActiveCandidates() + " actual=" + targetIds.size());
        }

        int canonicalBefore = players.findAll().size();
        BackupHandle backup = backupStore.create();
        try {
            int canonicalCreated = bootstrapMissingCanonical(pre);
            verifyAllTargetsCanonical(targetIds);

            NflversePlayerSeasonProductionImporter.ImportResult imported =
                hydrationAction.hydrate(Set.copyOf(targetIds));
            verifyImport(targetIds.size(), imported);

            SleeperLiveWaiverProductionCoverageAudit.AuditReport post =
                coverageSource.audit(normalizedLeagueId);
            verifyPost(pre, post, targetIds.size());

            int canonicalAfter = players.findAll().size();
            if (canonicalAfter != canonicalBefore + canonicalCreated) {
                throw new IllegalStateException(
                    "BF-605 post-write canonical player count drifted; before=" + canonicalBefore
                        + " created=" + canonicalCreated + " after=" + canonicalAfter);
            }

            return new HydrationReport(
                POLICY_ID,
                normalizedLeagueId,
                pre.marketSnapshotId(),
                backup.displayPath(),
                targetIds.size(),
                canonicalBefore,
                canonicalCreated,
                canonicalAfter,
                pre.unmappedCanonical(),
                pre.mappedNoProduction(),
                pre.mappedWithProduction(),
                imported.providerRows(),
                imported.providerRowsForSeason(),
                imported.crosswalkEntries(),
                imported.providerRowsMapped(),
                imported.eligiblePlayers(),
                imported.matchedPlayers(),
                imported.unmatchedPlayers(),
                imported.snapshotsWritten(),
                imported.asOfDate().toString(),
                imported.unmatched(),
                post.unmappedCanonical(),
                post.mappedNoProduction(),
                post.mappedWithProduction(),
                HydrationState.HYDRATED_VERIFIED);
        } catch (Exception failure) {
            try {
                backupStore.restore(backup);
            } catch (Exception restoreFailure) {
                failure.addSuppressed(restoreFailure);
                throw new HydrationRollbackException(
                    "BF-605 hydration failed and automatic database restore also failed; backup="
                        + backup.displayPath(),
                    failure,
                    false,
                    backup.displayPath());
            }
            throw new HydrationRollbackException(
                "BF-605 hydration failed; database restored from " + backup.displayPath(),
                failure,
                true,
                backup.displayPath());
        }
    }

    private int bootstrapMissingCanonical(SleeperLiveWaiverProductionCoverageAudit.AuditReport pre)
        throws SQLException {
        int created = 0;
        for (var candidate : pre.candidates()) {
            if (candidate.state() != SleeperLiveWaiverProductionCoverageAudit.CoverageState.UNMAPPED_CANONICAL) {
                continue;
            }
            var market = candidate.market();
            String sleeperId = requireText(market.sleeperPlayerId(), "market Sleeper player id");
            if (players.findByExternalId(sleeperId).isPresent()) {
                throw new IllegalStateException(
                    "BF-605 canonical preflight drift: exact Sleeper id became mapped after BF-604 frame: " + sleeperId);
            }
            players.save(new Player(
                UUID.randomUUID().toString(),
                sleeperId,
                requireText(market.displayName(), "market display name"),
                requireText(market.position(), "market position"),
                normalizeOptional(market.nflTeam())));
            created++;
        }
        return created;
    }

    private void verifyAllTargetsCanonical(Set<String> targetIds) throws SQLException {
        for (String sleeperId : targetIds) {
            if (players.findByExternalId(sleeperId).isEmpty()) {
                throw new IllegalStateException(
                    "BF-605 exact canonical bootstrap incomplete for Sleeper id " + sleeperId);
            }
        }
    }

    private static void validatePreflight(
        SleeperLiveWaiverProductionCoverageAudit.AuditReport pre,
        String leagueId) {
        Objects.requireNonNull(pre, "BF-604 preflight report must not be null");
        if (!leagueId.equals(pre.leagueId())) {
            throw new IllegalStateException("BF-605 BLOCKED: BF-604 audited a different Butler league");
        }
        if (pre.productionSeason() != PRODUCTION_SEASON) {
            throw new IllegalStateException(
                "BF-605 BLOCKED: BF-604 production season is " + pre.productionSeason()
                    + " instead of " + PRODUCTION_SEASON);
        }
        if (pre.marketActiveCandidates() <= 0) {
            throw new IllegalStateException("BF-605 BLOCKED: BF-603 market-active frame is empty");
        }
        if (pre.unmappedCanonical() + pre.mappedNoProduction() + pre.mappedWithProduction()
            != pre.marketActiveCandidates()) {
            throw new IllegalStateException("BF-605 BLOCKED: BF-604 coverage partition does not reconcile");
        }
    }

    private static void verifyImport(
        int targetCount,
        NflversePlayerSeasonProductionImporter.ImportResult imported) {
        Objects.requireNonNull(imported, "targeted nflverse import result must not be null");
        if (!imported.persisted()) {
            throw new IllegalStateException("BF-605 targeted nflverse hydration returned preview-only result");
        }
        if (imported.season() != PRODUCTION_SEASON) {
            throw new IllegalStateException("BF-605 targeted nflverse hydration returned wrong season");
        }
        if (imported.eligiblePlayers() != targetCount) {
            throw new IllegalStateException(
                "BF-605 target canonical frame drifted during nflverse hydration; expected="
                    + targetCount + " eligible=" + imported.eligiblePlayers());
        }
        if (imported.matchedPlayers() + imported.unmatchedPlayers() != targetCount) {
            throw new IllegalStateException("BF-605 targeted nflverse matched/unmatched counts do not reconcile");
        }
        if (imported.snapshotsWritten() != imported.matchedPlayers()) {
            throw new IllegalStateException("BF-605 targeted nflverse snapshot writes do not equal exact matches");
        }
    }

    private static void verifyPost(
        SleeperLiveWaiverProductionCoverageAudit.AuditReport pre,
        SleeperLiveWaiverProductionCoverageAudit.AuditReport post,
        int targetCount) {
        Objects.requireNonNull(post, "BF-604 post report must not be null");
        if (!pre.marketSnapshotId().equals(post.marketSnapshotId())) {
            throw new IllegalStateException(
                "BF-605 market-attention frame changed during hydration; pre=" + pre.marketSnapshotId()
                    + " post=" + post.marketSnapshotId());
        }
        if (post.marketActiveCandidates() != targetCount) {
            throw new IllegalStateException("BF-605 post BF-604 market-active count drifted");
        }
        if (post.unmappedCanonical() != 0) {
            throw new IllegalStateException(
                "BF-605 post BF-604 still has unmapped canonical identities: " + post.unmappedCanonical());
        }
        if (post.mappedNoProduction() + post.mappedWithProduction() != targetCount) {
            throw new IllegalStateException("BF-605 post BF-604 coverage partition does not reconcile");
        }
        if (post.mappedWithProduction() < pre.mappedWithProduction()) {
            throw new IllegalStateException("BF-605 post BF-604 lost existing production coverage");
        }
    }

    @FunctionalInterface
    interface CoverageSource {
        SleeperLiveWaiverProductionCoverageAudit.AuditReport audit(String leagueId) throws SQLException;
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
            Path backupPath = parent.resolve(stem + "-before-bf605-" + epochMillis + ".db");
            Files.copy(databasePath, backupPath);
            return new BackupHandle(backupPath.toString());
        }

        @Override
        public void restore(BackupHandle backup) throws IOException {
            Path backupPath = Path.of(backup.displayPath()).toAbsolutePath();
            if (!Files.isRegularFile(backupPath)) {
                throw new IOException("BF-605 backup does not exist: " + backupPath);
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
        HYDRATED_VERIFIED
    }

    public record HydrationReport(
        String policyId,
        String leagueId,
        String marketSnapshotId,
        String backupPath,
        int targetCandidates,
        int canonicalPlayersBefore,
        int canonicalPlayersCreated,
        int canonicalPlayersAfter,
        int preUnmapped,
        int preMappedNoProduction,
        int preMappedWithProduction,
        int providerRows,
        int providerRowsForSeason,
        int crosswalkEntries,
        int targetProviderRowsMapped,
        int targetEligiblePlayers,
        int targetMatchedPlayers,
        int targetUnmatchedPlayers,
        int snapshotsWritten,
        String productionAsOfDate,
        List<NflversePlayerSeasonProductionImporter.UnmatchedPlayer> unmatched,
        int postUnmapped,
        int postMappedNoProduction,
        int postMappedWithProduction,
        HydrationState state) {
        public HydrationReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            leagueId = requireText(leagueId, "leagueId");
            marketSnapshotId = requireText(marketSnapshotId, "marketSnapshotId");
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

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
