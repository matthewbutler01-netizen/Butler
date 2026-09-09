package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.GovernedRecommendationAuditRepository;
import io.butler.bet.data.GovernedRecommendationExplanationRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

/** BF-653 explicit immutable explanation capture for an existing BF-627 governed audit. */
public final class SleeperLiveWaiverGovernedExplanationCapture {
    public static final String POLICY_ID =
        "sleeper-live-waiver-governed-explanation-v1-bf627-audit-companion-immutable-explicit";

    static final String TYPE_CROSS_POSITION = "CROSS_POSITION_BF625_TRANSACTION_DOMINANCE";
    static final String TYPE_SAME_POSITION = "SAME_POSITION_BF618_DIRECTIONAL_SELECTION";
    static final String TYPE_NO_TRANSACTION = "NO_GOVERNED_TRANSACTION";

    static final String CROSS_POSITION_REASON =
        "BF-624 produced the unique complete add/drop transaction whose governed supported-subtotal-per-game "
            + "improvement is strictly greater on every compatible common evidence source. No position preference "
            + "or hidden market/depth/injury tiebreaker was used.";
    static final String SAME_POSITION_REASON =
        "The add is the unique historical finalist directionally supported over every other same-position historical "
            + "finalist under the frozen BF-614 evidence method, and the drop is the unique weakest production-backed "
            + "exact-position BENCH/RESERVE comparator already directionally supported for replacement by BF-615.";
    static final String NO_TRANSACTION_REASON =
        "The governed final method did not produce one unique evidence-supported add/drop pair. Cross-position ties "
            + "or incompatible evidence remain unresolved; Butler will not manufacture a tiebreaker.";

    private final GovernedRecommendationAuditRepository auditRepository;
    private final GovernedRecommendationExplanationRepository explanationRepository;
    private final RecommendationSource recommendationSource;
    private final EvidenceSource evidenceSource;
    private final Clock clock;

    public SleeperLiveWaiverGovernedExplanationCapture(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.auditRepository = new GovernedRecommendationAuditRepository(database);
        this.explanationRepository = new GovernedRecommendationExplanationRepository(database);
        this.recommendationSource = (leagueId, ownerId) ->
            new SleeperLiveWaiverFinalRecommendationBundle(database).run(leagueId, ownerId);
        this.evidenceSource = recommendation ->
            new SleeperLiveWaiverCrossPositionTransactionEvidence(database).explain(recommendation);
        this.clock = Clock.systemUTC();
    }

    SleeperLiveWaiverGovernedExplanationCapture(
        GovernedRecommendationAuditRepository auditRepository,
        GovernedRecommendationExplanationRepository explanationRepository,
        RecommendationSource recommendationSource,
        EvidenceSource evidenceSource,
        Clock clock) {
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.explanationRepository = Objects.requireNonNull(explanationRepository, "explanationRepository must not be null");
        this.recommendationSource = Objects.requireNonNull(recommendationSource, "recommendationSource must not be null");
        this.evidenceSource = Objects.requireNonNull(evidenceSource, "evidenceSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public CaptureReport capture(SleeperPersonalizedTargetService.VerifiedTarget target, String auditId)
        throws SQLException, IOException, InterruptedException {
        validateVerifiedTarget(target);
        String normalizedAuditId = requireText(auditId, "auditId");
        GovernedRecommendationAuditRepository.AuditRecord audit = exactAudit(target.butlerLeagueId(), normalizedAuditId);
        reconcileTarget(target, audit);

        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation =
            recommendationSource.run(target.butlerLeagueId(), target.sleeperUserId());
        reconcileAudit(audit, recommendation);
        ExplanationPayload payload = explanationPayload(recommendation, evidenceSource);

        var desired = new GovernedRecommendationExplanationRepository.ExplanationRecord(
            null,
            audit.id(),
            POLICY_ID,
            payload.type(),
            payload.text(),
            payload.evidencePolicyId(),
            payload.evidenceTrace(),
            clock.instant());
        var persisted = explanationRepository.capture(desired);
        var readback = explanationRepository.findByAuditId(audit.id())
            .orElseThrow(() -> new IllegalStateException("BF-653 BLOCKED: explanation missing after capture"));
        if (!persisted.record().id().equals(readback.id())) {
            throw new IllegalStateException("BF-653 BLOCKED: explanation readback id does not match capture result");
        }

        return new CaptureReport(
            POLICY_ID,
            persisted.state(),
            readback.id(),
            readback.auditId(),
            readback.capturedAtUtc().toString(),
            readback.explanationType(),
            readback.explanationText(),
            readback.evidencePolicyId(),
            readback.evidenceTrace(),
            audit.marketSnapshotId(),
            audit.waiverSnapshotId(),
            audit.addSleeperPlayerId(),
            audit.dropSleeperPlayerId());
    }

    private GovernedRecommendationAuditRepository.AuditRecord exactAudit(String leagueId, String auditId)
        throws SQLException {
        List<GovernedRecommendationAuditRepository.AuditRecord> matches = auditRepository.findAllForLeague(leagueId)
            .stream()
            .filter(value -> value.id().equals(auditId))
            .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                "BF-653 BLOCKED: exact BF-627 audit id must resolve once for target league; found " + matches.size());
        }
        return matches.get(0);
    }

    static void reconcileTarget(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        GovernedRecommendationAuditRepository.AuditRecord audit) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(audit, "audit must not be null");
        if (!target.butlerLeagueId().equals(audit.leagueId())
            || !target.sleeperUserId().equals(audit.sleeperOwnerId())
            || !target.sleeperLeagueId().equals(audit.sleeperLeagueId())
            || target.rosterId() != audit.rosterId()
            || audit.season() != SleeperPersonalizedTargetService.TARGET_SEASON
            || !target.providerStatus().equals(audit.providerStatus())) {
            throw new IllegalStateException("BF-653 BLOCKED: BF-623 target does not reconcile to exact BF-627 audit");
        }
    }

    static void reconcileAudit(
        GovernedRecommendationAuditRepository.AuditRecord audit,
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation) {
        Objects.requireNonNull(audit, "audit must not be null");
        Objects.requireNonNull(recommendation, "recommendation must not be null");
        String addId = recommendation.recommendedAdd() == null ? null : recommendation.recommendedAdd().sleeperPlayerId();
        String dropId = recommendation.recommendedDrop() == null ? null : recommendation.recommendedDrop().sleeperPlayerId();
        if (!audit.leagueId().equals(recommendation.leagueId())
            || !audit.sleeperOwnerId().equals(recommendation.sleeperOwnerId())
            || !audit.sleeperLeagueId().equals(recommendation.sleeperLeagueId())
            || audit.rosterId() != recommendation.rosterId()
            || audit.season() != recommendation.providerSeason()
            || !audit.providerStatus().equals(recommendation.providerStatus())
            || !Objects.equals(audit.providerLeg(), recommendation.providerLeg())
            || !audit.marketSnapshotId().equals(recommendation.marketSnapshotId())
            || !audit.waiverSnapshotId().equals(recommendation.waiverSnapshotId())
            || !audit.bf618PolicyId().equals(recommendation.methodology().policyId())
            || !audit.bf619PolicyId().equals(recommendation.selection().policyId())
            || !audit.bf620PolicyId().equals(recommendation.policyId())
            || !SleeperLiveWaiverFinalRecommendationBundle.BF624_POLICY_ID.equals(audit.bf624PolicyId())
            || !audit.selectionState().equals(recommendation.selection().state().name())
            || !audit.recommendationState().equals(recommendation.state().name())
            || !Objects.equals(audit.addSleeperPlayerId(), addId)
            || !Objects.equals(audit.dropSleeperPlayerId(), dropId)) {
            throw new IllegalStateException(
                "BF-653 BLOCKED: recomputed explanation source does not exactly reproduce immutable BF-627 audit");
        }
    }

    static ExplanationPayload explanationPayload(
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation,
        EvidenceSource evidenceSource)
        throws SQLException, IOException, InterruptedException {
        Objects.requireNonNull(recommendation, "recommendation must not be null");
        Objects.requireNonNull(evidenceSource, "evidenceSource must not be null");
        if (recommendation.state()
            == SleeperLiveWaiverFinalRecommendationBundle.RecommendationState.NO_GOVERNED_TRANSACTION) {
            return new ExplanationPayload(TYPE_NO_TRANSACTION, NO_TRANSACTION_REASON, null, null);
        }

        if (recommendation.methodology().historicalFinalistPositions().size() <= 1) {
            return new ExplanationPayload(TYPE_SAME_POSITION, SAME_POSITION_REASON, null, null);
        }

        SleeperLiveWaiverCrossPositionTransactionEvidence.EvidenceReport evidence = evidenceSource.explain(recommendation);
        if (evidence.state() != SleeperLiveWaiverCrossPositionTransactionEvidence.EvidenceState.RECONCILED
            || !recommendation.leagueId().equals(evidence.leagueId())
            || !recommendation.sleeperOwnerId().equals(evidence.sleeperOwnerId())
            || !recommendation.marketSnapshotId().equals(evidence.marketSnapshotId())
            || !recommendation.waiverSnapshotId().equals(evidence.waiverSnapshotId())
            || recommendation.selection().state() != evidence.selectionState()) {
            throw new IllegalStateException("BF-653 BLOCKED: BF-625 explanation evidence did not reconcile to BF-620");
        }
        List<SleeperLiveWaiverCrossPositionTransactionEvidence.TransactionEvidence> selected = evidence.options().stream()
            .filter(SleeperLiveWaiverCrossPositionTransactionEvidence.TransactionEvidence::selected)
            .toList();
        if (selected.size() != 1) {
            throw new IllegalStateException(
                "BF-653 BLOCKED: cross-position explanation requires exactly one BF-625 selected option; found "
                    + selected.size());
        }
        var option = selected.get(0);
        if (!recommendation.recommendedAdd().sleeperPlayerId().equals(option.add().sleeperPlayerId())
            || !recommendation.recommendedDrop().sleeperPlayerId().equals(option.drop().sleeperPlayerId())) {
            throw new IllegalStateException("BF-653 BLOCKED: BF-625 selected option differs from audited BF-620 pair");
        }
        return new ExplanationPayload(
            TYPE_CROSS_POSITION,
            CROSS_POSITION_REASON,
            SleeperLiveWaiverCrossPositionTransactionEvidence.POLICY_ID,
            canonicalEvidenceTrace(option));
    }

    static String canonicalEvidenceTrace(
        SleeperLiveWaiverCrossPositionTransactionEvidence.TransactionEvidence option) {
        List<String> pieces = new ArrayList<>();
        pieces.add("ADD=" + option.add().sleeperPlayerId());
        pieces.add("DROP=" + option.drop().sleeperPlayerId());
        for (String source : new TreeSet<>(option.improvementBySource().keySet())) {
            Double improvement = option.improvementBySource().get(source);
            List<String> keys = option.scoringKeysBySource().get(source);
            if (improvement == null || keys == null || keys.isEmpty()) {
                throw new IllegalStateException("BF-653 BLOCKED: selected BF-625 source evidence is incomplete");
            }
            List<String> sortedKeys = keys.stream().sorted(Comparator.naturalOrder()).toList();
            pieces.add("source=" + source
                + ",improvement=" + String.format(Locale.ROOT, "%.4f", improvement)
                + ",scoringKeys=" + sortedKeys);
        }
        if (pieces.size() <= 2) {
            throw new IllegalStateException("BF-653 BLOCKED: selected BF-625 option has no source improvement evidence");
        }
        return String.join(" | ", pieces);
    }

    private static void validateVerifiedTarget(SleeperPersonalizedTargetService.VerifiedTarget target) {
        Objects.requireNonNull(target, "target must not be null");
        if (!SleeperPersonalizedTargetService.BF623_POLICY_ID.equals(target.policyId())
            || target.state() != SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED) {
            throw new IllegalStateException("BF-653 BLOCKED: target is not BF-623 live verified");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    @FunctionalInterface
    interface RecommendationSource {
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport run(String leagueId, String ownerId)
            throws SQLException, IOException, InterruptedException;
    }

    @FunctionalInterface
    interface EvidenceSource {
        SleeperLiveWaiverCrossPositionTransactionEvidence.EvidenceReport explain(
            SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation)
            throws SQLException, IOException, InterruptedException;
    }

    record ExplanationPayload(String type, String text, String evidencePolicyId, String evidenceTrace) {}

    public record CaptureReport(
        String policyId,
        GovernedRecommendationExplanationRepository.CaptureState captureState,
        String explanationId,
        String auditId,
        String capturedAtUtc,
        String explanationType,
        String explanationText,
        String evidencePolicyId,
        String evidenceTrace,
        String marketSnapshotId,
        String waiverSnapshotId,
        String addSleeperPlayerId,
        String dropSleeperPlayerId) {
        public CaptureReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-653 policyId");
            Objects.requireNonNull(captureState, "captureState must not be null");
        }
    }
}
