package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerSeasonProductionRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** BF-625 read-only evidence sidecar for the governed BF-903 complete-transaction recommendation. */
public final class SleeperLiveWaiverCrossPositionTransactionEvidence {
    public static final String POLICY_ID =
        "sleeper-live-waiver-complete-transaction-evidence-v2-bf903-all-live-bench-reserve-drops";

    private final BundleSource bundleSource;
    private final FreshnessSource freshnessSource;
    private final SleeperLiveWaiverFinalRecommendationBundle.ProductionSource productionSource;

    public SleeperLiveWaiverCrossPositionTransactionEvidence(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        PlayerSeasonProductionRepository productionRepository = new PlayerSeasonProductionRepository(database);
        this.bundleSource = (leagueId, ownerId) ->
            new SleeperLiveWaiverComparisonExecutionBundle(database).run(leagueId, ownerId);
        this.freshnessSource = (leagueId, ownerId) ->
            new SleeperLiveWaiverTargetRosterContextAudit(database).audit(leagueId, ownerId);
        this.productionSource = productionRepository::findByPlayerId;
    }

    SleeperLiveWaiverCrossPositionTransactionEvidence(
        BundleSource bundleSource,
        FreshnessSource freshnessSource,
        SleeperLiveWaiverFinalRecommendationBundle.ProductionSource productionSource) {
        this.bundleSource = Objects.requireNonNull(bundleSource, "bundleSource must not be null");
        this.freshnessSource = Objects.requireNonNull(freshnessSource, "freshnessSource must not be null");
        this.productionSource = Objects.requireNonNull(productionSource, "productionSource must not be null");
    }

    public EvidenceReport explain(SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation)
        throws SQLException, IOException, InterruptedException {
        Objects.requireNonNull(recommendation, "recommendation must not be null");
        if (recommendation.methodology().historicalFinalists() <= 0) {
            return new EvidenceReport(
                POLICY_ID,
                recommendation.leagueId(),
                recommendation.sleeperOwnerId(),
                recommendation.marketSnapshotId(),
                recommendation.waiverSnapshotId(),
                recommendation.selection().state(),
                List.of(),
                EvidenceState.NOT_APPLICABLE_NO_HISTORICAL_CANDIDATE);
        }

        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle =
            bundleSource.run(recommendation.leagueId(), recommendation.sleeperOwnerId());
        validateLineage(recommendation, bundle);

        SleeperLiveWaiverTargetRosterContextAudit.AuditReport freshness =
            freshnessSource.audit(recommendation.leagueId(), recommendation.sleeperOwnerId());
        validateFreshness(recommendation, freshness);

        List<SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry> historical =
            bundle.shortlist().shortlist().stream()
                .filter(value -> value.lane()
                    == SleeperLiveWaiverComparisonExecutionBundle.ShortlistLane.HISTORICAL_DIRECTIONAL)
                .sorted(Comparator.comparing(value -> value.candidate().sleeperPlayerId()))
                .toList();

        SleeperLiveWaiverCrossPositionTransactionSelector.Result result =
            new SleeperLiveWaiverCrossPositionTransactionSelector(productionSource)
                .select(bundle, historical, freshness);
        validateSelection(recommendation, result);

        List<TransactionEvidence> options = new ArrayList<>();
        for (var option : result.options()) {
            boolean selected = recommendation.recommendedAdd() != null
                && recommendation.recommendedDrop() != null
                && recommendation.recommendedAdd().sleeperPlayerId().equals(option.add().sleeperPlayerId())
                && recommendation.recommendedDrop().sleeperPlayerId().equals(option.drop().sleeperPlayerId());
            options.add(new TransactionEvidence(
                option.add(), option.drop(), option.improvementBySource(), option.scoringKeysBySource(), selected));
        }
        options.sort(Comparator
            .comparing((TransactionEvidence value) -> value.add().sleeperPlayerId())
            .thenComparing(value -> value.drop().sleeperPlayerId()));

        return new EvidenceReport(
            POLICY_ID,
            recommendation.leagueId(),
            recommendation.sleeperOwnerId(),
            recommendation.marketSnapshotId(),
            recommendation.waiverSnapshotId(),
            result.state(),
            List.copyOf(options),
            EvidenceState.RECONCILED);
    }

    private static void validateLineage(
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation,
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle) {
        Objects.requireNonNull(bundle, "BF-625 bundle must not be null");
        var comparisons = bundle.comparisons();
        if (!recommendation.leagueId().equals(comparisons.leagueId())
            || !recommendation.sleeperOwnerId().equals(comparisons.sleeperOwnerId())
            || !recommendation.marketSnapshotId().equals(comparisons.marketSnapshotId())
            || !recommendation.waiverSnapshotId().equals(comparisons.waiverSnapshotId())
            || !recommendation.sleeperLeagueId().equals(comparisons.sleeperLeagueId())
            || recommendation.rosterId() != comparisons.rosterId()) {
            throw new IllegalStateException(
                "BF-625 BLOCKED: explanation lineage differs from the emitted BF-620 recommendation");
        }
    }

    private static void validateFreshness(
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation,
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport freshness) {
        Objects.requireNonNull(freshness, "BF-625 freshness must not be null");
        if (!recommendation.leagueId().equals(freshness.leagueId())
            || !recommendation.sleeperOwnerId().equals(freshness.sleeperOwnerId())
            || !recommendation.marketSnapshotId().equals(freshness.marketSnapshotId())
            || !recommendation.waiverSnapshotId().equals(freshness.waiverSnapshotId())
            || !recommendation.sleeperLeagueId().equals(freshness.sleeperLeagueId())
            || recommendation.rosterId() != freshness.rosterId()) {
            throw new IllegalStateException(
                "BF-625 BLOCKED: live-roster evidence lineage differs from the emitted BF-620 recommendation");
        }
    }

    private static void validateSelection(
        SleeperLiveWaiverFinalRecommendationBundle.RecommendationReport recommendation,
        SleeperLiveWaiverCrossPositionTransactionSelector.Result result) {
        if (recommendation.selection().state() != result.state()) {
            throw new IllegalStateException(
                "BF-625 BLOCKED: re-evaluated BF-903 selection state differs from emitted recommendation");
        }

        String expectedAdd = recommendation.recommendedAdd() == null
            ? null : recommendation.recommendedAdd().sleeperPlayerId();
        String expectedDrop = recommendation.recommendedDrop() == null
            ? null : recommendation.recommendedDrop().sleeperPlayerId();
        String actualAdd = result.selectedAdd() == null ? null : result.selectedAdd().sleeperPlayerId();
        String actualDrop = result.selectedDrop() == null ? null : result.selectedDrop().sleeperPlayerId();
        if (!Objects.equals(expectedAdd, actualAdd) || !Objects.equals(expectedDrop, actualDrop)) {
            throw new IllegalStateException(
                "BF-625 BLOCKED: re-evaluated BF-903 selected pair differs from emitted recommendation");
        }
    }

    @FunctionalInterface
    interface BundleSource {
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport run(String leagueId, String ownerId)
            throws SQLException, IOException, InterruptedException;
    }

    @FunctionalInterface
    interface FreshnessSource {
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport audit(String leagueId, String ownerId)
            throws SQLException, IOException, InterruptedException;
    }

    public enum EvidenceState {
        RECONCILED,
        NOT_APPLICABLE_NO_HISTORICAL_CANDIDATE
    }

    public record TransactionEvidence(
        SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer add,
        SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer drop,
        Map<String, Double> improvementBySource,
        Map<String, List<String>> scoringKeysBySource,
        boolean selected) {
        public TransactionEvidence {
            Objects.requireNonNull(add);
            Objects.requireNonNull(drop);
            improvementBySource = Collections.unmodifiableMap(new LinkedHashMap<>(improvementBySource));
            Map<String, List<String>> immutableKeys = new LinkedHashMap<>();
            scoringKeysBySource.forEach((key, value) -> immutableKeys.put(key, List.copyOf(value)));
            scoringKeysBySource = Collections.unmodifiableMap(immutableKeys);
        }
    }

    public record EvidenceReport(
        String policyId,
        String leagueId,
        String sleeperOwnerId,
        String marketSnapshotId,
        String waiverSnapshotId,
        SleeperLiveWaiverFinalRecommendationBundle.SelectionState selectionState,
        List<TransactionEvidence> options,
        EvidenceState state) {
        public EvidenceReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-625 policy");
            Objects.requireNonNull(selectionState);
            options = List.copyOf(options);
            Objects.requireNonNull(state);
        }
    }
}
