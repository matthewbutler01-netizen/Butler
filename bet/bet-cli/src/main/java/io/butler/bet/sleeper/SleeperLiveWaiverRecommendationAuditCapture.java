package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.GovernedRecommendationAuditRepository;
import io.butler.bet.data.GovernedRecommendationExplanationRepository;
import io.butler.bet.data.PlayerSeasonProductionRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;

/** BF-627 explicit immutable audit capture of an already-governed BF-620 outcome. */
public final class SleeperLiveWaiverRecommendationAuditCapture {
    public static final String POLICY_ID =
        "sleeper-live-waiver-recommendation-audit-v1-bf623-final-lineage-immutable-explicit";

    private final DecisionSource decisionSource;
    private final GovernedRecommendationAuditRepository repository;
    private final GovernedRecommendationExplanationRepository explanationRepository;
    private final Clock clock;

    public SleeperLiveWaiverRecommendationAuditCapture(Database database) {
        this(
            productionDecisionSource(database),
            new GovernedRecommendationAuditRepository(database),
            new GovernedRecommendationExplanationRepository(database),
            Clock.systemUTC());
    }

    SleeperLiveWaiverRecommendationAuditCapture(
        RecommendationSource recommendationSource,
        GovernedRecommendationAuditRepository repository,
        GovernedRecommendationExplanationRepository explanationRepository,
        Clock clock) {
        this(
            (DecisionSource) (leagueId, ownerId) -> new DecisionFrame(recommendationSource.run(leagueId, ownerId), null),
            repository,
            explanationRepository,
            clock);
    }

    SleeperLiveWaiverRecommendationAuditCapture(
        DecisionSource decisionSource,
        GovernedRecommendationAuditRepository repository,
        GovernedRecommendationExplanationRepository explanationRepository,
        Clock clock) {
        this.decisionSource = Objects.requireNonNull(decisionSource, "decisionSource must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.explanationRepository = Objects.requireNonNull(explanationRepository, "explanationRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    private static DecisionSource productionDecisionSource(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        return (leagueId, ownerId) -> {
            SleeperLiveWaiverFinalRecommendationBundle finalBundle =
                new SleeperLiveWaiverFinalRecommendationBundle(database);
            var execution = finalBundle.execute(leagueId, ownerId);
            var recommendation = execution.recommendation();
            SleeperLiveWaiverCrossPositionTransactionEvidence.EvidenceReport evidence = null;
            if (recommendation.state()
                    == SleeperLiveWaiverFinalRecommendationBundle.RecommendationState.RECOMMEND_ADD_DROP
                && recommendation.methodology().historicalFinalistPositions().size() > 1) {
                PlayerSeasonProductionRepository productionRepository =
                    new PlayerSeasonProductionRepository(database);
                evidence = new SleeperLiveWaiverCrossPositionTransactionEvidence(
                    (ignoredLeague, ignoredOwner) -> execution.bundle(),
                    productionRepository::findByPlayerId)
                    .explain(recommendation);
            }
            return new DecisionFrame(recommendation, evidence);
        };
    }

    public CaptureReport capture(SleeperPersonalizedTargetService.VerifiedTarget target)
        throws SQLException, IOException, InterruptedException {
        validateVerifiedTarget(target);

        DecisionFrame decision = decisionSource.run(target.butlerLeagueId(), target.sleeperUserId());
        var recommendation = decision.recommendation();
        reconcile(target, recommendation);

        String addId = recommendation.recommendedAdd() == null
            ? null : recommendation.recommendedAdd().sleeperPlayerId();
        String dropId = recommendation.recommendedDrop() == null
            ? null : recommendation.recommendedDrop().sleeperPlayerId();
        String lineageKey = lineageKey(target, recommendation);

        var desired = new GovernedRecommendationAuditRepository.AuditRecord(
            null,
            lineageKey,
            POLICY_ID,
            recommendation.leagueId(),
            recommendation.sleeperOwnerId(),
            recommendation.sleeperLeagueId(),
            recommendation.rosterId(),
            recommendation.providerSeason(),
            recommendation.providerStatus(),
            recommendation.providerLeg(),
            recommendation.marketSnapshotId(),
            recommendation.waiverSnapshotId(),
            recommendation.methodology().policyId(),
            recommendation.selection().policyId(),
            recommendation.policyId(),
            SleeperLiveWaiverFinalRecommendationBundle.BF624_POLICY_ID,
            recommendation.selection().state().name(),
            recommendation.state().name(),
            addId,
            dropId,
            clock.instant());

        var persisted = repository.capture(desired);
        var readback = repository.findByLineageKey(lineageKey)
            .orElseThrow(() -> new IllegalStateException("BF-627 BLOCKED: audit record missing after capture"));
        if (!persisted.record().id().equals(readback.id())) {
            throw new IllegalStateException("BF-627 BLOCKED: audit readback id does not match capture result");
        }

        SleeperLiveWaiverGovernedExplanationCapture.EvidenceSource evidenceSource = ignored -> {
            if (decision.crossPositionEvidence() == null) {
                throw new IllegalStateException(
                    "BF-660 BLOCKED: exact cross-position BF-625 evidence is missing from BF-627 decision frame");
            }
            return decision.crossPositionEvidence();
        };
        var explanation = SleeperLiveWaiverGovernedExplanationCapture.captureCompanion(
            explanationRepository, readback, recommendation, evidenceSource, clock);

        return new CaptureReport(
            POLICY_ID,
            persisted.state(),
            readback.id(),
            readback.capturedAtUtc().toString(),
            readback.leagueId(),
            readback.sleeperOwnerId(),
            readback.sleeperLeagueId(),
            readback.rosterId(),
            readback.season(),
            readback.providerStatus(),
            readback.providerLeg(),
            readback.marketSnapshotId(),
            readback.waiverSnapshotId(),
            readback.selectionState(),
            readback.recommendationState(),
            readback.addSleeperPlayerId(),
            readback.dropSleeperPlayerId(),
            repository.countForLeague(readback.leagueId()),
            explanation.captureState(),
            explanation.explanationId(),
            explanation.explanationType(),
            explanation.explanationText(),
            explanation.evidencePolicyId(),
            explanation.evidenceTrace());
    }

    private static void validateVerifiedTarget(SleeperPersonalizedTargetService.VerifiedTarget target) {
        Objects.requireNonNull(target, "target must not be null");
        if (!SleeperPersonalizedTargetService.BF623_POLICY_ID.equals(target.policyId())) {
            throw new IllegalStateException("BF-627 BLOCKED: target is not BF-623 verified");
        }
        if (target.state() != SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED) {
            throw new IllegalStateException("BF-627 BLOCKED: personalized target state is not live verified");
        }
    }

    static void reconcile(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation) {
        Objects.requireNonNull(recommendation, "recommendation must not be null");
        if (!target.butlerLeagueId().equals(recommendation.leagueId())
            || !target.sleeperUserId().equals(recommendation.sleeperOwnerId())
            || !target.sleeperLeagueId().equals(recommendation.sleeperLeagueId())
            || target.rosterId() != recommendation.rosterId()) {
            throw new IllegalStateException("BF-627 BLOCKED: BF-623 identity does not reconcile to BF-620 recommendation identity");
        }
        if (recommendation.providerSeason() != SleeperPersonalizedTargetService.TARGET_SEASON
            || !target.providerStatus().equals(recommendation.providerStatus())) {
            throw new IllegalStateException("BF-627 BLOCKED: BF-623 provider season/status does not reconcile to BF-620");
        }
        if (!SleeperLiveWaiverFinalRecommendationBundle.BF620_POLICY_ID.equals(recommendation.policyId())
            || !SleeperLiveWaiverFinalRecommendationBundle.BF618_POLICY_ID.equals(recommendation.methodology().policyId())
            || !SleeperLiveWaiverFinalRecommendationBundle.BF619_POLICY_ID.equals(recommendation.selection().policyId())) {
            throw new IllegalStateException("BF-627 BLOCKED: final recommendation policy lineage is not the governed BF-618/BF-619/BF-620 chain");
        }
        if (!recommendation.marketSnapshotId().equals(recommendation.methodology().marketSnapshotId())
            || !recommendation.marketSnapshotId().equals(recommendation.selection().marketSnapshotId())) {
            throw new IllegalStateException("BF-627 BLOCKED: BF-603 market snapshot lineage is inconsistent inside the final recommendation");
        }
        if (recommendation.marketSnapshotId() == null || recommendation.marketSnapshotId().isBlank()
            || recommendation.waiverSnapshotId() == null || recommendation.waiverSnapshotId().isBlank()) {
            throw new IllegalStateException("BF-627 BLOCKED: BF-603/BF-602 snapshot lineage is incomplete");
        }

        boolean recommend = recommendation.state()
            == SleeperLiveWaiverFinalRecommendationBundle.RecommendationState.RECOMMEND_ADD_DROP;
        if (recommend) {
            if (recommendation.selection().state()
                != SleeperLiveWaiverFinalRecommendationBundle.SelectionState.UNIQUE_ADD_DROP_SELECTED
                || recommendation.recommendedAdd() == null || recommendation.recommendedDrop() == null
                || recommendation.selection().selectedAdd() == null || recommendation.selection().selectedDrop() == null
                || !recommendation.recommendedAdd().sleeperPlayerId()
                    .equals(recommendation.selection().selectedAdd().sleeperPlayerId())
                || !recommendation.recommendedDrop().sleeperPlayerId()
                    .equals(recommendation.selection().selectedDrop().sleeperPlayerId())) {
                throw new IllegalStateException("BF-627 BLOCKED: RECOMMEND_ADD_DROP payload does not reconcile to BF-619 selection");
            }
        } else if (recommendation.recommendedAdd() != null || recommendation.recommendedDrop() != null) {
            throw new IllegalStateException("BF-627 BLOCKED: NO_GOVERNED_TRANSACTION unexpectedly carries add/drop payload");
        }
    }

    private static String lineageKey(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation) {
        return String.join("|",
            "bf627-v1",
            target.policyId(),
            recommendation.leagueId(),
            recommendation.sleeperOwnerId(),
            recommendation.sleeperLeagueId(),
            Integer.toString(recommendation.rosterId()),
            Integer.toString(recommendation.providerSeason()),
            recommendation.providerStatus(),
            recommendation.providerLeg() == null ? "none" : recommendation.providerLeg().toString(),
            recommendation.marketSnapshotId(),
            recommendation.waiverSnapshotId(),
            recommendation.methodology().policyId(),
            recommendation.selection().policyId(),
            recommendation.policyId(),
            SleeperLiveWaiverFinalRecommendationBundle.BF624_POLICY_ID);
    }

    @FunctionalInterface
    interface RecommendationSource {
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport run(String leagueId, String ownerId)
            throws SQLException, IOException, InterruptedException;
    }

    @FunctionalInterface
    interface DecisionSource {
        DecisionFrame run(String leagueId, String ownerId)
            throws SQLException, IOException, InterruptedException;
    }

    record DecisionFrame(
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation,
        SleeperLiveWaiverCrossPositionTransactionEvidence.EvidenceReport crossPositionEvidence) {
        DecisionFrame {
            Objects.requireNonNull(recommendation, "recommendation must not be null");
        }
    }

    public record CaptureReport(
        String policyId,
        GovernedRecommendationAuditRepository.CaptureState captureState,
        String auditId,
        String capturedAtUtc,
        String leagueId,
        String sleeperOwnerId,
        String sleeperLeagueId,
        int rosterId,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        String marketSnapshotId,
        String waiverSnapshotId,
        String selectionState,
        String recommendationState,
        String addSleeperPlayerId,
        String dropSleeperPlayerId,
        int retainedAuditRecordsForLeague,
        GovernedRecommendationExplanationRepository.CaptureState explanationCaptureState,
        String explanationId,
        String explanationType,
        String explanationText,
        String explanationEvidencePolicyId,
        String explanationEvidenceTrace) {
        public CaptureReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-627 policyId");
            Objects.requireNonNull(captureState, "captureState must not be null");
            Objects.requireNonNull(explanationCaptureState, "explanationCaptureState must not be null");
            Objects.requireNonNull(explanationId, "explanationId must not be null");
            Objects.requireNonNull(explanationType, "explanationType must not be null");
            Objects.requireNonNull(explanationText, "explanationText must not be null");
        }
    }

}
