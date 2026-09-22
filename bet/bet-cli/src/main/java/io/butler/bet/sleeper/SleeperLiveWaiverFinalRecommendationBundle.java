package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LiveWaiverSnapshotRepository;
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
import java.util.Set;
import java.util.TreeSet;

/** BF-618 through BF-620 final recommendation plus BF-624 governed cross-position transaction selection. */
public final class SleeperLiveWaiverFinalRecommendationBundle {
    public static final String BF618_POLICY_ID =
        "sleeper-live-waiver-final-selection-method-v4-bf903-all-live-drop-transaction-first";
    public static final String BF619_POLICY_ID =
        "sleeper-live-waiver-final-add-drop-selection-v4-bf903-all-live-drop-transaction-first";
    public static final String BF620_POLICY_ID =
        "sleeper-live-waiver-final-recommendation-v1-live-bf610-and-bf602-membership-reverified-read-only";
    public static final String BF624_POLICY_ID =
        "sleeper-live-waiver-complete-transaction-improvement-v2-bf903-all-live-bench-reserve-drops";
    private static final int PRODUCTION_SEASON = 2025;

    private final Database database;
    private final BundleSource bundleSource;
    private final FreshnessSource freshnessSource;
    private final ProductionSource productionSource;
    private final SnapshotEntriesSource snapshotEntriesSource;

    public SleeperLiveWaiverFinalRecommendationBundle(Database database) {
        this(
            Objects.requireNonNull(database, "database must not be null"),
            (leagueId, ownerId) -> new SleeperLiveWaiverComparisonExecutionBundle(database).run(leagueId, ownerId),
            (leagueId, ownerId) -> new SleeperLiveWaiverTargetRosterContextAudit(database).audit(leagueId, ownerId),
            new PlayerSeasonProductionRepository(database)::findByPlayerId,
            snapshotId -> new LiveWaiverSnapshotRepository(database).entries(snapshotId));
    }

    SleeperLiveWaiverFinalRecommendationBundle(
        Database database,
        BundleSource bundleSource,
        FreshnessSource freshnessSource,
        ProductionSource productionSource,
        SnapshotEntriesSource snapshotEntriesSource) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.bundleSource = Objects.requireNonNull(bundleSource, "bundleSource must not be null");
        this.freshnessSource = Objects.requireNonNull(freshnessSource, "freshnessSource must not be null");
        this.productionSource = Objects.requireNonNull(productionSource, "productionSource must not be null");
        this.snapshotEntriesSource = Objects.requireNonNull(snapshotEntriesSource, "snapshotEntriesSource must not be null");
    }

    public RecommendationReport run(String leagueId, String sleeperOwnerId)
        throws SQLException, IOException, InterruptedException {
        return execute(leagueId, sleeperOwnerId).recommendation();
    }

    /** BF-660 retains the exact BF-615 bundle used by this BF-620 recommendation for audit-time explanation capture. */
    RecommendationExecution execute(String leagueId, String sleeperOwnerId)
        throws SQLException, IOException, InterruptedException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");

        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle =
            bundleSource.run(normalizedLeagueId, normalizedOwnerId);
        validateBundle(bundle, normalizedLeagueId, normalizedOwnerId);

        SleeperLiveWaiverTargetRosterContextAudit.AuditReport freshness =
            freshnessSource.audit(normalizedLeagueId, normalizedOwnerId);
        validateFreshness(bundle, freshness, normalizedLeagueId, normalizedOwnerId);

        MethodologyReport methodology = methodology(bundle);
        SelectionReport selection = select(bundle, freshness);

        RecommendationReport recommendation;
        if (selection.state() != SelectionState.UNIQUE_ADD_DROP_SELECTED) {
            recommendation = new RecommendationReport(
                BF620_POLICY_ID, normalizedLeagueId, normalizedOwnerId,
                bundle.comparisons().marketSnapshotId(), bundle.comparisons().waiverSnapshotId(),
                bundle.comparisons().sleeperLeagueId(), bundle.comparisons().rosterId(),
                methodology, selection, freshness.providerSeason(), freshness.providerStatus(), freshness.providerLeg(),
                null, null, newcomerAlternatives(bundle),
                RecommendationState.NO_GOVERNED_TRANSACTION);
        } else {
            var add = Objects.requireNonNull(selection.selectedAdd());
            var drop = Objects.requireNonNull(selection.selectedDrop());
            validateSelectedPairAgainstLiveAndSnapshot(bundle, freshness, add, drop);
            recommendation = new RecommendationReport(
                BF620_POLICY_ID, normalizedLeagueId, normalizedOwnerId,
                bundle.comparisons().marketSnapshotId(), bundle.comparisons().waiverSnapshotId(),
                bundle.comparisons().sleeperLeagueId(), bundle.comparisons().rosterId(),
                methodology, selection, freshness.providerSeason(), freshness.providerStatus(), freshness.providerLeg(),
                add, drop, newcomerAlternatives(bundle),
                RecommendationState.RECOMMEND_ADD_DROP);
        }
        return new RecommendationExecution(recommendation, bundle);
    }

    private MethodologyReport methodology(SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle) {
        List<SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry> historical = historicalFinalists(bundle);
        Set<String> positions = new TreeSet<>();
        historical.forEach(value -> positions.add(requireText(value.candidate().position(), "historical finalist position").toUpperCase()));
        return new MethodologyReport(
            BF618_POLICY_ID,
            bundle.comparisons().marketSnapshotId(),
            historical.size(),
            bundle.shortlist().newcomerShortlistCount(),
            List.copyOf(positions),
            "HISTORICAL_CANDIDATE_ADMISSION_REQUIRES_AT_LEAST_ONE_BF615_SUPPORTED_SAME_POSITION_DROP",
            "LATEST_2025_COMMON_SOURCE_SUPPORTED_SUBTOTAL_PER_GAME_SCHEMA_EQUALITY",
            "BF903_ALL_HISTORICAL_CANDIDATE_X_LIVE_BENCH_RESERVE_TRANSACTION_DELTA_STRICT_DOMINANCE",
            "NEWCOMERS_NONNUMERIC_NOT_ELIGIBLE_FOR_FINAL_WINNER",
            "DROP_FROM_ANY_EXACT_LIVE_BENCH_RESERVE_PLAYER_WITH_COMPATIBLE_COMMON_SOURCE_PRODUCTION",
            "PROTECTED_MISSING_PRODUCTION_TARGET_NEVER_DROPPABLE",
            MethodologyState.FINAL_SELECTION_METHOD_FROZEN);
    }

    private SelectionReport select(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport freshness) throws SQLException {
        List<SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry> historical = historicalFinalists(bundle);
        if (historical.isEmpty()) {
            return selection(bundle, SelectionState.NO_HISTORICAL_FINALIST, null, null, List.of(), List.of());
        }

        var transactions = new SleeperLiveWaiverCrossPositionTransactionSelector(productionSource)
            .select(bundle, historical, freshness);
        return selection(
            bundle,
            transactions.state(),
            transactions.selectedAdd(),
            transactions.selectedDrop(),
            finalistIds(historical),
            transactions.directComparisons());
    }

    private void validateSelectedPairAgainstLiveAndSnapshot(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport freshness,
        SelectedPlayer add,
        SelectedPlayer drop) throws SQLException {
        boolean dropLive = freshness.targetPlayers().stream().anyMatch(value ->
            value.sleeperPlayerId().equals(drop.sleeperPlayerId())
                && SleeperLiveWaiverCandidateRosterComparisonMethodology.eligibleReplacementSlot(value.rosterSlot()));
        if (!dropLive) {
            throw new IllegalStateException("BF-620 BLOCKED: selected drop is not on the exact live target BENCH/RESERVE roster");
        }
        if (freshness.targetPlayers().stream().anyMatch(value -> value.sleeperPlayerId().equals(add.sleeperPlayerId()))) {
            throw new IllegalStateException("BF-620 BLOCKED: selected add is already on the exact live target roster");
        }

        List<LiveWaiverSnapshotRepository.Entry> entries = snapshotEntriesSource.load(bundle.comparisons().waiverSnapshotId());
        Map<String, LiveWaiverSnapshotRepository.Entry> byId = new LinkedHashMap<>();
        for (var entry : entries) {
            if (byId.putIfAbsent(entry.sleeperPlayerId(), entry) != null) {
                throw new IllegalStateException("BF-620 BLOCKED: duplicate BF-602 snapshot identity " + entry.sleeperPlayerId());
            }
        }
        var addSnapshot = byId.get(add.sleeperPlayerId());
        var dropSnapshot = byId.get(drop.sleeperPlayerId());
        if (addSnapshot == null || !addSnapshot.freeAgent() || addSnapshot.rostered() || !addSnapshot.leagueEligible()) {
            throw new IllegalStateException("BF-620 BLOCKED: selected add was not an exact league-eligible free agent in referenced BF-602 snapshot");
        }
        if (dropSnapshot == null || !dropSnapshot.rostered() || dropSnapshot.freeAgent()) {
            throw new IllegalStateException("BF-620 BLOCKED: selected drop was not rostered in referenced BF-602 snapshot");
        }

        boolean historicalShortlist = bundle.shortlist().shortlist().stream().anyMatch(value ->
            value.lane() == SleeperLiveWaiverComparisonExecutionBundle.ShortlistLane.HISTORICAL_DIRECTIONAL
                && value.candidate().sleeperPlayerId().equals(add.sleeperPlayerId()));
        if (!historicalShortlist) {
            throw new IllegalStateException("BF-620 BLOCKED: selected add is absent from exact BF-616 historical shortlist");
        }
        var addEntry = bundle.shortlist().shortlist().stream()
            .filter(value -> value.lane()
                == SleeperLiveWaiverComparisonExecutionBundle.ShortlistLane.HISTORICAL_DIRECTIONAL)
            .filter(value -> value.candidate().sleeperPlayerId().equals(add.sleeperPlayerId()))
            .map(SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry::candidate)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "BF-620 BLOCKED: selected add is absent from exact BF-616 historical shortlist"));

        var dropTarget = freshness.targetPlayers().stream()
            .filter(value -> value.sleeperPlayerId().equals(drop.sleeperPlayerId()))
            .filter(value -> SleeperLiveWaiverCandidateRosterComparisonMethodology
                .eligibleReplacementSlot(value.rosterSlot()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "BF-620 BLOCKED: selected drop is absent from exact live BENCH/RESERVE roster"));

        if (dropTarget.butlerPlayerId() == null || dropTarget.butlerPlayerId().isBlank()
            || !positiveTransactionEvidence(
                addEntry.butlerPlayerId(),
                dropTarget.butlerPlayerId(),
                bundle.methodology().exactLeagueScoringSettings())) {
            throw new IllegalStateException(
                "BF-620 BLOCKED: selected add/drop lacks positive compatible complete-transaction evidence");
        }
    }

    private boolean positiveTransactionEvidence(
        String addButlerPlayerId,
        String dropButlerPlayerId,
        Map<String, Double> scoring) throws SQLException {
        Map<String, PlayerSeasonProduction> addRows =
            latest2025BySource(productionSource.load(addButlerPlayerId));
        Map<String, PlayerSeasonProduction> dropRows =
            latest2025BySource(productionSource.load(dropButlerPlayerId));

        Set<String> common = new TreeSet<>(addRows.keySet());
        common.retainAll(dropRows.keySet());
        if (common.isEmpty()) return false;

        for (String source : common) {
            var addSubtotal = SleeperLiveWaiverCandidateRosterComparisonMethodology
                .supportedSubtotal(addRows.get(source), scoring);
            var dropSubtotal = SleeperLiveWaiverCandidateRosterComparisonMethodology
                .supportedSubtotal(dropRows.get(source), scoring);
            if (addSubtotal.supportedSubtotalPerGame() == null
                || dropSubtotal.supportedSubtotalPerGame() == null
                || !addSubtotal.includedScoringKeys().equals(dropSubtotal.includedScoringKeys())
                || !(addSubtotal.supportedSubtotalPerGame() > dropSubtotal.supportedSubtotalPerGame())) {
                return false;
            }
        }
        return true;
    }

    private static void validateBundle(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        String leagueId,
        String ownerId) {
        Objects.requireNonNull(bundle, "BF-615–617 bundle must not be null");
        if (bundle.decisionReadiness().state()
            != SleeperLiveWaiverComparisonExecutionBundle.FinalDecisionAuthorizationState.READY_FOR_FINAL_WAIVER_DECISION_METHOD) {
            throw new IllegalStateException("BF-618 BLOCKED: BF-617 did not authorize final decision method");
        }
        if (!leagueId.equals(bundle.comparisons().leagueId()) || !ownerId.equals(bundle.comparisons().sleeperOwnerId())) {
            throw new IllegalStateException("BF-618 BLOCKED: BF-615–617 league/owner lineage differs from request");
        }
        if (!bundle.comparisons().marketSnapshotId().equals(bundle.shortlist().marketSnapshotId())
            || !bundle.comparisons().marketSnapshotId().equals(bundle.decisionReadiness().marketSnapshotId())) {
            throw new IllegalStateException("BF-618 BLOCKED: BF-615–617 market lineage does not reconcile");
        }
    }

    private static void validateFreshness(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport freshness,
        String leagueId,
        String ownerId) {
        Objects.requireNonNull(freshness, "BF-620 freshness report must not be null");
        if (!leagueId.equals(freshness.leagueId()) || !ownerId.equals(freshness.sleeperOwnerId())) {
            throw new IllegalStateException("BF-620 BLOCKED: final BF-610 freshness league/owner differs from request");
        }
        if (!bundle.comparisons().marketSnapshotId().equals(freshness.marketSnapshotId())
            || !bundle.comparisons().waiverSnapshotId().equals(freshness.waiverSnapshotId())
            || !bundle.comparisons().sleeperLeagueId().equals(freshness.sleeperLeagueId())
            || bundle.comparisons().rosterId() != freshness.rosterId()) {
            throw new IllegalStateException("BF-620 BLOCKED: final BF-610 freshness lineage differs from BF-615–617 frame");
        }
    }

    private static List<SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry> historicalFinalists(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle) {
        return bundle.shortlist().shortlist().stream()
            .filter(value -> value.lane() == SleeperLiveWaiverComparisonExecutionBundle.ShortlistLane.HISTORICAL_DIRECTIONAL)
            .sorted(Comparator.comparing(value -> value.candidate().sleeperPlayerId()))
            .toList();
    }

    private static List<String> finalistIds(List<SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry> values) {
        return values.stream().map(value -> value.candidate().sleeperPlayerId()).sorted().toList();
    }

    private static List<SelectedPlayer> newcomerAlternatives(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle) {
        return bundle.shortlist().shortlist().stream()
            .filter(value -> value.lane() == SleeperLiveWaiverComparisonExecutionBundle.ShortlistLane.NEWCOMER_REVIEW)
            .map(value -> candidate(value.candidate()))
            .sorted(Comparator.comparing(SelectedPlayer::sleeperPlayerId))
            .toList();
    }

    private static SelectedPlayer candidate(SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry value) {
        return new SelectedPlayer(
            value.sleeperPlayerId(), value.displayName(), value.position(), "WAIVER_CANDIDATE",
            value.currentTeam(), value.currentStatus(), value.injuryStatus(), value.depthChartPosition(), value.depthChartOrder());
    }

    private static SelectedPlayer roster(SleeperLiveWaiverComparisonExecutionBundle.RosterEntry value) {
        return new SelectedPlayer(
            value.sleeperPlayerId(), value.displayName(), value.position(), value.rosterSlot(),
            null, null, null, null, null);
    }

    private static SelectionReport selection(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        SelectionState state,
        SelectedPlayer add,
        SelectedPlayer drop,
        List<String> consideredIds,
        List<DirectComparison> directComparisons) {
        return new SelectionReport(
            BF619_POLICY_ID, bundle.comparisons().marketSnapshotId(),
            consideredIds, directComparisons, add, drop, state);
    }

    private static Map<String, PlayerSeasonProduction> latest2025BySource(List<PlayerSeasonProduction> rows) {
        Map<String, PlayerSeasonProduction> latest = new LinkedHashMap<>();
        for (PlayerSeasonProduction row : Objects.requireNonNull(rows, "production rows must not be null")) {
            if (row.season() != PRODUCTION_SEASON) continue;
            PlayerSeasonProduction existing = latest.get(row.source());
            if (existing == null
                || row.asOfDate().isAfter(existing.asOfDate())
                || (row.asOfDate().equals(existing.asOfDate()) && row.id().compareTo(existing.id()) < 0)) {
                latest.put(row.source(), row);
            }
        }
        Map<String, PlayerSeasonProduction> sorted = new LinkedHashMap<>();
        latest.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(sorted);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    record RecommendationExecution(
        RecommendationReport recommendation,
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle) {
        RecommendationExecution {
            Objects.requireNonNull(recommendation, "recommendation must not be null");
            Objects.requireNonNull(bundle, "bundle must not be null");
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

    @FunctionalInterface
    interface ProductionSource {
        List<PlayerSeasonProduction> load(String butlerPlayerId) throws SQLException;
    }

    @FunctionalInterface
    interface SnapshotEntriesSource {
        List<LiveWaiverSnapshotRepository.Entry> load(String snapshotId) throws SQLException;
    }

    public enum MethodologyState { FINAL_SELECTION_METHOD_FROZEN }

    public enum DirectDirection {
        LEFT_DIRECTIONALLY_SUPPORTED,
        RIGHT_DIRECTIONALLY_SUPPORTED,
        TIED_ALL_COMMON_SOURCES,
        SOURCE_DIRECTION_UNRESOLVED,
        NO_COMMON_SOURCE
    }

    public enum SelectionState {
        UNIQUE_ADD_DROP_SELECTED,
        NO_HISTORICAL_FINALIST,
        MULTI_POSITION_HISTORICAL_FINALISTS_UNRESOLVED,
        NO_UNIQUE_HISTORICAL_ADD,
        NO_GOVERNED_DROP_FOR_SELECTED_ADD,
        NO_UNIQUE_GOVERNED_DROP,
        CROSS_POSITION_NO_ACTIONABLE_TRANSACTION,
        CROSS_POSITION_TRANSACTION_EVIDENCE_INCOMPATIBLE,
        CROSS_POSITION_TRANSACTION_IMPROVEMENT_UNRESOLVED
    }

    public enum RecommendationState { RECOMMEND_ADD_DROP, NO_GOVERNED_TRANSACTION }

    public record DirectSourceComparison(
        String source,
        LocalDate leftAsOf,
        LocalDate rightAsOf,
        Double leftSupportedSubtotalPerGame,
        Double rightSupportedSubtotalPerGame,
        List<String> leftIncludedScoringKeys,
        List<String> rightIncludedScoringKeys,
        String sourceState) {
        public DirectSourceComparison {
            leftIncludedScoringKeys = List.copyOf(leftIncludedScoringKeys);
            rightIncludedScoringKeys = List.copyOf(rightIncludedScoringKeys);
        }
    }

    public record DirectComparison(
        String leftSleeperPlayerId,
        String rightSleeperPlayerId,
        List<String> commonSources,
        List<DirectSourceComparison> sources,
        DirectDirection direction) {
        public DirectComparison {
            commonSources = List.copyOf(commonSources);
            sources = List.copyOf(sources);
            Objects.requireNonNull(direction);
        }
    }

    public record SelectedPlayer(
        String sleeperPlayerId,
        String displayName,
        String position,
        String role,
        String currentTeam,
        String currentStatus,
        String injuryStatus,
        String depthChartPosition,
        Integer depthChartOrder) {}

    public record MethodologyReport(
        String policyId,
        String marketSnapshotId,
        int historicalFinalists,
        int newcomerFinalists,
        List<String> historicalFinalistPositions,
        String addWinnerRule,
        String evidenceRule,
        String crossPositionRule,
        String newcomerRule,
        String dropRule,
        String protectedTargetRule,
        MethodologyState state) {
        public MethodologyReport {
            if (!BF618_POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-618 policy");
            historicalFinalistPositions = List.copyOf(historicalFinalistPositions);
            Objects.requireNonNull(state);
        }
    }

    public record SelectionReport(
        String policyId,
        String marketSnapshotId,
        List<String> consideredSleeperPlayerIds,
        List<DirectComparison> directComparisons,
        SelectedPlayer selectedAdd,
        SelectedPlayer selectedDrop,
        SelectionState state) {
        public SelectionReport {
            if (!BF619_POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-619 policy");
            consideredSleeperPlayerIds = List.copyOf(consideredSleeperPlayerIds);
            directComparisons = List.copyOf(directComparisons);
            Objects.requireNonNull(state);
            if (state == SelectionState.UNIQUE_ADD_DROP_SELECTED && (selectedAdd == null || selectedDrop == null)) {
                throw new IllegalArgumentException("unique selection requires add and drop");
            }
        }
    }

    public record RecommendationReport(
        String policyId,
        String leagueId,
        String sleeperOwnerId,
        String marketSnapshotId,
        String waiverSnapshotId,
        String sleeperLeagueId,
        int rosterId,
        MethodologyReport methodology,
        SelectionReport selection,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        SelectedPlayer recommendedAdd,
        SelectedPlayer recommendedDrop,
        List<SelectedPlayer> newcomerReviewAlternatives,
        RecommendationState state) {
        public RecommendationReport {
            if (!BF620_POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected BF-620 policy");
            Objects.requireNonNull(methodology);
            Objects.requireNonNull(selection);
            newcomerReviewAlternatives = List.copyOf(newcomerReviewAlternatives);
            Objects.requireNonNull(state);
            if (state == RecommendationState.RECOMMEND_ADD_DROP
                && (recommendedAdd == null || recommendedDrop == null)) {
                throw new IllegalArgumentException("recommendation requires add/drop pair");
            }
        }
    }
}
