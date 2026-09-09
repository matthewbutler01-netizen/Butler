package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.GovernedRecommendationAuditRepository;
import io.butler.bet.data.GovernedRecommendationExplanationRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** BF-653 read-only persisted explanation lookup. Never invokes BF-618/BF-620/BF-625. */
public final class SleeperLiveWaiverGovernedExplanationLookup {
    public static final String POLICY_ID =
        "sleeper-live-waiver-governed-explanation-lookup-v1-bf627-bf653-read-only";

    private final GovernedRecommendationAuditRepository auditRepository;
    private final GovernedRecommendationExplanationRepository explanationRepository;

    public SleeperLiveWaiverGovernedExplanationLookup(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.auditRepository = new GovernedRecommendationAuditRepository(database);
        this.explanationRepository = new GovernedRecommendationExplanationRepository(database);
    }

    SleeperLiveWaiverGovernedExplanationLookup(
        GovernedRecommendationAuditRepository auditRepository,
        GovernedRecommendationExplanationRepository explanationRepository) {
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository must not be null");
        this.explanationRepository = Objects.requireNonNull(explanationRepository, "explanationRepository must not be null");
    }

    public LookupReport lookup(SleeperPersonalizedTargetService.VerifiedTarget target, String auditId)
        throws SQLException {
        validateVerifiedTarget(target);
        String normalizedAuditId = requireText(auditId, "auditId");
        GovernedRecommendationAuditRepository.AuditRecord audit = exactAudit(target.butlerLeagueId(), normalizedAuditId);
        SleeperLiveWaiverGovernedExplanationCapture.reconcileTarget(target, audit);

        var explanation = explanationRepository.findByAuditId(audit.id());
        if (explanation.isEmpty()) {
            return new LookupReport(
                POLICY_ID,
                LookupState.EXPLANATION_NOT_CAPTURED,
                audit.id(),
                null,
                null,
                null,
                null,
                null,
                null,
                audit.marketSnapshotId(),
                audit.waiverSnapshotId(),
                audit.addSleeperPlayerId(),
                audit.dropSleeperPlayerId());
        }

        var value = explanation.get();
        if (!audit.id().equals(value.auditId())
            || !SleeperLiveWaiverGovernedExplanationCapture.POLICY_ID.equals(value.policyId())) {
            throw new IllegalStateException("BF-653 BLOCKED: explanation companion lineage is not exact");
        }
        return new LookupReport(
            POLICY_ID,
            LookupState.EXPLANATION_READY,
            audit.id(),
            value.id(),
            value.capturedAtUtc().toString(),
            value.explanationType(),
            value.explanationText(),
            value.evidencePolicyId(),
            value.evidenceTrace(),
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

    public enum LookupState { EXPLANATION_READY, EXPLANATION_NOT_CAPTURED }

    public record LookupReport(
        String policyId,
        LookupState state,
        String auditId,
        String explanationId,
        String capturedAtUtc,
        String explanationType,
        String explanationText,
        String evidencePolicyId,
        String evidenceTrace,
        String marketSnapshotId,
        String waiverSnapshotId,
        String addSleeperPlayerId,
        String dropSleeperPlayerId) {
        public LookupReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-653 lookup policyId");
            Objects.requireNonNull(state, "state must not be null");
        }
    }
}
