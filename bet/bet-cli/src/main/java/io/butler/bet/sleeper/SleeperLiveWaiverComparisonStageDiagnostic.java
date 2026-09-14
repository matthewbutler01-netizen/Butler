package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.domain.PlayerSeasonProduction;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** BF-736 read-only one-pass timing around the existing BF-615 comparison bundle sources. */
public final class SleeperLiveWaiverComparisonStageDiagnostic {
    private final SleeperLiveWaiverComparisonExecutionBundle.MethodologySource methodologySource;
    private final SleeperLiveWaiverComparisonExecutionBundle.CandidateSource candidateSource;
    private final SleeperLiveWaiverComparisonExecutionBundle.RosterSource rosterSource;
    private final SleeperLiveWaiverComparisonExecutionBundle.BatchProductionSource batchProductionSource;

    public SleeperLiveWaiverComparisonStageDiagnostic(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        PlayerSeasonProductionRepository productionRepository = new PlayerSeasonProductionRepository(database);
        this.methodologySource = (leagueId, ownerId) ->
            new SleeperLiveWaiverCandidateRosterComparisonMethodology(database).audit(leagueId, ownerId);
        this.candidateSource = leagueId -> candidateFrame(
            new SleeperLiveWaiverPregameEvidenceReadinessAudit(database).audit(leagueId));
        this.rosterSource = (leagueId, ownerId) -> rosterFrame(
            new SleeperLiveWaiverTargetRosterProductionComparabilityAudit(database).audit(leagueId, ownerId));
        this.batchProductionSource = productionRepository::findByPlayerIdsAndSeason;
    }

    SleeperLiveWaiverComparisonStageDiagnostic(
        SleeperLiveWaiverComparisonExecutionBundle.MethodologySource methodologySource,
        SleeperLiveWaiverComparisonExecutionBundle.CandidateSource candidateSource,
        SleeperLiveWaiverComparisonExecutionBundle.RosterSource rosterSource,
        SleeperLiveWaiverComparisonExecutionBundle.BatchProductionSource batchProductionSource) {
        this.methodologySource = Objects.requireNonNull(methodologySource, "methodologySource must not be null");
        this.candidateSource = Objects.requireNonNull(candidateSource, "candidateSource must not be null");
        this.rosterSource = Objects.requireNonNull(rosterSource, "rosterSource must not be null");
        this.batchProductionSource = Objects.requireNonNull(batchProductionSource, "batchProductionSource must not be null");
    }

    public DiagnosticReport measure(String leagueId, String sleeperOwnerId)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");
        MutableTiming timing = new MutableTiming();

        SleeperLiveWaiverComparisonExecutionBundle bundle = new SleeperLiveWaiverComparisonExecutionBundle(
            (requestedLeagueId, requestedOwnerId) -> {
                long started = System.nanoTime();
                timing.methodologyCalls++;
                try {
                    return methodologySource.audit(requestedLeagueId, requestedOwnerId);
                } finally {
                    timing.methodologyMs += elapsedMs(started);
                }
            },
            requestedLeagueId -> {
                long started = System.nanoTime();
                timing.candidateCalls++;
                try {
                    return candidateSource.audit(requestedLeagueId);
                } finally {
                    timing.candidateFrameMs += elapsedMs(started);
                }
            },
            (requestedLeagueId, requestedOwnerId) -> {
                long started = System.nanoTime();
                timing.rosterCalls++;
                try {
                    return rosterSource.audit(requestedLeagueId, requestedOwnerId);
                } finally {
                    timing.rosterFrameMs += elapsedMs(started);
                }
            },
            (playerIds, season) -> {
                long started = System.nanoTime();
                timing.productionCalls++;
                try {
                    return batchProductionSource.load(playerIds, season);
                } finally {
                    timing.productionLoadMs += elapsedMs(started);
                }
            });

        long totalStarted = System.nanoTime();
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport report =
            bundle.run(normalizedLeagueId, normalizedOwnerId);
        long totalMs = elapsedMs(totalStarted);

        if (timing.methodologyCalls != 1 || timing.candidateCalls != 1 || timing.rosterCalls != 1
            || timing.productionCalls > 1) {
            throw new IllegalStateException(
                "BF-736 BLOCKED: comparison diagnostic source call counts diverged from the BF-615 execution contract");
        }

        long measuredSourceMs = timing.methodologyMs + timing.candidateFrameMs
            + timing.rosterFrameMs + timing.productionLoadMs;
        long residualMs = Math.max(0L, totalMs - measuredSourceMs);
        return new DiagnosticReport(
            report,
            new StageTiming(
                timing.methodologyMs,
                timing.candidateFrameMs,
                timing.rosterFrameMs,
                timing.productionLoadMs,
                residualMs,
                totalMs));
    }

    private static SleeperLiveWaiverComparisonExecutionBundle.CandidateFrame candidateFrame(
        SleeperLiveWaiverPregameEvidenceReadinessAudit.ReadinessReport report) {
        List<SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry> reviewable = new ArrayList<>();
        for (var value : report.candidates()) {
            boolean withPrior = value.primaryStratum()
                == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION;
            boolean withoutPrior = value.primaryStratum()
                == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITHOUT_PRIOR_PRODUCTION;
            if (!withPrior && !withoutPrior) continue;
            var dossier = value.dossier();
            var market = dossier.market();
            var availability = dossier.availability();
            reviewable.add(new SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry(
                market.sleeperPlayerId(), market.displayName(), market.position(), dossier.butlerPlayerId(), withPrior,
                market.addCount(), market.dropCount(), market.netAddAttention(), market.frameMembership(),
                availability.currentTeam(), availability.currentStatus(), availability.injuryStatus(),
                availability.depthChartPosition(), availability.depthChartOrder()));
        }
        reviewable.sort(Comparator.comparing(SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry::sleeperPlayerId));
        return new SleeperLiveWaiverComparisonExecutionBundle.CandidateFrame(
            report.leagueId(), report.marketSnapshotId(), report.candidateCount(),
            reviewable.size(), List.copyOf(reviewable));
    }

    private static SleeperLiveWaiverComparisonExecutionBundle.RosterFrame rosterFrame(
        SleeperLiveWaiverTargetRosterProductionComparabilityAudit.AuditReport report) {
        List<SleeperLiveWaiverComparisonExecutionBundle.RosterEntry> entries = report.players().stream()
            .map(value -> new SleeperLiveWaiverComparisonExecutionBundle.RosterEntry(
                value.target().sleeperPlayerId(), value.target().displayName(), value.target().position(),
                value.target().rosterSlot(), value.target().butlerPlayerId(),
                value.state() == SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_PRESENT))
            .sorted(Comparator.comparing(SleeperLiveWaiverComparisonExecutionBundle.RosterEntry::sleeperPlayerId))
            .toList();
        return new SleeperLiveWaiverComparisonExecutionBundle.RosterFrame(
            report.leagueId(), report.marketSnapshotId(), report.waiverSnapshotId(), report.sleeperLeagueId(),
            report.sleeperOwnerId(), report.rosterId(), report.targetPlayerCount(),
            report.starterCount(), report.benchCount(), report.reserveCount(), report.taxiCount(), entries);
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record StageTiming(
        long methodologyMs,
        long candidateFrameMs,
        long rosterFrameMs,
        long productionLoadMs,
        long residualMs,
        long totalMs) {
        public StageTiming {
            if (methodologyMs < 0 || candidateFrameMs < 0 || rosterFrameMs < 0
                || productionLoadMs < 0 || residualMs < 0 || totalMs < 0) {
                throw new IllegalArgumentException("BF-736 timing values must be non-negative");
            }
        }
    }

    public record DiagnosticReport(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        StageTiming timing) {
        public DiagnosticReport {
            Objects.requireNonNull(bundle, "bundle must not be null");
            Objects.requireNonNull(timing, "timing must not be null");
        }
    }

    private static final class MutableTiming {
        private int methodologyCalls;
        private int candidateCalls;
        private int rosterCalls;
        private int productionCalls;
        private long methodologyMs;
        private long candidateFrameMs;
        private long rosterFrameMs;
        private long productionLoadMs;
    }
}
