package io.butler.bet.sleeper;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** BF-633 read-only wall-clock age telemetry for the persisted evidence behind the latest governed audit. */
public final class SleeperLiveWaiverRecommendationEvidenceAgeTelemetry {
    public static final String POLICY_ID =
        "sleeper-live-waiver-recommendation-evidence-age-telemetry-v1-bf623-bf631-read-only-no-threshold";

    private final LineageSource lineageSource;
    private final Clock clock;

    public SleeperLiveWaiverRecommendationEvidenceAgeTelemetry(Database database) {
        this(
            target -> new SleeperLiveWaiverRecommendationEvidenceLineageRevalidation(database).revalidate(target),
            Clock.systemUTC());
    }

    SleeperLiveWaiverRecommendationEvidenceAgeTelemetry(LineageSource lineageSource, Clock clock) {
        this.lineageSource = Objects.requireNonNull(lineageSource, "lineageSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public TelemetryReport inspect(SleeperPersonalizedTargetService.VerifiedTarget target) throws SQLException {
        validateVerifiedTarget(target);
        var lineage = lineageSource.revalidate(target);
        validateLineage(target, lineage);

        Instant observedAt = clock.instant();
        if (lineage.state()
            == SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState.NO_AUDITED_DECISION) {
            return new TelemetryReport(
                POLICY_ID,
                target.butlerLeagueId(),
                target.sleeperUserId(),
                target.sleeperLeagueId(),
                target.rosterId(),
                observedAt.toString(),
                null,
                null,
                null,
                null,
                null,
                null,
                lineage.state(),
                TelemetryState.NO_AUDITED_DECISION);
        }

        String auditId = requireText(lineage.auditId(), "auditId");
        Instant auditCapturedAt = parseRequired(lineage.capturedAtUtc(), "audit capturedAtUtc");
        Instant marketObservedAt = parseRequired(lineage.latestMarketObservedAtUtc(), "latest BF-603 observedAtUtc");
        Instant waiverObservedAt = parseRequired(lineage.latestWaiverObservedAtUtc(), "latest BF-602 observedAtUtc");

        long auditAgeSeconds = ageSeconds(observedAt, auditCapturedAt, "audit capturedAtUtc");
        long marketAgeSeconds = ageSeconds(observedAt, marketObservedAt, "latest BF-603 observedAtUtc");
        long waiverAgeSeconds = ageSeconds(observedAt, waiverObservedAt, "latest BF-602 observedAtUtc");

        return new TelemetryReport(
            POLICY_ID,
            target.butlerLeagueId(),
            target.sleeperUserId(),
            target.sleeperLeagueId(),
            target.rosterId(),
            observedAt.toString(),
            auditId,
            auditAgeSeconds,
            marketObservedAt.toString(),
            marketAgeSeconds,
            waiverObservedAt.toString(),
            waiverAgeSeconds,
            lineage.state(),
            TelemetryState.EVIDENCE_AGE_REPORTED);
    }

    private static void validateVerifiedTarget(SleeperPersonalizedTargetService.VerifiedTarget target) {
        Objects.requireNonNull(target, "target must not be null");
        if (!SleeperPersonalizedTargetService.BF623_POLICY_ID.equals(target.policyId())
            || target.state() != SleeperPersonalizedTargetService.VerificationState.BOUND_TARGET_LIVE_VERIFIED) {
            throw new IllegalStateException("BF-633 BLOCKED: target is not BF-623 live verified");
        }
    }

    private static void validateLineage(
        SleeperPersonalizedTargetService.VerifiedTarget target,
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport lineage) {
        Objects.requireNonNull(lineage, "BF-631 lineage report must not be null");
        if (!SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.POLICY_ID.equals(lineage.policyId())
            || !target.butlerLeagueId().equals(lineage.leagueId())
            || !target.sleeperUserId().equals(lineage.sleeperOwnerId())
            || !target.sleeperLeagueId().equals(lineage.sleeperLeagueId())
            || target.rosterId() != lineage.rosterId()) {
            throw new IllegalStateException("BF-633 BLOCKED: BF-631 evidence lineage does not reconcile to BF-623 target");
        }
    }

    private static Instant parseRequired(String value, String field) {
        String normalized = requireText(value, field);
        try {
            return Instant.parse(normalized);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("BF-633 BLOCKED: malformed " + field + ": " + normalized, e);
        }
    }

    private static long ageSeconds(Instant observedAt, Instant timestamp, String field) {
        if (timestamp.isAfter(observedAt)) {
            throw new IllegalStateException("BF-633 BLOCKED: " + field + " is in the future relative to observation clock");
        }
        return Duration.between(timestamp, observedAt).getSeconds();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("BF-633 BLOCKED: " + field + " is blank");
        }
        return value.trim();
    }

    @FunctionalInterface
    interface LineageSource {
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.RevalidationReport revalidate(
            SleeperPersonalizedTargetService.VerifiedTarget target) throws SQLException;
    }

    public enum TelemetryState {
        NO_AUDITED_DECISION,
        EVIDENCE_AGE_REPORTED
    }

    public record TelemetryReport(
        String policyId,
        String leagueId,
        String sleeperOwnerId,
        String sleeperLeagueId,
        int rosterId,
        String observedAtUtc,
        String auditId,
        Long auditAgeSeconds,
        String latestMarketObservedAtUtc,
        Long latestMarketAgeSeconds,
        String latestWaiverObservedAtUtc,
        Long latestWaiverAgeSeconds,
        SleeperLiveWaiverRecommendationEvidenceLineageRevalidation.EvidenceLineageState bf631State,
        TelemetryState state) {
        public TelemetryReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-633 policyId");
            Objects.requireNonNull(bf631State, "bf631State must not be null");
            Objects.requireNonNull(state, "state must not be null");
        }
    }
}
