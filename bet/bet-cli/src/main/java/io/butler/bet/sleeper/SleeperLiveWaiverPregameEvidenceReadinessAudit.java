package io.butler.bet.sleeper;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** BF-609 read-only classification of BF-608 pregame evidence availability. */
public final class SleeperLiveWaiverPregameEvidenceReadinessAudit {
    public static final String POLICY_ID =
        "sleeper-live-waiver-pregame-readiness-v1-bf608-evidence-strata-read-only";

    private final Database database;

    public SleeperLiveWaiverPregameEvidenceReadinessAudit(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public ReadinessReport audit(String leagueId) throws SQLException {
        return classify(new SleeperLiveWaiverPregameEvidenceDossier(database).audit(leagueId));
    }

    public static ReadinessReport classify(SleeperLiveWaiverPregameEvidenceDossier.DossierReport dossier) {
        Objects.requireNonNull(dossier, "dossier must not be null");
        if (!SleeperLiveWaiverPregameEvidenceDossier.POLICY_ID.equals(dossier.policyId())) {
            throw new IllegalStateException("BF-609 BLOCKED: unexpected BF-608 dossier policy");
        }
        if (dossier.candidateCount() <= 0) {
            throw new IllegalStateException("BF-609 BLOCKED: BF-608 dossier is empty");
        }

        EnumMap<PrimaryStratum, MutableSummary> accumulator = new EnumMap<>(PrimaryStratum.class);
        for (PrimaryStratum stratum : PrimaryStratum.values()) {
            accumulator.put(stratum, new MutableSummary());
        }

        List<CandidateReadiness> candidates = new ArrayList<>();
        for (var candidate : dossier.candidates()) {
            validateEvidenceStates(candidate);
            PrimaryStratum stratum = classifyCandidate(candidate);
            accumulator.get(stratum).observe(candidate);
            candidates.add(new CandidateReadiness(candidate, stratum));
        }

        candidates.sort(Comparator
            .comparing((CandidateReadiness value) -> value.primaryStratum().ordinal())
            .thenComparing(value -> value.dossier().market().sleeperPlayerId()));

        EnumMap<PrimaryStratum, StratumSummary> summaries = new EnumMap<>(PrimaryStratum.class);
        int reconciled = 0;
        for (PrimaryStratum stratum : PrimaryStratum.values()) {
            StratumSummary summary = accumulator.get(stratum).freeze();
            summaries.put(stratum, summary);
            reconciled += summary.total();
        }
        if (reconciled != dossier.candidateCount()) {
            throw new IllegalStateException("BF-609 BLOCKED: readiness strata do not reconcile to BF-608 candidate count");
        }

        return new ReadinessReport(
            POLICY_ID,
            dossier.policyId(),
            dossier.leagueId(),
            dossier.marketSnapshotId(),
            dossier.marketObservedAtUtc(),
            dossier.availabilitySnapshotId(),
            dossier.availabilityObservedAtUtc().toString(),
            dossier.currentWeekSnapshotId(),
            dossier.currentWeekObservedAtUtc().toString(),
            dossier.currentWeekObservationState(),
            dossier.stateSeason(),
            dossier.stateWeek(),
            dossier.stateSeasonType(),
            dossier.candidateCount(),
            Collections.unmodifiableMap(new EnumMap<>(summaries)),
            List.copyOf(candidates));
    }

    private static PrimaryStratum classifyCandidate(
        SleeperLiveWaiverPregameEvidenceDossier.CandidateDossier candidate) {
        if ("CURRENT_TEAM_UNKNOWN".equals(candidate.teamEvidenceState())) {
            return PrimaryStratum.CURRENT_TEAM_UNKNOWN;
        }
        if ("DEPTH_EVIDENCE_MISSING".equals(candidate.depthEvidenceState())) {
            return PrimaryStratum.DEPTH_EVIDENCE_MISSING;
        }
        if ("PRIOR_SEASON_PRODUCTION_PRESENT".equals(candidate.priorSeasonProductionState())) {
            return PrimaryStratum.REVIEWABLE_WITH_PRIOR_PRODUCTION;
        }
        if ("PRIOR_SEASON_PRODUCTION_MISSING".equals(candidate.priorSeasonProductionState())) {
            return PrimaryStratum.REVIEWABLE_WITHOUT_PRIOR_PRODUCTION;
        }
        throw new IllegalStateException("BF-609 BLOCKED: unsupported prior-production evidence state for "
            + candidate.market().sleeperPlayerId());
    }

    private static void validateEvidenceStates(
        SleeperLiveWaiverPregameEvidenceDossier.CandidateDossier candidate) {
        String id = candidate.market().sleeperPlayerId();
        if (!"PROVIDER_STATUS_KNOWN".equals(candidate.providerStatusEvidenceState())) {
            throw new IllegalStateException("BF-609 BLOCKED: provider status is unknown for candidate " + id);
        }
        if (!"CURRENT_TEAM_KNOWN".equals(candidate.teamEvidenceState())
            && !"CURRENT_TEAM_UNKNOWN".equals(candidate.teamEvidenceState())) {
            throw new IllegalStateException("BF-609 BLOCKED: unsupported team evidence state for candidate " + id);
        }
        if (!"DEPTH_EVIDENCE_PRESENT".equals(candidate.depthEvidenceState())
            && !"DEPTH_EVIDENCE_MISSING".equals(candidate.depthEvidenceState())) {
            throw new IllegalStateException("BF-609 BLOCKED: unsupported depth evidence state for candidate " + id);
        }
        if (!"PRIOR_SEASON_PRODUCTION_PRESENT".equals(candidate.priorSeasonProductionState())
            && !"PRIOR_SEASON_PRODUCTION_MISSING".equals(candidate.priorSeasonProductionState())) {
            throw new IllegalStateException("BF-609 BLOCKED: unsupported prior-production evidence state for candidate " + id);
        }
        if (!"INJURY_FLAG_PRESENT".equals(candidate.injuryEvidenceState())
            && !"INJURY_FLAG_ABSENT_OR_UNKNOWN".equals(candidate.injuryEvidenceState())) {
            throw new IllegalStateException("BF-609 BLOCKED: unsupported injury evidence state for candidate " + id);
        }
        if (!"CURRENT_WEEK_OBSERVED".equals(candidate.currentWeekEvidenceState())
            && !"CURRENT_WEEK_UNOBSERVED".equals(candidate.currentWeekEvidenceState())) {
            throw new IllegalStateException("BF-609 BLOCKED: unsupported current-week evidence state for candidate " + id);
        }
        String frame = candidate.market().frameMembership();
        if (!"ADD_ONLY".equals(frame) && !"DROP_ONLY".equals(frame) && !"BOTH".equals(frame)) {
            throw new IllegalStateException("BF-609 BLOCKED: non-market-active BF-603 frame membership for candidate " + id);
        }
    }

    public enum PrimaryStratum {
        CURRENT_TEAM_UNKNOWN,
        DEPTH_EVIDENCE_MISSING,
        REVIEWABLE_WITH_PRIOR_PRODUCTION,
        REVIEWABLE_WITHOUT_PRIOR_PRODUCTION
    }

    public record CandidateReadiness(
        SleeperLiveWaiverPregameEvidenceDossier.CandidateDossier dossier,
        PrimaryStratum primaryStratum) {
        public CandidateReadiness {
            Objects.requireNonNull(dossier, "dossier must not be null");
            Objects.requireNonNull(primaryStratum, "primaryStratum must not be null");
        }
    }

    public record StratumSummary(
        int total,
        int injuryFlagPresent,
        int currentWeekObserved,
        int addOnly,
        int dropOnly,
        int both) {
        public StratumSummary {
            if (total < 0 || injuryFlagPresent < 0 || currentWeekObserved < 0
                || addOnly < 0 || dropOnly < 0 || both < 0) {
                throw new IllegalArgumentException("BF-609 summary counts must be nonnegative");
            }
            if (injuryFlagPresent > total || currentWeekObserved > total) {
                throw new IllegalArgumentException("BF-609 evidence counts cannot exceed stratum total");
            }
            if (addOnly + dropOnly + both != total) {
                throw new IllegalArgumentException("BF-609 market-frame counts must reconcile to stratum total");
            }
        }
    }

    public record ReadinessReport(
        String policyId,
        String dossierPolicyId,
        String leagueId,
        String marketSnapshotId,
        String marketObservedAtUtc,
        String availabilitySnapshotId,
        String availabilityObservedAtUtc,
        String currentWeekSnapshotId,
        String currentWeekObservedAtUtc,
        String currentWeekObservationState,
        int stateSeason,
        int stateWeek,
        String stateSeasonType,
        int candidateCount,
        Map<PrimaryStratum, StratumSummary> summaries,
        List<CandidateReadiness> candidates) {
        public ReadinessReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-609 policyId");
            if (!SleeperLiveWaiverPregameEvidenceDossier.POLICY_ID.equals(dossierPolicyId)) {
                throw new IllegalArgumentException("unexpected BF-608 dossier policyId");
            }
            summaries = Collections.unmodifiableMap(new EnumMap<>(Objects.requireNonNull(summaries, "summaries must not be null")));
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
            if (candidateCount != candidates.size()) throw new IllegalArgumentException("candidate count must reconcile");
            int total = 0;
            for (PrimaryStratum stratum : PrimaryStratum.values()) {
                StratumSummary summary = summaries.get(stratum);
                if (summary == null) throw new IllegalArgumentException("missing BF-609 summary for " + stratum);
                total += summary.total();
            }
            if (total != candidateCount) throw new IllegalArgumentException("stratum totals must reconcile");
        }
    }

    private static final class MutableSummary {
        private int total;
        private int injuryFlagPresent;
        private int currentWeekObserved;
        private int addOnly;
        private int dropOnly;
        private int both;

        private void observe(SleeperLiveWaiverPregameEvidenceDossier.CandidateDossier candidate) {
            total++;
            if ("INJURY_FLAG_PRESENT".equals(candidate.injuryEvidenceState())) injuryFlagPresent++;
            if ("CURRENT_WEEK_OBSERVED".equals(candidate.currentWeekEvidenceState())) currentWeekObserved++;
            switch (candidate.market().frameMembership()) {
                case "ADD_ONLY" -> addOnly++;
                case "DROP_ONLY" -> dropOnly++;
                case "BOTH" -> both++;
                default -> throw new IllegalStateException("unexpected market frame");
            }
        }

        private StratumSummary freeze() {
            return new StratumSummary(total, injuryFlagPresent, currentWeekObserved, addOnly, dropOnly, both);
        }
    }
}
