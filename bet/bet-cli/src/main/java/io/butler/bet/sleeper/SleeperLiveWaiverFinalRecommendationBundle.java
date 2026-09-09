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
        "sleeper-live-waiver-final-selection-method-v2-bf624-cross-position-transaction-improvement";
    public static final String BF619_POLICY_ID =
        "sleeper-live-waiver-final-add-drop-selection-v2-bf624-cross-position-transaction-improvement";
    public static final String BF620_POLICY_ID =
        "sleeper-live-waiver-final-recommendation-v1-live-bf610-and-bf602-membership-reverified-read-only";
    public static final String BF624_POLICY_ID =
        "sleeper-live-waiver-cross-position-transaction-improvement-v1-compatible-source-schema-strict-delta-dominance";
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
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");

        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle =
            bundleSource.run(normalizedLeagueId, normalizedOwnerId);
        validateBundle(bundle, normalizedLeagueId, normalizedOwnerId);

        MethodologyReport methodology = methodology(bundle);
        SelectionReport selection = select(bundle);

        SleeperLiveWaiverTargetRosterContextAudit.AuditReport freshness =
            freshnessSource.audit(normalizedLeagueId, normalizedOwnerId);
        validateFreshness(bundle, freshness, normalizedLeagueId, normalizedOwnerId);

        if (selection.state() != SelectionState.UNIQUE_ADD_DROP_SELECTED) {
            return new RecommendationReport(
                BF620_POLICY_ID, normalizedLeagueId, normalizedOwnerId,
                bundle.comparisons().marketSnapshotId(), bundle.comparisons().waiverSnapshotId(),
                bundle.comparisons().sleeperLeagueId(), bundle.comparisons().rosterId(),
                methodology, selection, freshness.providerSeason(), freshness.providerStatus(), freshness.providerLeg(),
                null, null, newcomerAlternatives(bundle),
                RecommendationState.NO_GOVERNED_TRANSACTION);
        }

        var add = Objects.requireNonNull(selection.selectedAdd());
        var drop = Objects.requireNonNull(selection.selectedDrop());
        validateSelectedPairAgainstLiveAndSnapshot(bundle, freshness, add, drop);

        return new RecommendationReport(
            BF620_POLICY_ID, normalizedLeagueId, normalizedOwnerId,
            bundle.comparisons().marketSnapshotId(), bundle.comparisons().waiverSnapshotId(),
            bundle.comparisons().sleeperLeagueId(), bundle.comparisons().rosterId(),
            methodology, selection, freshness.providerSeason(), freshness.providerStatus(), freshness.providerLeg(),
            add, drop, newcomerAlternatives(bundle),
            RecommendationState.RECOMMEND_ADD_DROP);
    }

    /** BF-659 replays the frozen final method against an explicitly reconstructed pre-transaction roster frame. */
    RecommendationReport replay(
        String leagueId,
        String sleeperOwnerId,
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        ReplayFreshnessFrame freshness) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedOwnerId = requireText(sleeperOwnerId, "sleeperOwnerId");
        validateBundle(bundle, normalizedLeagueId, normalizedOwnerId);
        validateReplayFreshness(bundle, freshness, normalizedLeagueId, normalizedOwnerId);

        MethodologyReport methodology = methodology(bundle);
        SelectionReport selection = select(bundle);
        if (selection.state() != SelectionState.UNIQUE_ADD_DROP_SELECTED) {
            return new RecommendationReport(
                BF620_POLICY_ID, normalizedLeagueId, normalizedOwnerId,
                bundle.comparisons().marketSnapshotId(), bundle.comparisons().waiverSnapshotId(),
                bundle.comparisons().sleeperLeagueId(), bundle.comparisons().rosterId(),
                methodology, selection, freshness.providerSeason(), freshness.providerStatus(), freshness.providerLeg(),
                null, null, newcomerAlternatives(bundle), RecommendationState.NO_GOVERNED_TRANSACTION);
        }

        var add = Objects.requireNonNull(selection.selectedAdd());
        var drop = Objects.requireNonNull(selection.selectedDrop());
        validateSelectedPairAgainstReplayAndSnapshot(bundle, freshness, add, drop);
        return new RecommendationReport(
            BF620_POLICY_ID, normalizedLeagueId, normalizedOwnerId,
            bundle.comparisons().marketSnapshotId(), bundle.comparisons().waiverSnapshotId(),
            bundle.comparisons().sleeperLeagueId(), bundle.comparisons().rosterId(),
            methodology, selection, freshness.providerSeason(), freshness.providerStatus(), freshness.providerLeg(),
            add, drop, newcomerAlternatives(bundle), RecommendationState.RECOMMEND_ADD_DROP);
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
            "HISTORICAL_FINALISTS_DIRECT_ALL_OPPONENTS_DOMINANCE_WITHIN_POSITION",
            "LATEST_2025_COMMON_SOURCE_SUPPORTED_SUBTOTAL_PER_GAME_SCHEMA_EQUALITY",
            "BF624_COMPLETE_TRANSACTION_DELTA_STRICT_ALL_COMPATIBLE_COMMON_SOURCE_DOMINANCE",
            "NEWCOMERS_NONNUMERIC_NOT_ELIGIBLE_FOR_FINAL_WINNER",
            "DROP_ONLY_FROM_SELECTED_ADD_BF615_CANDIDATE_SUPPORTED_BENCH_RESERVE_COMPARATORS",
            "PROTECTED_MISSING_PRODUCTION_TARGET_NEVER_DROPPABLE",
            MethodologyState.FINAL_SELECTION_METHOD_FROZEN);
    }

    private SelectionReport select(SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle) throws SQLException {
        List<SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry> historical = historicalFinalists(bundle);
        if (historical.isEmpty()) {
            return selection(bundle, SelectionState.NO_HISTORICAL_FINALIST, null, null, List.of(), List.of());
        }
        Set<String> positions = new TreeSet<>();
        historical.forEach(value -> positions.add(requireText(value.candidate().position(), "historical finalist position").toUpperCase()));
        if (positions.size() != 1) {
            var crossPosition = new SleeperLiveWaiverCrossPositionTransactionSelector(productionSource)
                .select(bundle, historical);
            return selection(
                bundle, crossPosition.state(), crossPosition.selectedAdd(), crossPosition.selectedDrop(),
                finalistIds(historical), crossPosition.directComparisons());
        }

        List<SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry> addWinners = new ArrayList<>();
        List<DirectComparison> addComparisons = new ArrayList<>();
        for (var left : historical) {
            boolean dominatesAll = true;
            for (var right : historical) {
                if (left == right) continue;
                DirectComparison comparison = comparePlayers(
                    left.candidate().butlerPlayerId(), left.candidate().sleeperPlayerId(),
                    right.candidate().butlerPlayerId(), right.candidate().sleeperPlayerId(),
                    bundle.methodology().exactLeagueScoringSettings());
                addComparisons.add(comparison);
                if (comparison.direction() != DirectDirection.LEFT_DIRECTIONALLY_SUPPORTED) {
                    dominatesAll = false;
                }
            }
            if (dominatesAll) addWinners.add(left);
        }
        if (addWinners.size() != 1) {
            return selection(bundle, SelectionState.NO_UNIQUE_HISTORICAL_ADD,
                null, null, finalistIds(historical), List.copyOf(addComparisons));
        }

        var addEntry = addWinners.get(0);
        var add = candidate(addEntry.candidate());
        SleeperLiveWaiverComparisonExecutionBundle.CandidateComparison candidateExecution =
            bundle.comparisons().candidates().stream()
                .filter(value -> value.candidate().sleeperPlayerId().equals(add.sleeperPlayerId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("BF-619 BLOCKED: selected add is absent from BF-615 candidate execution"));

        List<SleeperLiveWaiverComparisonExecutionBundle.RosterEntry> supportedDrops = candidateExecution.pairs().stream()
            .filter(value -> value.state() == SleeperLiveWaiverComparisonExecutionBundle.PairState.CANDIDATE_DIRECTIONALLY_SUPPORTED)
            .map(SleeperLiveWaiverComparisonExecutionBundle.PairComparison::roster)
            .filter(value -> SleeperLiveWaiverCandidateRosterComparisonMethodology.eligibleReplacementSlot(value.rosterSlot()))
            .filter(SleeperLiveWaiverComparisonExecutionBundle.RosterEntry::priorProductionPresent)
            .distinct()
            .sorted(Comparator.comparing(SleeperLiveWaiverComparisonExecutionBundle.RosterEntry::sleeperPlayerId))
            .toList();
        if (supportedDrops.isEmpty()) {
            return selection(bundle, SelectionState.NO_GOVERNED_DROP_FOR_SELECTED_ADD,
                add, null, finalistIds(historical), List.copyOf(addComparisons));
        }

        List<SleeperLiveWaiverComparisonExecutionBundle.RosterEntry> dropWinners = new ArrayList<>();
        List<DirectComparison> dropComparisons = new ArrayList<>();
        if (supportedDrops.size() == 1) {
            dropWinners.add(supportedDrops.get(0));
        } else {
            for (var left : supportedDrops) {
                boolean weakerThanAll = true;
                for (var right : supportedDrops) {
                    if (left == right) continue;
                    DirectComparison comparison = comparePlayers(
                        left.butlerPlayerId(), left.sleeperPlayerId(),
                        right.butlerPlayerId(), right.sleeperPlayerId(),
                        bundle.methodology().exactLeagueScoringSettings());
                    dropComparisons.add(comparison);
                    if (comparison.direction() != DirectDirection.RIGHT_DIRECTIONALLY_SUPPORTED) {
                        weakerThanAll = false;
                    }
                }
                if (weakerThanAll) dropWinners.add(left);
            }
        }
        if (dropWinners.size() != 1) {
            List<DirectComparison> all = new ArrayList<>(addComparisons);
            all.addAll(dropComparisons);
            return selection(bundle, SelectionState.NO_UNIQUE_GOVERNED_DROP,
                add, null, supportedDrops.stream().map(SleeperLiveWaiverComparisonExecutionBundle.RosterEntry::sleeperPlayerId).toList(),
                List.copyOf(all));
        }

        var drop = roster(dropWinners.get(0));
        List<DirectComparison> all = new ArrayList<>(addComparisons);
        all.addAll(dropComparisons);
        return selection(bundle, SelectionState.UNIQUE_ADD_DROP_SELECTED,
            add, drop, supportedDrops.stream().map(SleeperLiveWaiverComparisonExecutionBundle.RosterEntry::sleeperPlayerId).toList(),
            List.copyOf(all));
    }

    private DirectComparison comparePlayers(
        String leftButlerId, String leftSleeperId,
        String rightButlerId, String rightSleeperId,
        Map<String, Double> scoring) throws SQLException {
        Map<String, PlayerSeasonProduction> leftRows = latest2025BySource(productionSource.load(leftButlerId));
        Map<String, PlayerSeasonProduction> rightRows = latest2025BySource(productionSource.load(rightButlerId));
        Set<String> common = new TreeSet<>(leftRows.keySet());
        common.retainAll(rightRows.keySet());
        if (common.isEmpty()) {
            return new DirectComparison(leftSleeperId, rightSleeperId, List.of(),
                List.of(), DirectDirection.NO_COMMON_SOURCE);
        }

        Map<String, Double> leftPerGame = new LinkedHashMap<>();
        Map<String, Double> rightPerGame = new LinkedHashMap<>();
        List<DirectSourceComparison> details = new ArrayList<>();
        boolean unresolved = false;
        for (String source : common) {
            PlayerSeasonProduction leftRow = leftRows.get(source);
            PlayerSeasonProduction rightRow = rightRows.get(source);
            var leftSubtotal = SleeperLiveWaiverCandidateRosterComparisonMethodology.supportedSubtotal(leftRow, scoring);
            var rightSubtotal = SleeperLiveWaiverCandidateRosterComparisonMethodology.supportedSubtotal(rightRow, scoring);
            String state;
            if (leftSubtotal.supportedSubtotalPerGame() == null || rightSubtotal.supportedSubtotalPerGame() == null) {
                unresolved = true;
                state = "NONCOMPARABLE_GAMES_PLAYED";
            } else if (!leftSubtotal.includedScoringKeys().equals(rightSubtotal.includedScoringKeys())) {
                unresolved = true;
                state = "SCHEMA_SUPPORT_MISMATCH";
            } else {
                state = "COMPARABLE_COMMON_SOURCE";
                leftPerGame.put(source, leftSubtotal.supportedSubtotalPerGame());
                rightPerGame.put(source, rightSubtotal.supportedSubtotalPerGame());
            }
            details.add(new DirectSourceComparison(
                source, leftRow.asOfDate(), rightRow.asOfDate(),
                leftSubtotal.supportedSubtotalPerGame(), rightSubtotal.supportedSubtotalPerGame(),
                leftSubtotal.includedScoringKeys(), rightSubtotal.includedScoringKeys(), state));
        }
        DirectDirection direction;
        if (unresolved) {
            direction = DirectDirection.SOURCE_DIRECTION_UNRESOLVED;
        } else {
            direction = switch (SleeperLiveWaiverCandidateRosterComparisonMethodology
                .directionAcrossCommonSources(leftPerGame, rightPerGame)) {
                case CANDIDATE_DIRECTIONALLY_SUPPORTED -> DirectDirection.LEFT_DIRECTIONALLY_SUPPORTED;
                case ROSTER_DIRECTIONALLY_SUPPORTED -> DirectDirection.RIGHT_DIRECTIONALLY_SUPPORTED;
                case TIED_ALL_COMMON_SOURCES -> DirectDirection.TIED_ALL_COMMON_SOURCES;
                case SOURCE_DIRECTION_UNRESOLVED -> DirectDirection.SOURCE_DIRECTION_UNRESOLVED;
                case NO_COMMON_SOURCE -> DirectDirection.NO_COMMON_SOURCE;
            };
        }
        return new DirectComparison(leftSleeperId, rightSleeperId, List.copyOf(common), List.copyOf(details), direction);
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
        boolean backedPair = bundle.comparisons().pairs().stream().anyMatch(value ->
            value.candidate().sleeperPlayerId().equals(add.sleeperPlayerId())
                && value.roster().sleeperPlayerId().equals(drop.sleeperPlayerId())
                && value.state() == SleeperLiveWaiverComparisonExecutionBundle.PairState.CANDIDATE_DIRECTIONALLY_SUPPORTED);
        if (!backedPair) {
            throw new IllegalStateException("BF-620 BLOCKED: selected add/drop lacks exact BF-615 candidate-supported pair evidence");
        }
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

    private void validateSelectedPairAgainstReplayAndSnapshot(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        ReplayFreshnessFrame freshness,
        SelectedPlayer add,
        SelectedPlayer drop) throws SQLException {
        boolean dropReplay = freshness.targetPlayers().stream().anyMatch(value ->
            value.sleeperPlayerId().equals(drop.sleeperPlayerId())
                && SleeperLiveWaiverCandidateRosterComparisonMethodology.eligibleReplacementSlot(value.rosterSlot()));
        if (!dropReplay) {
            throw new IllegalStateException("BF-659 BLOCKED: selected drop is not on reconstructed target BENCH/RESERVE roster");
        }
        if (freshness.targetPlayers().stream().anyMatch(value -> value.sleeperPlayerId().equals(add.sleeperPlayerId()))) {
            throw new IllegalStateException("BF-659 BLOCKED: selected add appears on reconstructed pre-transaction target roster");
        }

        List<LiveWaiverSnapshotRepository.Entry> entries = snapshotEntriesSource.load(bundle.comparisons().waiverSnapshotId());
        Map<String, LiveWaiverSnapshotRepository.Entry> byId = new LinkedHashMap<>();
        for (var entry : entries) {
            if (byId.putIfAbsent(entry.sleeperPlayerId(), entry) != null) {
                throw new IllegalStateException("BF-659 BLOCKED: duplicate BF-602 snapshot identity " + entry.sleeperPlayerId());
            }
        }
        var addSnapshot = byId.get(add.sleeperPlayerId());
        var dropSnapshot = byId.get(drop.sleeperPlayerId());
        if (addSnapshot == null || !addSnapshot.freeAgent() || addSnapshot.rostered() || !addSnapshot.leagueEligible()) {
            throw new IllegalStateException("BF-659 BLOCKED: selected add was not an exact league-eligible free agent in audited BF-602 snapshot");
        }
        if (dropSnapshot == null || !dropSnapshot.rostered() || dropSnapshot.freeAgent()) {
            throw new IllegalStateException("BF-659 BLOCKED: selected drop was not rostered in audited BF-602 snapshot");
        }
        boolean historicalShortlist = bundle.shortlist().shortlist().stream().anyMatch(value ->
            value.lane() == SleeperLiveWaiverComparisonExecutionBundle.ShortlistLane.HISTORICAL_DIRECTIONAL
                && value.candidate().sleeperPlayerId().equals(add.sleeperPlayerId()));
        if (!historicalShortlist) {
            throw new IllegalStateException("BF-659 BLOCKED: selected add is absent from replayed BF-616 historical shortlist");
        }
        boolean backedPair = bundle.comparisons().pairs().stream().anyMatch(value ->
            value.candidate().sleeperPlayerId().equals(add.sleeperPlayerId())
                && value.roster().sleeperPlayerId().equals(drop.sleeperPlayerId())
                && value.state() == SleeperLiveWaiverComparisonExecutionBundle.PairState.CANDIDATE_DIRECTIONALLY_SUPPORTED);
        if (!backedPair) {
            throw new IllegalStateException("BF-659 BLOCKED: selected add/drop lacks replayed BF-615 candidate-supported pair evidence");
        }
    }

    private static void validateReplayFreshness(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        ReplayFreshnessFrame freshness,
        String leagueId,
        String ownerId) {
        Objects.requireNonNull(freshness, "BF-659 replay freshness must not be null");
        if (!bundle.comparisons().marketSnapshotId().equals(freshness.marketSnapshotId())
            || !bundle.comparisons().waiverSnapshotId().equals(freshness.waiverSnapshotId())
            || !bundle.comparisons().sleeperLeagueId().equals(freshness.sleeperLeagueId())
            || bundle.comparisons().rosterId() != freshness.rosterId()) {
            throw new IllegalStateException("BF-659 BLOCKED: reconstructed freshness lineage differs from BF-615-617 replay frame");
        }
        if (freshness.providerSeason() != 2026 || !"in_season".equals(freshness.providerStatus())) {
            throw new IllegalStateException("BF-659 BLOCKED: reconstructed freshness frame is not in-season 2026");
        }
        if (freshness.targetPlayers().isEmpty()) {
            throw new IllegalStateException("BF-659 BLOCKED: reconstructed target roster is empty");
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

    record ReplayTargetPlayer(String sleeperPlayerId, String rosterSlot) {
    ReplayTargetPlayer {
        sleeperPlayerId = requireText(sleeperPlayerId, "replay Sleeper player id");
        rosterSlot = requireText(rosterSlot, "replay roster slot");
    }
}

    record ReplayFreshnessFrame(
        String marketSnapshotId,
        String waiverSnapshotId,
        String sleeperLeagueId,
        int rosterId,
        int providerSeason,
        String providerStatus,
        Integer providerLeg,
        List<ReplayTargetPlayer> targetPlayers) {
        ReplayFreshnessFrame {
            marketSnapshotId = requireText(marketSnapshotId, "replay market snapshot id");
            waiverSnapshotId = requireText(waiverSnapshotId, "replay waiver snapshot id");
            sleeperLeagueId = requireText(sleeperLeagueId, "replay Sleeper league id");
            providerStatus = requireText(providerStatus, "replay provider status");
            if (rosterId <= 0) throw new IllegalArgumentException("replay roster id must be positive");
            targetPlayers = List.copyOf(Objects.requireNonNull(targetPlayers, "replay target players must not be null"));
        }
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
