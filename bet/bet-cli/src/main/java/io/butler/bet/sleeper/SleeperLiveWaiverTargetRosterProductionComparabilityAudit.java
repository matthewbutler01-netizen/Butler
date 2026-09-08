package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.domain.PlayerSeasonProduction;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** BF-611 read-only governed 2025 production comparability for the exact BF-610 target roster. */
public final class SleeperLiveWaiverTargetRosterProductionComparabilityAudit {
    public static final String POLICY_ID =
        "sleeper-live-waiver-target-roster-production-comparability-v1-bf610-exact-2025-read-only";
    public static final int PRODUCTION_SEASON = 2025;

    private final Database database;
    private final ContextSource contextSource;

    public SleeperLiveWaiverTargetRosterProductionComparabilityAudit(Database database) {
        this(database, (leagueId, ownerId) ->
            new SleeperLiveWaiverTargetRosterContextAudit(database).audit(leagueId, ownerId));
    }

    SleeperLiveWaiverTargetRosterProductionComparabilityAudit(
        Database database,
        ContextSource contextSource) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.contextSource = Objects.requireNonNull(contextSource, "contextSource must not be null");
    }

    public AuditReport audit(String leagueId, String sleeperOwnerId)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");

        SleeperLiveWaiverTargetRosterContextAudit.AuditReport context =
            contextSource.audit(normalizedLeagueId, normalizedOwnerId);
        validateContext(context, normalizedLeagueId, normalizedOwnerId);

        PlayerSeasonProductionRepository productionRepository =
            new PlayerSeasonProductionRepository(database);
        List<TargetPlayerCoverage> players = new ArrayList<>();
        Map<String, MutableSourceCoverage> sourceAccumulator = new LinkedHashMap<>();
        int present = 0;
        int missing = 0;

        for (var target : context.targetPlayers()) {
            String butlerPlayerId = requireText(target.butlerPlayerId(), "BF-610 target Butler player id");
            List<ProductionObservation> observations = latestProductionPerSource(
                productionRepository.findByPlayerId(butlerPlayerId), PRODUCTION_SEASON);
            CoverageState state;
            if (observations.isEmpty()) {
                state = CoverageState.PRIOR_PRODUCTION_MISSING;
                missing++;
            } else {
                state = CoverageState.PRIOR_PRODUCTION_PRESENT;
                present++;
                for (ProductionObservation observation : observations) {
                    sourceAccumulator
                        .computeIfAbsent(observation.source(), ignored -> new MutableSourceCoverage())
                        .observe(observation.asOfDate());
                }
            }
            players.add(new TargetPlayerCoverage(target, state, observations));
        }

        if (present + missing != context.targetPlayerCount()) {
            throw new IllegalStateException("BF-611 BLOCKED: target-roster production coverage does not reconcile");
        }

        Map<String, SourceCoverage> sourceCoverage = new LinkedHashMap<>();
        sourceAccumulator.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
            sourceCoverage.put(entry.getKey(), entry.getValue().freeze()));

        return new AuditReport(
            POLICY_ID,
            context.policyId(),
            normalizedLeagueId,
            context.marketSnapshotId(),
            context.waiverSnapshotId(),
            context.sleeperLeagueId(),
            context.providerSeason(),
            context.providerStatus(),
            context.providerLeg(),
            normalizedOwnerId,
            context.ownerDisplayName(),
            context.ownerTeamName(),
            context.rosterId(),
            context.butlerTeamId(),
            context.butlerTeamName(),
            context.candidateCount(),
            context.reviewableCandidateCount(),
            PRODUCTION_SEASON,
            context.targetPlayerCount(),
            context.starterCount(),
            context.benchCount(),
            context.reserveCount(),
            context.taxiCount(),
            present,
            missing,
            Collections.unmodifiableMap(new LinkedHashMap<>(sourceCoverage)),
            List.copyOf(players));
    }

    private static void validateContext(
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport context,
        String leagueId,
        String ownerId) {
        Objects.requireNonNull(context, "BF-610 context must not be null");
        if (!SleeperLiveWaiverTargetRosterContextAudit.POLICY_ID.equals(context.policyId())) {
            throw new IllegalStateException("BF-611 BLOCKED: unexpected BF-610 target-roster context policy");
        }
        if (!leagueId.equals(context.leagueId()) || !ownerId.equals(context.sleeperOwnerId())) {
            throw new IllegalStateException("BF-611 BLOCKED: BF-610 target-roster context does not match requested league/owner");
        }
        if (context.providerSeason() != 2026 || !"in_season".equals(context.providerStatus())) {
            throw new IllegalStateException("BF-611 BLOCKED: BF-610 context is not in-season 2026");
        }
        if (context.targetPlayerCount() <= 0 || context.targetPlayers().size() != context.targetPlayerCount()) {
            throw new IllegalStateException("BF-611 BLOCKED: BF-610 target roster is empty or unreconciled");
        }
        if (context.exactMappedTargetPlayers() != context.targetPlayerCount()
            || context.unmappedTargetPlayers() != 0) {
            throw new IllegalStateException("BF-611 BLOCKED: every target-roster player must have an exact canonical mapping");
        }
        for (var target : context.targetPlayers()) {
            if (!"EXACT_CANONICAL".equals(target.mappingState())
                || target.butlerPlayerId() == null || target.butlerPlayerId().isBlank()) {
                throw new IllegalStateException("BF-611 BLOCKED: non-exact target-roster identity "
                    + target.sleeperPlayerId());
            }
        }
    }

    private static List<ProductionObservation> latestProductionPerSource(
        List<PlayerSeasonProduction> rows,
        int season) {
        Map<String, PlayerSeasonProduction> latest = new LinkedHashMap<>();
        for (PlayerSeasonProduction row : rows) {
            if (row.season() != season) continue;
            PlayerSeasonProduction existing = latest.get(row.source());
            if (existing == null || row.asOfDate().isAfter(existing.asOfDate())) {
                latest.put(row.source(), row);
            }
        }
        return latest.values().stream()
            .sorted(Comparator.comparing(PlayerSeasonProduction::source))
            .map(row -> new ProductionObservation(
                row.source(),
                row.asOfDate(),
                row.gamesPlayed(),
                row.passingYards(),
                row.passingTouchdowns(),
                row.rushingYards(),
                row.rushingTouchdowns(),
                row.receptions(),
                row.receivingYards(),
                row.receivingTouchdowns()))
            .toList();
    }

    @FunctionalInterface
    interface ContextSource {
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport audit(String leagueId, String ownerId)
            throws SQLException, IOException, InterruptedException;
    }

    public enum CoverageState {
        PRIOR_PRODUCTION_PRESENT,
        PRIOR_PRODUCTION_MISSING
    }

    public record TargetPlayerCoverage(
        SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer target,
        CoverageState state,
        List<ProductionObservation> production) {
        public TargetPlayerCoverage {
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(state, "state must not be null");
            production = List.copyOf(Objects.requireNonNull(production, "production must not be null"));
            if (state == CoverageState.PRIOR_PRODUCTION_PRESENT && production.isEmpty()) {
                throw new IllegalArgumentException("present production state requires evidence rows");
            }
            if (state == CoverageState.PRIOR_PRODUCTION_MISSING && !production.isEmpty()) {
                throw new IllegalArgumentException("missing production state cannot retain production rows");
            }
        }
    }

    public record ProductionObservation(
        String source,
        LocalDate asOfDate,
        int gamesPlayed,
        int passingYards,
        int passingTouchdowns,
        int rushingYards,
        int rushingTouchdowns,
        int receptions,
        int receivingYards,
        int receivingTouchdowns) {}

    public record SourceCoverage(
        int targetPlayerObservations,
        LocalDate earliestAsOf,
        LocalDate latestAsOf) {}

    public record AuditReport(
        String policyId,
        String contextPolicyId,
        String leagueId,
        String marketSnapshotId,
        String waiverSnapshotId,
        String sleeperLeagueId,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        String sleeperOwnerId,
        String ownerDisplayName,
        String ownerTeamName,
        int rosterId,
        String butlerTeamId,
        String butlerTeamName,
        int candidateCount,
        int reviewableCandidateCount,
        int productionSeason,
        int targetPlayerCount,
        int starterCount,
        int benchCount,
        int reserveCount,
        int taxiCount,
        int priorProductionPresent,
        int priorProductionMissing,
        Map<String, SourceCoverage> sourceCoverage,
        List<TargetPlayerCoverage> players) {
        public AuditReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-611 policyId");
            if (!SleeperLiveWaiverTargetRosterContextAudit.POLICY_ID.equals(contextPolicyId)) {
                throw new IllegalArgumentException("unexpected BF-610 context policyId");
            }
            sourceCoverage = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(sourceCoverage, "sourceCoverage must not be null")));
            players = List.copyOf(Objects.requireNonNull(players, "players must not be null"));
            if (targetPlayerCount != players.size()) throw new IllegalArgumentException("target player count must reconcile");
            if (starterCount + benchCount + reserveCount + taxiCount != targetPlayerCount) {
                throw new IllegalArgumentException("target roster slot counts must reconcile");
            }
            if (priorProductionPresent + priorProductionMissing != targetPlayerCount) {
                throw new IllegalArgumentException("production coverage counts must reconcile");
            }
        }
    }

    private static final class MutableSourceCoverage {
        private int count;
        private LocalDate earliest;
        private LocalDate latest;

        private void observe(LocalDate date) {
            count++;
            if (earliest == null || date.isBefore(earliest)) earliest = date;
            if (latest == null || date.isAfter(latest)) latest = date;
        }

        private SourceCoverage freeze() {
            return new SourceCoverage(count, earliest, latest);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
