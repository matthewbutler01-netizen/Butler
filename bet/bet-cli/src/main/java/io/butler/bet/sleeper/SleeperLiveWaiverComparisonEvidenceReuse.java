package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * BF-737 one-execution reuse of the exact BF-609/BF-611 reports already loaded for BF-613/BF-614.
 *
 * <p>This is not a cache. Evidence is loaded once per invocation, validated through the existing
 * BF-613 and BF-614 gates, then carried forward into the unchanged BF-615 through BF-617 bundle.</p>
 */
public final class SleeperLiveWaiverComparisonEvidenceReuse {
    private final CandidateEvidenceSource candidateEvidenceSource;
    private final RosterEvidenceSource rosterEvidenceSource;
    private final SleeperLiveWaiverCandidateRosterComparisonMethodology.ScoringSettingsSource scoringSettingsSource;
    private final SleeperLiveWaiverComparisonExecutionBundle.BatchProductionSource batchProductionSource;

    public SleeperLiveWaiverComparisonEvidenceReuse(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        PlayerSeasonProductionRepository productionRepository = new PlayerSeasonProductionRepository(database);
        LeagueScoringSettingsRepository scoringRepository = new LeagueScoringSettingsRepository(database);
        this.candidateEvidenceSource = leagueId -> candidateEvidence(
            new SleeperLiveWaiverPregameEvidenceReadinessAudit(database).audit(leagueId));
        this.rosterEvidenceSource = (leagueId, ownerId) -> rosterEvidence(
            new SleeperLiveWaiverTargetRosterProductionComparabilityAudit(database).audit(leagueId, ownerId));
        this.scoringSettingsSource = scoringRepository::findByLeagueId;
        this.batchProductionSource = productionRepository::findByPlayerIdsAndSeason;
    }

    SleeperLiveWaiverComparisonEvidenceReuse(
        CandidateEvidenceSource candidateEvidenceSource,
        RosterEvidenceSource rosterEvidenceSource,
        SleeperLiveWaiverCandidateRosterComparisonMethodology.ScoringSettingsSource scoringSettingsSource,
        SleeperLiveWaiverComparisonExecutionBundle.BatchProductionSource batchProductionSource) {
        this.candidateEvidenceSource = Objects.requireNonNull(candidateEvidenceSource, "candidateEvidenceSource must not be null");
        this.rosterEvidenceSource = Objects.requireNonNull(rosterEvidenceSource, "rosterEvidenceSource must not be null");
        this.scoringSettingsSource = Objects.requireNonNull(scoringSettingsSource, "scoringSettingsSource must not be null");
        this.batchProductionSource = Objects.requireNonNull(batchProductionSource, "batchProductionSource must not be null");
    }

    public SleeperLiveWaiverComparisonExecutionBundle.BundleReport run(String leagueId, String sleeperOwnerId)
        throws SQLException, IOException, InterruptedException {
        return runMeasured(leagueId, sleeperOwnerId).bundle();
    }

    public MeasuredReport runMeasured(String leagueId, String sleeperOwnerId)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");
        long totalStarted = System.nanoTime();

        long candidateStarted = System.nanoTime();
        CandidateEvidence candidateEvidence = Objects.requireNonNull(
            candidateEvidenceSource.load(normalizedLeagueId), "BF-737 candidate evidence must not be null");
        long candidateMs = elapsedMs(candidateStarted);

        long rosterStarted = System.nanoTime();
        RosterEvidence rosterEvidence = Objects.requireNonNull(
            rosterEvidenceSource.load(normalizedLeagueId, normalizedOwnerId), "BF-737 roster evidence must not be null");
        long rosterMs = elapsedMs(rosterStarted);

        long methodologyStarted = System.nanoTime();
        var readiness = SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.assess(
            normalizedLeagueId,
            normalizedOwnerId,
            candidateEvidence.readinessFrame(),
            rosterEvidence.readinessFrame());
        int[] scoringCalls = {0};
        var methodology = new SleeperLiveWaiverCandidateRosterComparisonMethodology(
            (requestedLeagueId, requestedOwnerId) -> methodologyReadinessFrame(readiness),
            requestedLeagueId -> {
                scoringCalls[0]++;
                return scoringSettingsSource.load(requestedLeagueId);
            }).audit(normalizedLeagueId, normalizedOwnerId);
        long methodologyMs = elapsedMs(methodologyStarted);

        int[] productionCalls = {0};
        long[] productionMs = {0L};
        var bundle = new SleeperLiveWaiverComparisonExecutionBundle(
            (requestedLeagueId, requestedOwnerId) -> methodology,
            requestedLeagueId -> candidateEvidence.comparisonFrame(),
            (requestedLeagueId, requestedOwnerId) -> rosterEvidence.comparisonFrame(),
            (playerIds, season) -> {
                long started = System.nanoTime();
                productionCalls[0]++;
                try {
                    return batchProductionSource.load(playerIds, season);
                } finally {
                    productionMs[0] += elapsedMs(started);
                }
            });
        var report = bundle.run(normalizedLeagueId, normalizedOwnerId);
        long totalMs = elapsedMs(totalStarted);

        if (scoringCalls[0] != 1 || productionCalls[0] > 1) {
            throw new IllegalStateException(
                "BF-737 BLOCKED: one-execution source call counts diverged from the governed comparison contract");
        }
        long residualMs = Math.max(0L,
            totalMs - candidateMs - rosterMs - methodologyMs - productionMs[0]);
        return new MeasuredReport(
            report,
            new StageTiming(candidateMs, rosterMs, methodologyMs, productionMs[0], residualMs, totalMs));
    }

    static CandidateEvidence candidateEvidence(
        SleeperLiveWaiverPregameEvidenceReadinessAudit.ReadinessReport report) {
        Objects.requireNonNull(report, "BF-609 report must not be null");
        if (!SleeperLiveWaiverPregameEvidenceReadinessAudit.POLICY_ID.equals(report.policyId())) {
            throw new IllegalStateException("BF-737 BLOCKED: unexpected BF-609 policy");
        }

        int teamUnknown = report.summaries()
            .get(SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.CURRENT_TEAM_UNKNOWN).total();
        int depthMissing = report.summaries()
            .get(SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.DEPTH_EVIDENCE_MISSING).total();
        int withPrior = report.summaries()
            .get(SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION).total();
        int withoutPrior = report.summaries()
            .get(SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITHOUT_PRIOR_PRODUCTION).total();

        List<SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateEntry> readinessEntries =
            report.candidates().stream().map(value -> {
                var stratum = value.primaryStratum();
                boolean reviewable = stratum
                    == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION
                    || stratum
                    == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITHOUT_PRIOR_PRODUCTION;
                boolean priorPresent = stratum
                    == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION;
                return new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateEntry(
                    value.dossier().market().sleeperPlayerId(),
                    value.dossier().market().displayName(),
                    value.dossier().market().position(),
                    reviewable,
                    priorPresent);
            }).toList();
        var readinessFrame = new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateFrame(
            report.leagueId(), report.marketSnapshotId(), report.candidateCount(),
            teamUnknown, depthMissing, withPrior, withoutPrior, readinessEntries);

        List<SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry> comparisonEntries = new ArrayList<>();
        for (var value : report.candidates()) {
            boolean candidateWithPrior = value.primaryStratum()
                == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION;
            boolean candidateWithoutPrior = value.primaryStratum()
                == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITHOUT_PRIOR_PRODUCTION;
            if (!candidateWithPrior && !candidateWithoutPrior) continue;
            var dossier = value.dossier();
            var market = dossier.market();
            var availability = dossier.availability();
            comparisonEntries.add(new SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry(
                market.sleeperPlayerId(), market.displayName(), market.position(), dossier.butlerPlayerId(),
                candidateWithPrior, market.addCount(), market.dropCount(), market.netAddAttention(), market.frameMembership(),
                availability.currentTeam(), availability.currentStatus(), availability.injuryStatus(),
                availability.depthChartPosition(), availability.depthChartOrder()));
        }
        comparisonEntries.sort(Comparator.comparing(
            SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry::sleeperPlayerId));
        var comparisonFrame = new SleeperLiveWaiverComparisonExecutionBundle.CandidateFrame(
            report.leagueId(), report.marketSnapshotId(), report.candidateCount(),
            comparisonEntries.size(), List.copyOf(comparisonEntries));
        return new CandidateEvidence(readinessFrame, comparisonFrame);
    }

    static RosterEvidence rosterEvidence(
        SleeperLiveWaiverTargetRosterProductionComparabilityAudit.AuditReport report) {
        Objects.requireNonNull(report, "BF-611 report must not be null");
        if (!SleeperLiveWaiverTargetRosterProductionComparabilityAudit.POLICY_ID.equals(report.policyId())) {
            throw new IllegalStateException("BF-737 BLOCKED: unexpected BF-611 policy");
        }

        List<SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterEntry> readinessEntries =
            report.players().stream().map(value ->
                new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterEntry(
                    value.target().sleeperPlayerId(),
                    value.target().displayName(),
                    value.target().position(),
                    value.target().rosterSlot(),
                    value.target().mappingState(),
                    value.state()
                        == SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_PRESENT))
                .toList();
        var readinessFrame = new SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterFrame(
            report.leagueId(), report.marketSnapshotId(), report.waiverSnapshotId(),
            report.sleeperLeagueId(), report.providerSeason(), report.providerStatus(), report.providerLeg(),
            report.sleeperOwnerId(), report.rosterId(), report.targetPlayerCount(),
            report.starterCount(), report.benchCount(), report.reserveCount(), report.taxiCount(),
            report.priorProductionPresent(), report.priorProductionMissing(), readinessEntries);

        List<SleeperLiveWaiverComparisonExecutionBundle.RosterEntry> comparisonEntries = report.players().stream()
            .map(value -> new SleeperLiveWaiverComparisonExecutionBundle.RosterEntry(
                value.target().sleeperPlayerId(), value.target().displayName(), value.target().position(),
                value.target().rosterSlot(), value.target().butlerPlayerId(),
                value.state()
                    == SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_PRESENT))
            .sorted(Comparator.comparing(SleeperLiveWaiverComparisonExecutionBundle.RosterEntry::sleeperPlayerId))
            .toList();
        var comparisonFrame = new SleeperLiveWaiverComparisonExecutionBundle.RosterFrame(
            report.leagueId(), report.marketSnapshotId(), report.waiverSnapshotId(), report.sleeperLeagueId(),
            report.sleeperOwnerId(), report.rosterId(), report.targetPlayerCount(),
            report.starterCount(), report.benchCount(), report.reserveCount(), report.taxiCount(), comparisonEntries);
        return new RosterEvidence(readinessFrame, comparisonFrame);
    }

    private static SleeperLiveWaiverCandidateRosterComparisonMethodology.ReadinessFrame methodologyReadinessFrame(
        SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.AuditReport report) {
        List<SleeperLiveWaiverCandidateRosterComparisonMethodology.ProtectedMissingProduction> missing =
            report.missingRosterProduction().stream()
                .map(value -> new SleeperLiveWaiverCandidateRosterComparisonMethodology.ProtectedMissingProduction(
                    value.sleeperPlayerId(), value.displayName(), value.position(), value.rosterSlot()))
                .toList();
        return new SleeperLiveWaiverCandidateRosterComparisonMethodology.ReadinessFrame(
            report.leagueId(), report.sleeperOwnerId(), report.marketSnapshotId(), report.waiverSnapshotId(),
            report.sleeperLeagueId(), report.providerSeason(), report.providerStatus(), report.providerLeg(), report.rosterId(),
            report.candidateCount(), report.reviewableCandidateCount(),
            report.reviewableWithPriorProduction(), report.reviewableWithoutPriorProduction(),
            report.targetPlayerCount(), report.starterCount(), report.benchCount(), report.reserveCount(), report.taxiCount(),
            report.targetPriorProductionPresent(), report.targetPriorProductionMissing(), missing, report.state());
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    @FunctionalInterface
    interface CandidateEvidenceSource {
        CandidateEvidence load(String leagueId) throws SQLException;
    }

    @FunctionalInterface
    interface RosterEvidenceSource {
        RosterEvidence load(String leagueId, String ownerId) throws SQLException, IOException, InterruptedException;
    }

    record CandidateEvidence(
        SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.CandidateFrame readinessFrame,
        SleeperLiveWaiverComparisonExecutionBundle.CandidateFrame comparisonFrame) {
        CandidateEvidence {
            Objects.requireNonNull(readinessFrame, "readinessFrame must not be null");
            Objects.requireNonNull(comparisonFrame, "comparisonFrame must not be null");
        }
    }

    record RosterEvidence(
        SleeperLiveWaiverCandidateRosterComparisonReadinessAudit.RosterFrame readinessFrame,
        SleeperLiveWaiverComparisonExecutionBundle.RosterFrame comparisonFrame) {
        RosterEvidence {
            Objects.requireNonNull(readinessFrame, "readinessFrame must not be null");
            Objects.requireNonNull(comparisonFrame, "comparisonFrame must not be null");
        }
    }

    public record StageTiming(
        long candidateEvidenceMs,
        long rosterEvidenceMs,
        long methodologyMs,
        long productionLoadMs,
        long residualMs,
        long totalMs) {
        public StageTiming {
            if (candidateEvidenceMs < 0 || rosterEvidenceMs < 0 || methodologyMs < 0
                || productionLoadMs < 0 || residualMs < 0 || totalMs < 0) {
                throw new IllegalArgumentException("BF-737 timing values must be non-negative");
            }
        }
    }

    public record MeasuredReport(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        StageTiming timing) {
        public MeasuredReport {
            Objects.requireNonNull(bundle, "bundle must not be null");
            Objects.requireNonNull(timing, "timing must not be null");
        }
    }
}
