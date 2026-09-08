package io.butler.bet.sleeper;

import io.butler.bet.data.Database;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** BF-613 read-only authorization gate for candidate-vs-roster comparison methodology. */
public final class SleeperLiveWaiverCandidateRosterComparisonReadinessAudit {
    public static final String POLICY_ID =
        "sleeper-live-waiver-candidate-roster-comparison-readiness-v1-bf609-bf611-same-market-frame-read-only";

    private static final List<String> SUPPORTED_POSITIONS = List.of("QB", "RB", "WR", "TE");

    private final CandidateSource candidateSource;
    private final RosterSource rosterSource;

    public SleeperLiveWaiverCandidateRosterComparisonReadinessAudit(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.candidateSource = leagueId -> candidateFrame(
            new SleeperLiveWaiverPregameEvidenceReadinessAudit(database).audit(leagueId));
        this.rosterSource = (leagueId, ownerId) -> rosterFrame(
            new SleeperLiveWaiverTargetRosterProductionComparabilityAudit(database).audit(leagueId, ownerId));
    }

    SleeperLiveWaiverCandidateRosterComparisonReadinessAudit(
        CandidateSource candidateSource,
        RosterSource rosterSource) {
        this.candidateSource = Objects.requireNonNull(candidateSource, "candidateSource must not be null");
        this.rosterSource = Objects.requireNonNull(rosterSource, "rosterSource must not be null");
    }

    public AuditReport audit(String leagueId, String sleeperOwnerId)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");
        CandidateFrame candidates = candidateSource.audit(normalizedLeagueId);
        RosterFrame roster = rosterSource.audit(normalizedLeagueId, normalizedOwnerId);
        return assess(normalizedLeagueId, normalizedOwnerId, candidates, roster);
    }

    static AuditReport assess(
        String leagueId,
        String sleeperOwnerId,
        CandidateFrame candidates,
        RosterFrame roster) {
        Objects.requireNonNull(candidates, "candidate frame must not be null");
        Objects.requireNonNull(roster, "roster frame must not be null");

        if (!leagueId.equals(candidates.leagueId()) || !leagueId.equals(roster.leagueId())) {
            throw new IllegalStateException("BF-613 BLOCKED: BF-609/BF-611 league lineage differs from requested league");
        }
        if (!sleeperOwnerId.equals(roster.sleeperOwnerId())) {
            throw new IllegalStateException("BF-613 BLOCKED: BF-611 owner lineage differs from requested owner");
        }
        if (!candidates.marketSnapshotId().equals(roster.marketSnapshotId())) {
            throw new IllegalStateException("BF-613 BLOCKED: BF-609 and BF-611 reference different BF-603 market snapshots");
        }
        if (roster.providerSeason() != 2026 || !"in_season".equals(roster.providerStatus())) {
            throw new IllegalStateException("BF-613 BLOCKED: target-roster frame is not in-season 2026");
        }
        if (candidates.candidateCount() <= 0 || candidates.entries().size() != candidates.candidateCount()) {
            throw new IllegalStateException("BF-613 BLOCKED: BF-609 candidate frame is empty or unreconciled");
        }
        if (candidates.currentTeamUnknown() + candidates.depthEvidenceMissing()
            + candidates.reviewableWithPriorProduction() + candidates.reviewableWithoutPriorProduction()
            != candidates.candidateCount()) {
            throw new IllegalStateException("BF-613 BLOCKED: BF-609 candidate strata do not reconcile");
        }
        int reviewable = candidates.reviewableWithPriorProduction()
            + candidates.reviewableWithoutPriorProduction();
        if (reviewable <= 0) {
            throw new IllegalStateException("BF-613 BLOCKED: BF-609 has no evidence-reviewable candidates");
        }
        if (roster.targetPlayerCount() <= 0 || roster.entries().size() != roster.targetPlayerCount()) {
            throw new IllegalStateException("BF-613 BLOCKED: BF-611 target roster is empty or unreconciled");
        }
        if (roster.priorProductionPresent() + roster.priorProductionMissing() != roster.targetPlayerCount()) {
            throw new IllegalStateException("BF-613 BLOCKED: BF-611 target production partition does not reconcile");
        }
        if (roster.starterCount() + roster.benchCount() + roster.reserveCount() + roster.taxiCount()
            != roster.targetPlayerCount()) {
            throw new IllegalStateException("BF-613 BLOCKED: BF-611 target roster slots do not reconcile");
        }

        Map<String, MutableCandidatePosition> candidateAccumulator = newPositionCandidateMap();
        int observedReviewable = 0;
        for (CandidateEntry candidate : candidates.entries()) {
            Objects.requireNonNull(candidate, "BF-613 candidate entry must not be null");
            if (!candidate.reviewable()) continue;
            String position = supportedPosition(candidate.position(), "candidate " + candidate.sleeperPlayerId());
            MutableCandidatePosition bucket = candidateAccumulator.get(position);
            if (candidate.priorProductionPresent()) bucket.withPrior++;
            else bucket.withoutPrior++;
            observedReviewable++;
        }
        if (observedReviewable != reviewable) {
            throw new IllegalStateException("BF-613 BLOCKED: reviewable candidate entries do not reconcile to BF-609 strata");
        }

        Map<String, MutableRosterPosition> rosterAccumulator = newPositionRosterMap();
        List<MissingRosterProduction> missingRosterProduction = new ArrayList<>();
        int observedPresent = 0;
        int observedMissing = 0;
        for (RosterEntry target : roster.entries()) {
            Objects.requireNonNull(target, "BF-613 roster entry must not be null");
            if (!"EXACT_CANONICAL".equals(target.mappingState())) {
                throw new IllegalStateException("BF-613 BLOCKED: non-exact target-roster identity " + target.sleeperPlayerId());
            }
            String position = supportedPosition(target.position(), "target-roster player " + target.sleeperPlayerId());
            MutableRosterPosition bucket = rosterAccumulator.get(position);
            bucket.total++;
            switch (target.rosterSlot()) {
                case "STARTER" -> bucket.starter++;
                case "BENCH" -> bucket.bench++;
                case "RESERVE" -> bucket.reserve++;
                case "TAXI" -> bucket.taxi++;
                default -> throw new IllegalStateException("BF-613 BLOCKED: unsupported target roster slot "
                    + target.rosterSlot() + " for " + target.sleeperPlayerId());
            }
            if (target.priorProductionPresent()) {
                bucket.withPrior++;
                observedPresent++;
            } else {
                bucket.withoutPrior++;
                observedMissing++;
                missingRosterProduction.add(new MissingRosterProduction(
                    target.sleeperPlayerId(), target.displayName(), position, target.rosterSlot()));
            }
        }
        if (observedPresent != roster.priorProductionPresent()
            || observedMissing != roster.priorProductionMissing()) {
            throw new IllegalStateException("BF-613 BLOCKED: BF-611 per-player production states do not reconcile");
        }

        Map<String, CandidatePositionCoverage> candidatePositionCoverage = new LinkedHashMap<>();
        int candidatePositionTotal = 0;
        for (String position : SUPPORTED_POSITIONS) {
            MutableCandidatePosition bucket = candidateAccumulator.get(position);
            CandidatePositionCoverage frozen = new CandidatePositionCoverage(bucket.withPrior, bucket.withoutPrior);
            candidatePositionCoverage.put(position, frozen);
            candidatePositionTotal += frozen.total();
        }
        if (candidatePositionTotal != reviewable) {
            throw new IllegalStateException("BF-613 BLOCKED: reviewable candidate position coverage does not reconcile");
        }

        Map<String, RosterPositionCoverage> rosterPositionCoverage = new LinkedHashMap<>();
        int rosterPositionTotal = 0;
        for (String position : SUPPORTED_POSITIONS) {
            MutableRosterPosition bucket = rosterAccumulator.get(position);
            RosterPositionCoverage frozen = new RosterPositionCoverage(
                bucket.total, bucket.withPrior, bucket.withoutPrior,
                bucket.starter, bucket.bench, bucket.reserve, bucket.taxi);
            rosterPositionCoverage.put(position, frozen);
            rosterPositionTotal += frozen.total();
        }
        if (rosterPositionTotal != roster.targetPlayerCount()) {
            throw new IllegalStateException("BF-613 BLOCKED: target-roster position coverage does not reconcile");
        }

        return new AuditReport(
            POLICY_ID,
            leagueId,
            candidates.marketSnapshotId(),
            roster.waiverSnapshotId(),
            roster.sleeperLeagueId(),
            roster.providerSeason(),
            roster.providerStatus(),
            roster.providerLeg(),
            sleeperOwnerId,
            roster.rosterId(),
            candidates.candidateCount(),
            candidates.currentTeamUnknown(),
            candidates.depthEvidenceMissing(),
            candidates.reviewableWithPriorProduction(),
            candidates.reviewableWithoutPriorProduction(),
            reviewable,
            roster.targetPlayerCount(),
            roster.starterCount(),
            roster.benchCount(),
            roster.reserveCount(),
            roster.taxiCount(),
            roster.priorProductionPresent(),
            roster.priorProductionMissing(),
            Collections.unmodifiableMap(candidatePositionCoverage),
            Collections.unmodifiableMap(rosterPositionCoverage),
            List.copyOf(missingRosterProduction),
            AuthorizationState.READY_FOR_COMPARISON_METHODOLOGY);
    }

    private static CandidateFrame candidateFrame(
        SleeperLiveWaiverPregameEvidenceReadinessAudit.ReadinessReport report) {
        Objects.requireNonNull(report, "BF-609 report must not be null");
        if (!SleeperLiveWaiverPregameEvidenceReadinessAudit.POLICY_ID.equals(report.policyId())) {
            throw new IllegalStateException("BF-613 BLOCKED: unexpected BF-609 policy");
        }
        int teamUnknown = report.summaries()
            .get(SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.CURRENT_TEAM_UNKNOWN).total();
        int depthMissing = report.summaries()
            .get(SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.DEPTH_EVIDENCE_MISSING).total();
        int withPrior = report.summaries()
            .get(SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION).total();
        int withoutPrior = report.summaries()
            .get(SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITHOUT_PRIOR_PRODUCTION).total();
        List<CandidateEntry> entries = report.candidates().stream().map(value -> {
            var stratum = value.primaryStratum();
            boolean reviewable = stratum == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION
                || stratum == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITHOUT_PRIOR_PRODUCTION;
            boolean priorPresent = stratum == SleeperLiveWaiverPregameEvidenceReadinessAudit.PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION;
            return new CandidateEntry(
                value.dossier().market().sleeperPlayerId(),
                value.dossier().market().displayName(),
                value.dossier().market().position(),
                reviewable,
                priorPresent);
        }).toList();
        return new CandidateFrame(
            report.leagueId(), report.marketSnapshotId(), report.candidateCount(),
            teamUnknown, depthMissing, withPrior, withoutPrior, entries);
    }

    private static RosterFrame rosterFrame(
        SleeperLiveWaiverTargetRosterProductionComparabilityAudit.AuditReport report) {
        Objects.requireNonNull(report, "BF-611 report must not be null");
        if (!SleeperLiveWaiverTargetRosterProductionComparabilityAudit.POLICY_ID.equals(report.policyId())) {
            throw new IllegalStateException("BF-613 BLOCKED: unexpected BF-611 policy");
        }
        List<RosterEntry> entries = report.players().stream().map(value -> new RosterEntry(
            value.target().sleeperPlayerId(),
            value.target().displayName(),
            value.target().position(),
            value.target().rosterSlot(),
            value.target().mappingState(),
            value.state() == SleeperLiveWaiverTargetRosterProductionComparabilityAudit.CoverageState.PRIOR_PRODUCTION_PRESENT)).toList();
        return new RosterFrame(
            report.leagueId(), report.marketSnapshotId(), report.waiverSnapshotId(),
            report.sleeperLeagueId(), report.providerSeason(), report.providerStatus(), report.providerLeg(),
            report.sleeperOwnerId(), report.rosterId(), report.targetPlayerCount(),
            report.starterCount(), report.benchCount(), report.reserveCount(), report.taxiCount(),
            report.priorProductionPresent(), report.priorProductionMissing(), entries);
    }

    private static String supportedPosition(String value, String subject) {
        String normalized = requireText(value, subject + " position");
        if (!SUPPORTED_POSITIONS.contains(normalized)) {
            throw new IllegalStateException("BF-613 BLOCKED: unsupported position " + normalized + " for " + subject);
        }
        return normalized;
    }

    private static Map<String, MutableCandidatePosition> newPositionCandidateMap() {
        Map<String, MutableCandidatePosition> result = new LinkedHashMap<>();
        for (String position : SUPPORTED_POSITIONS) result.put(position, new MutableCandidatePosition());
        return result;
    }

    private static Map<String, MutableRosterPosition> newPositionRosterMap() {
        Map<String, MutableRosterPosition> result = new LinkedHashMap<>();
        for (String position : SUPPORTED_POSITIONS) result.put(position, new MutableRosterPosition());
        return result;
    }

    @FunctionalInterface
    interface CandidateSource {
        CandidateFrame audit(String leagueId) throws SQLException;
    }

    @FunctionalInterface
    interface RosterSource {
        RosterFrame audit(String leagueId, String ownerId) throws SQLException, IOException, InterruptedException;
    }

    record CandidateFrame(
        String leagueId,
        String marketSnapshotId,
        int candidateCount,
        int currentTeamUnknown,
        int depthEvidenceMissing,
        int reviewableWithPriorProduction,
        int reviewableWithoutPriorProduction,
        List<CandidateEntry> entries) {
        CandidateFrame {
            entries = List.copyOf(Objects.requireNonNull(entries));
        }
    }

    record CandidateEntry(
        String sleeperPlayerId,
        String displayName,
        String position,
        boolean reviewable,
        boolean priorProductionPresent) {}

    record RosterFrame(
        String leagueId,
        String marketSnapshotId,
        String waiverSnapshotId,
        String sleeperLeagueId,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        String sleeperOwnerId,
        int rosterId,
        int targetPlayerCount,
        int starterCount,
        int benchCount,
        int reserveCount,
        int taxiCount,
        int priorProductionPresent,
        int priorProductionMissing,
        List<RosterEntry> entries) {
        RosterFrame {
            entries = List.copyOf(Objects.requireNonNull(entries));
        }
    }

    record RosterEntry(
        String sleeperPlayerId,
        String displayName,
        String position,
        String rosterSlot,
        String mappingState,
        boolean priorProductionPresent) {}

    public record CandidatePositionCoverage(int withPriorProduction, int withoutPriorProduction) {
        public CandidatePositionCoverage {
            if (withPriorProduction < 0 || withoutPriorProduction < 0) {
                throw new IllegalArgumentException("candidate position counts must be nonnegative");
            }
        }
        public int total() { return withPriorProduction + withoutPriorProduction; }
    }

    public record RosterPositionCoverage(
        int total,
        int withPriorProduction,
        int withoutPriorProduction,
        int starter,
        int bench,
        int reserve,
        int taxi) {
        public RosterPositionCoverage {
            if (total < 0 || withPriorProduction < 0 || withoutPriorProduction < 0
                || starter < 0 || bench < 0 || reserve < 0 || taxi < 0) {
                throw new IllegalArgumentException("roster position counts must be nonnegative");
            }
            if (withPriorProduction + withoutPriorProduction != total) {
                throw new IllegalArgumentException("roster production counts must reconcile by position");
            }
            if (starter + bench + reserve + taxi != total) {
                throw new IllegalArgumentException("roster slot counts must reconcile by position");
            }
        }
    }

    public record MissingRosterProduction(
        String sleeperPlayerId,
        String displayName,
        String position,
        String rosterSlot) {}

    public enum AuthorizationState {
        READY_FOR_COMPARISON_METHODOLOGY
    }

    public record AuditReport(
        String policyId,
        String leagueId,
        String marketSnapshotId,
        String waiverSnapshotId,
        String sleeperLeagueId,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        String sleeperOwnerId,
        int rosterId,
        int candidateCount,
        int currentTeamUnknownCandidates,
        int depthEvidenceMissingCandidates,
        int reviewableWithPriorProduction,
        int reviewableWithoutPriorProduction,
        int reviewableCandidateCount,
        int targetPlayerCount,
        int starterCount,
        int benchCount,
        int reserveCount,
        int taxiCount,
        int targetPriorProductionPresent,
        int targetPriorProductionMissing,
        Map<String, CandidatePositionCoverage> candidatePositionCoverage,
        Map<String, RosterPositionCoverage> rosterPositionCoverage,
        List<MissingRosterProduction> missingRosterProduction,
        AuthorizationState state) {
        public AuditReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-613 policyId");
            candidatePositionCoverage = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(candidatePositionCoverage, "candidatePositionCoverage must not be null")));
            rosterPositionCoverage = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(rosterPositionCoverage, "rosterPositionCoverage must not be null")));
            missingRosterProduction = List.copyOf(Objects.requireNonNull(missingRosterProduction, "missingRosterProduction must not be null"));
            Objects.requireNonNull(state, "state must not be null");
            if (reviewableWithPriorProduction + reviewableWithoutPriorProduction != reviewableCandidateCount) {
                throw new IllegalArgumentException("reviewable candidate counts must reconcile");
            }
            if (currentTeamUnknownCandidates + depthEvidenceMissingCandidates + reviewableCandidateCount != candidateCount) {
                throw new IllegalArgumentException("candidate readiness counts must reconcile");
            }
            if (starterCount + benchCount + reserveCount + taxiCount != targetPlayerCount) {
                throw new IllegalArgumentException("target roster slot counts must reconcile");
            }
            if (targetPriorProductionPresent + targetPriorProductionMissing != targetPlayerCount) {
                throw new IllegalArgumentException("target production counts must reconcile");
            }
            if (missingRosterProduction.size() != targetPriorProductionMissing) {
                throw new IllegalArgumentException("missing target production identities must reconcile");
            }
        }
    }

    private static final class MutableCandidatePosition {
        int withPrior;
        int withoutPrior;
    }

    private static final class MutableRosterPosition {
        int total;
        int withPrior;
        int withoutPrior;
        int starter;
        int bench;
        int reserve;
        int taxi;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
