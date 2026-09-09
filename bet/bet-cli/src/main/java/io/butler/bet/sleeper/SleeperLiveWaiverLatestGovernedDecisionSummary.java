package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.domain.Player;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;

/** BF-630 compact presentation tightened by BF-632 currentness, BF-634 age telemetry, BF-635 warning policy, BF-638 transaction lifecycle clarity, and BF-649 audited evidence traceability. */
public final class SleeperLiveWaiverLatestGovernedDecisionSummary {
    public static final String POLICY_ID =
        "sleeper-live-waiver-latest-governed-decision-summary-v5-bf623-bf628-bf629-bf631-bf633-bf635-bf638-transaction-lifecycle";
    public static final long REFRESH_WARNING_THRESHOLD_SECONDS = 6L * 60L * 60L;

    private final RevalidationSource revalidationSource;
    private final EvidenceLineageSource evidenceLineageSource;
    private final EvidenceAgeSource evidenceAgeSource;
    private final PlayerLookup playerLookup;

    public SleeperLiveWaiverLatestGovernedDecisionSummary(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        PlayerRepository players = new PlayerRepository(database);
        this.revalidationSource = target ->
            new SleeperLiveWaiverRecommendationActionabilityRevalidation(database).revalidate(target);
        this.evidenceLineageSource = target ->
            new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation(database).revalidate(target);
        this.evidenceAgeSource = target ->
            new SleeperLiveWaiverRecommendationEvidenceAgeTelemetry(database).inspect(target);
        this.playerLookup = sleeperId -> players.findByExternalId(sleeperId)
            .map(SleeperLiveWaiverLatestGovernedDecisionSummary::display)
            .orElse(null);
    }

    SleeperLiveWaiverLatestGovernedDecisionSummary(
        RevalidationSource revalidationSource,
        EvidenceLineageSource evidenceLineageSource,
        EvidenceAgeSource evidenceAgeSource,
        PlayerLookup playerLookup) {
        this.revalidationSource = Objects.requireNonNull(revalidationSource, "revalidationSource must not be null");
        this.evidenceLineageSource = Objects.requireNonNull(evidenceLineageSource, "evidenceLineageSource must not be null");
        this.evidenceAgeSource = Objects.requireNonNull(evidenceAgeSource, "evidenceAgeSource must not be null");
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup must not be null");
    }

    public SummaryReport summarize(SleeperPersonalizedTargetService.VerifiedTarget target)
        throws SQLException, IOException, InterruptedException {
        validateVerifiedTarget(target);
        var revalidation = revalidationSource.revalidate(target);
        validateRevalidation(target, revalidation);
        var evidenceLineage = evidenceLineageSource.revalidate(target);
        validateEvidenceLineage(target, evidenceLineage);
        validateCrossGateAudit(revalidation, evidenceLineage);
        var evidenceAge = evidenceAgeSource.inspect(target);
        validateEvidenceAge(target, evidenceLineage, evidenceAge);
        validateCrossGateTelemetry(revalidation, evidenceLineage, evidenceAge);

        if (revalidation.state()
            == SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.NO_AUDITED_DECISION) {
            return report(target, revalidation, evidenceLineage, evidenceAge,
                SummaryState.NO_AUDITED_DECISION, null, null);
        }
        if (revalidation.state()
            == SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.NO_TRANSACTION_TO_REVALIDATE) {
            return report(target, revalidation, evidenceLineage, evidenceAge,
                SummaryState.NO_TRANSACTION_TO_ACT_ON, null, null);
        }

        String addId = requireText(revalidation.addSleeperPlayerId(), "addSleeperPlayerId");
        String dropId = requireText(revalidation.dropSleeperPlayerId(), "dropSleeperPlayerId");
        PlayerDisplay add = requirePlayer(addId, "add");
        PlayerDisplay drop = requirePlayer(dropId, "drop");

        boolean latestEvidence = evidenceLineage.state()
            == SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.LATEST_EVIDENCE_LINEAGE_VERIFIED;
        boolean liveActionable = revalidation.state()
            == SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.LIVE_ACTIONABLE_VERIFIED;
        boolean transactionComplete = revalidation.state()
            == SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.AUDITED_TRANSACTION_COMPLETE;
        boolean transactionPending = revalidation.state()
            == SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.AUDITED_TRANSACTION_PENDING;

        SummaryState state;
        if (!latestEvidence) {
            state = SummaryState.STALE_DO_NOT_ACT;
        } else if (transactionComplete) {
            state = SummaryState.TRANSACTION_ALREADY_COMPLETE;
        } else if (transactionPending) {
            state = SummaryState.TRANSACTION_PENDING_DO_NOT_DUPLICATE;
        } else if (!liveActionable) {
            state = SummaryState.STALE_DO_NOT_ACT;
        } else if (refreshWarningTriggered(evidenceAge)) {
            state = SummaryState.CURRENT_REFRESH_RECOMMENDED;
        } else {
            state = SummaryState.CURRENT_AND_ACTIONABLE;
        }
        return report(target, revalidation, evidenceLineage, evidenceAge, state, add, drop);
    }

    private static boolean refreshWarningTriggered(
        SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport evidenceAge) {
        return evidenceAge.latestMarketAgeSeconds() > REFRESH_WARNING_THRESHOLD_SECONDS
            || evidenceAge.latestWaiverAgeSeconds() > REFRESH_WARNING_THRESHOLD_SECONDS;
    }

    private PlayerDisplay requirePlayer(String sleeperId, String role) throws SQLException {
        PlayerDisplay player = playerLookup.find(sleeperId);
        if (player == null) {
            throw new IllegalStateException("BF-630 BLOCKED: exact persisted Butler player is missing for "
                + role + " Sleeper id " + sleeperId);
        }
        if (!sleeperId.equals(player.sleeperPlayerId())) {
            throw new IllegalStateException("BF-630 BLOCKED: exact persisted Butler player identity mismatch for "
                + role + " Sleeper id " + sleeperId);
        }
        return player;
    }

    private static SummaryReport report(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport revalidation,
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport evidenceLineage,
        SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport evidenceAge,
        SummaryState state,
        PlayerDisplay add,
        PlayerDisplay drop) {
        return new SummaryReport(
            POLICY_ID,
            target.butlerLeagueId(),
            target.sleeperUserId(),
            target.sleeperLeagueId(),
            target.leagueName(),
            target.rosterId(),
            target.teamName(),
            revalidation.auditId(),
            revalidation.capturedAtUtc(),
            revalidation.recommendationState(),
            evidenceLineage.auditedMarketSnapshotId(),
            evidenceLineage.auditedWaiverSnapshotId(),
            add,
            drop,
            revalidation.state(),
            evidenceLineage.state(),
            evidenceAge.state(),
            evidenceAge.observedAtUtc(),
            evidenceAge.auditAgeSeconds(),
            evidenceAge.latestMarketObservedAtUtc(),
            evidenceAge.latestMarketAgeSeconds(),
            evidenceAge.latestWaiverObservedAtUtc(),
            evidenceAge.latestWaiverAgeSeconds(),
            state);
    }

    private static void validateVerifiedTarget(SleeperPersonalizedTargetService.VerifiedTarget target) {
        Objects.requireNonNull(target, "target must not be null");
        if (!SleeperPersonalizedTargetService.BF623_POLICY_ID.equals(target.policyId())
            || target.state() != SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED) {
            throw new IllegalStateException("BF-630 BLOCKED: target is not BF-623 live verified");
        }
    }

    private static void validateRevalidation(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport report) {
        Objects.requireNonNull(report, "BF-629 report must not be null");
        if (!SleeperLiveWaiverRecommendationActionabilityRevalidation.POLICY_ID.equals(report.policyId())
            || !target.butlerLeagueId().equals(report.leagueId())
            || !target.sleeperUserId().equals(report.sleeperOwnerId())
            || !target.sleeperLeagueId().equals(report.sleeperLeagueId())
            || target.rosterId() != report.rosterId()) {
            throw new IllegalStateException("BF-630 BLOCKED: BF-629 actionability does not reconcile to BF-623 target");
        }
    }

    private static void validateEvidenceLineage(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport report) {
        Objects.requireNonNull(report, "BF-631 report must not be null");
        if (!SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.POLICY_ID.equals(report.policyId())
            || !target.butlerLeagueId().equals(report.leagueId())
            || !target.sleeperUserId().equals(report.sleeperOwnerId())
            || !target.sleeperLeagueId().equals(report.sleeperLeagueId())
            || target.rosterId() != report.rosterId()) {
            throw new IllegalStateException("BF-632 BLOCKED: BF-631 evidence lineage does not reconcile to BF-623 target");
        }
        if (report.state()
            != SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.NO_AUDITED_DECISION) {
            requireText(report.auditId(), "BF-631 auditId");
            requireText(report.capturedAtUtc(), "BF-631 capturedAtUtc");
            requireText(report.auditedMarketSnapshotId(), "BF-631 auditedMarketSnapshotId");
            requireText(report.auditedWaiverSnapshotId(), "BF-631 auditedWaiverSnapshotId");
        }
    }

    private static void validateEvidenceAge(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport evidenceLineage,
        SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport report) {
        Objects.requireNonNull(report, "BF-633 telemetry report must not be null");
        if (!SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.POLICY_ID.equals(report.policyId())
            || !target.butlerLeagueId().equals(report.leagueId())
            || !target.sleeperUserId().equals(report.sleeperOwnerId())
            || !target.sleeperLeagueId().equals(report.sleeperLeagueId())
            || target.rosterId() != report.rosterId()) {
            throw new IllegalStateException("BF-634 BLOCKED: BF-633 telemetry does not reconcile to BF-623 target");
        }
        if (report.bf631State() != evidenceLineage.state()) {
            throw new IllegalStateException("BF-634 BLOCKED: BF-633 telemetry does not preserve the BF-631 evidence-lineage state");
        }
        requireText(report.observedAtUtc(), "BF-633 observedAtUtc");

        if (report.state() == SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.NO_AUDITED_DECISION) {
            if (report.auditId() != null || report.auditAgeSeconds() != null
                || report.latestMarketObservedAtUtc() != null || report.latestMarketAgeSeconds() != null
                || report.latestWaiverObservedAtUtc() != null || report.latestWaiverAgeSeconds() != null) {
                throw new IllegalStateException("BF-634 BLOCKED: BF-633 no-audit telemetry invented audit/evidence ages");
            }
            return;
        }

        if (report.state() != SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.EVIDENCE_AGE_REPORTED) {
            throw new IllegalStateException("BF-634 BLOCKED: unexpected BF-633 telemetry state");
        }
        requireText(report.auditId(), "BF-633 auditId");
        requireText(report.latestMarketObservedAtUtc(), "BF-633 latestMarketObservedAtUtc");
        requireText(report.latestWaiverObservedAtUtc(), "BF-633 latestWaiverObservedAtUtc");
        requireNonNegative(report.auditAgeSeconds(), "BF-633 auditAgeSeconds");
        requireNonNegative(report.latestMarketAgeSeconds(), "BF-633 latestMarketAgeSeconds");
        requireNonNegative(report.latestWaiverAgeSeconds(), "BF-633 latestWaiverAgeSeconds");
    }

    private static void validateCrossGateAudit(
        SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport actionability,
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport evidenceLineage) {
        boolean actionabilityNoAudit = actionability.state()
            == SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.NO_AUDITED_DECISION;
        boolean evidenceNoAudit = evidenceLineage.state()
            == SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.NO_AUDITED_DECISION;
        if (actionabilityNoAudit != evidenceNoAudit) {
            throw new IllegalStateException("BF-632 BLOCKED: BF-629/BF-631 disagree on whether an audited decision exists");
        }
        if (!Objects.equals(actionability.auditId(), evidenceLineage.auditId())
            || !Objects.equals(actionability.capturedAtUtc(), evidenceLineage.capturedAtUtc())) {
            throw new IllegalStateException("BF-632 BLOCKED: BF-629/BF-631 latest audit identity does not reconcile");
        }
    }

    private static void validateCrossGateTelemetry(
        SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport actionability,
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport evidenceLineage,
        SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport evidenceAge) {
        boolean noAudit = actionability.state()
            == SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState.NO_AUDITED_DECISION;
        boolean telemetryNoAudit = evidenceAge.state()
            == SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState.NO_AUDITED_DECISION;
        if (noAudit != telemetryNoAudit) {
            throw new IllegalStateException("BF-634 BLOCKED: BF-629/BF-631 and BF-633 disagree on whether an audited decision exists");
        }
        if (!Objects.equals(actionability.auditId(), evidenceAge.auditId())
            || !Objects.equals(evidenceLineage.auditId(), evidenceAge.auditId())) {
            throw new IllegalStateException("BF-634 BLOCKED: BF-633 latest audit identity does not reconcile to BF-629/BF-631");
        }
    }

    private static void requireNonNegative(Long value, String field) {
        if (value == null || value < 0) {
            throw new IllegalStateException("BF-634 BLOCKED: " + field + " must be non-negative");
        }
    }

    private static PlayerDisplay display(Player player) {
        return new PlayerDisplay(
            player.getExternalId(),
            player.getDisplayName(),
            player.getPosition(),
            player.getNflTeam());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("BF-630 BLOCKED: " + field + " is blank");
        }
        return value.trim();
    }

    @FunctionalInterface
    interface RevalidationSource {
        SleeperLiveWaiverRecommendationActionabilityRevalidation.RevalidationReport revalidate(
            SleeperPersonalizedTargetService.VerifiedTarget target)
            throws SQLException, IOException, InterruptedException;
    }

    @FunctionalInterface
    interface EvidenceLineageSource {
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport revalidate(
            SleeperPersonalizedTargetService.VerifiedTarget target) throws SQLException;
    }

    @FunctionalInterface
    interface EvidenceAgeSource {
        SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryReport inspect(
            SleeperPersonalizedTargetService.VerifiedTarget target) throws SQLException;
    }

    @FunctionalInterface
    interface PlayerLookup {
        PlayerDisplay find(String sleeperPlayerId) throws SQLException;
    }

    public enum SummaryState {
        NO_AUDITED_DECISION,
        NO_TRANSACTION_TO_ACT_ON,
        TRANSACTION_ALREADY_COMPLETE,
        TRANSACTION_PENDING_DO_NOT_DUPLICATE,
        CURRENT_AND_ACTIONABLE,
        CURRENT_REFRESH_RECOMMENDED,
        STALE_DO_NOT_ACT
    }

    public record PlayerDisplay(
        String sleeperPlayerId,
        String displayName,
        String position,
        String nflTeam) {
        public PlayerDisplay {
            sleeperPlayerId = requireText(sleeperPlayerId, "sleeperPlayerId");
            displayName = requireText(displayName, "displayName");
            position = requireText(position, "position");
            nflTeam = nflTeam == null || nflTeam.isBlank() ? null : nflTeam.trim();
        }
    }

    public record SummaryReport(
        String policyId,
        String leagueId,
        String sleeperOwnerId,
        String sleeperLeagueId,
        String leagueName,
        int rosterId,
        String teamName,
        String auditId,
        String capturedAtUtc,
        String recommendationState,
        String auditedMarketSnapshotId,
        String auditedWaiverSnapshotId,
        PlayerDisplay addPlayer,
        PlayerDisplay dropPlayer,
        SleeperLiveWaiverRecommendationActionabilityRevalidation.ActionabilityState bf629State,
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState bf631State,
        SleeperLiveWaiverRecommendationEvidenceAgeTelemetry.TelemetryState bf633State,
        String telemetryObservedAtUtc,
        Long auditAgeSeconds,
        String latestMarketObservedAtUtc,
        Long latestMarketAgeSeconds,
        String latestWaiverObservedAtUtc,
        Long latestWaiverAgeSeconds,
        SummaryState state) {
        public SummaryReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-630/BF-632/BF-634/BF-635/BF-638 policyId");
            Objects.requireNonNull(bf629State, "bf629State must not be null");
            Objects.requireNonNull(bf631State, "bf631State must not be null");
            Objects.requireNonNull(bf633State, "bf633State must not be null");
            Objects.requireNonNull(state, "state must not be null");
        }
    }
}
