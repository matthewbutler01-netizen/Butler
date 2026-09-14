package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;

/**
 * BF-762 one-execution coalescing of the exact BF-610 target-roster context used by BF-611
 * and the Waiver Board roster-context output.
 *
 * <p>This is not a cache. The exact BF-610 report is produced once during the governed BF-737
 * comparison invocation, consumed immediately by BF-611, and returned to the caller for the
 * same dashboard execution.</p>
 */
public final class SleeperLiveWaiverCoalescedComparisonEvidence {
    private final SleeperLiveWaiverComparisonEvidenceReuse.CandidateEvidenceSource candidateEvidenceSource;
    private final RosterContextSource rosterContextSource;
    private final RosterEvidenceFromContextSource rosterEvidenceSource;
    private final SleeperLiveWaiverCandidateRosterComparisonMethodology.ScoringSettingsSource scoringSettingsSource;
    private final SleeperLiveWaiverComparisonExecutionBundle.BatchProductionSource batchProductionSource;

    public SleeperLiveWaiverCoalescedComparisonEvidence(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        PlayerSeasonProductionRepository productionRepository = new PlayerSeasonProductionRepository(database);
        LeagueScoringSettingsRepository scoringRepository = new LeagueScoringSettingsRepository(database);
        this.candidateEvidenceSource = leagueId -> SleeperLiveWaiverComparisonEvidenceReuse.candidateEvidence(
            new SleeperLiveWaiverPregameEvidenceReadinessAudit(database).audit(leagueId));
        this.rosterContextSource = (leagueId, ownerId) ->
            new SleeperLiveWaiverTargetRosterContextAudit(database).audit(leagueId, ownerId);
        this.rosterEvidenceSource = (leagueId, ownerId, context) -> {
            var comparability = new SleeperLiveWaiverTargetRosterProductionComparabilityAudit(
                database,
                (requestedLeagueId, requestedOwnerId) -> requireMatchingContext(
                    context, requestedLeagueId, requestedOwnerId))
                .audit(leagueId, ownerId);
            return SleeperLiveWaiverComparisonEvidenceReuse.rosterEvidence(comparability);
        };
        this.scoringSettingsSource = scoringRepository::findByLeagueId;
        this.batchProductionSource = productionRepository::findByPlayerIdsAndSeason;
    }

    SleeperLiveWaiverCoalescedComparisonEvidence(
        SleeperLiveWaiverComparisonEvidenceReuse.CandidateEvidenceSource candidateEvidenceSource,
        RosterContextSource rosterContextSource,
        RosterEvidenceFromContextSource rosterEvidenceSource,
        SleeperLiveWaiverCandidateRosterComparisonMethodology.ScoringSettingsSource scoringSettingsSource,
        SleeperLiveWaiverComparisonExecutionBundle.BatchProductionSource batchProductionSource) {
        this.candidateEvidenceSource = Objects.requireNonNull(
            candidateEvidenceSource, "candidateEvidenceSource must not be null");
        this.rosterContextSource = Objects.requireNonNull(
            rosterContextSource, "rosterContextSource must not be null");
        this.rosterEvidenceSource = Objects.requireNonNull(
            rosterEvidenceSource, "rosterEvidenceSource must not be null");
        this.scoringSettingsSource = Objects.requireNonNull(
            scoringSettingsSource, "scoringSettingsSource must not be null");
        this.batchProductionSource = Objects.requireNonNull(
            batchProductionSource, "batchProductionSource must not be null");
    }

    public CoalescedReport run(String leagueId, String sleeperOwnerId)
        throws SQLException, IOException, InterruptedException {
        var measured = runMeasured(leagueId, sleeperOwnerId);
        return new CoalescedReport(measured.bundle(), measured.rosterContext());
    }

    public CoalescedMeasuredReport runMeasured(String leagueId, String sleeperOwnerId)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport[] contextHolder = {null};
        long[] rosterContextMs = {0L};
        int[] rosterContextCalls = {0};

        var reuse = new SleeperLiveWaiverComparisonEvidenceReuse(
            candidateEvidenceSource,
            (requestedLeagueId, requestedOwnerId) -> {
                if (rosterContextCalls[0] != 0 || contextHolder[0] != null) {
                    throw new IllegalStateException(
                        "BF-762 BLOCKED: target-roster context executed more than once in one comparison");
                }
                long contextStarted = System.nanoTime();
                SleeperLiveWaiverTargetRosterContextAudit.AuditReport context;
                try {
                    rosterContextCalls[0]++;
                    context = Objects.requireNonNull(
                        rosterContextSource.audit(requestedLeagueId, requestedOwnerId),
                        "BF-762 target-roster context must not be null");
                } finally {
                    rosterContextMs[0] = elapsedMs(contextStarted);
                }
                context = requireMatchingContext(context, requestedLeagueId, requestedOwnerId);
                contextHolder[0] = context;
                return Objects.requireNonNull(
                    rosterEvidenceSource.load(requestedLeagueId, requestedOwnerId, context),
                    "BF-762 roster evidence must not be null");
            },
            scoringSettingsSource,
            batchProductionSource);

        var measured = reuse.runMeasured(normalizedLeagueId, normalizedOwnerId);
        if (rosterContextCalls[0] != 1 || contextHolder[0] == null) {
            throw new IllegalStateException(
                "BF-762 BLOCKED: comparison did not produce exactly one target-roster context");
        }
        var context = requireMatchingContext(contextHolder[0], normalizedLeagueId, normalizedOwnerId);
        return new CoalescedMeasuredReport(
            measured.bundle(), context, measured.timing(), rosterContextMs[0]);
    }

    private static SleeperLiveWaiverTargetRosterContextAudit.AuditReport requireMatchingContext(
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport context,
        String leagueId,
        String ownerId) {
        Objects.requireNonNull(context, "BF-762 context must not be null");
        if (!SleeperLiveWaiverTargetRosterContextAudit.POLICY_ID.equals(context.policyId())) {
            throw new IllegalStateException("BF-762 BLOCKED: unexpected BF-610 policy");
        }
        if (!Objects.equals(context.leagueId(), leagueId)) {
            throw new IllegalStateException("BF-762 BLOCKED: BF-610 league does not match comparison league");
        }
        if (!Objects.equals(context.sleeperOwnerId(), ownerId)) {
            throw new IllegalStateException("BF-762 BLOCKED: BF-610 owner does not match comparison owner");
        }
        return context;
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    @FunctionalInterface
    interface RosterContextSource {
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport audit(String leagueId, String ownerId)
            throws SQLException, IOException, InterruptedException;
    }

    @FunctionalInterface
    interface RosterEvidenceFromContextSource {
        SleeperLiveWaiverComparisonEvidenceReuse.RosterEvidence load(
            String leagueId,
            String ownerId,
            SleeperLiveWaiverTargetRosterContextAudit.AuditReport context)
            throws SQLException, IOException, InterruptedException;
    }

    public record CoalescedReport(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport rosterContext) {
        public CoalescedReport {
            Objects.requireNonNull(bundle, "bundle must not be null");
            Objects.requireNonNull(rosterContext, "rosterContext must not be null");
        }
    }

    public record CoalescedMeasuredReport(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport rosterContext,
        SleeperLiveWaiverComparisonEvidenceReuse.StageTiming timing,
        long rosterContextMs) {
        public CoalescedMeasuredReport {
            Objects.requireNonNull(bundle, "bundle must not be null");
            Objects.requireNonNull(rosterContext, "rosterContext must not be null");
            Objects.requireNonNull(timing, "timing must not be null");
            if (rosterContextMs < 0) {
                throw new IllegalArgumentException("BF-762 rosterContextMs must be non-negative");
            }
        }
    }
}
