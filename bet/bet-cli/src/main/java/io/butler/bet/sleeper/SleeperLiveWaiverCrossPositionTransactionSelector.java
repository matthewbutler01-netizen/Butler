package io.butler.bet.sleeper;

import io.butler.bet.domain.PlayerSeasonProduction;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * BF-624/BF-903 governed selection over complete add/drop transaction improvement.
 *
 * <p>BF-903 keeps BF-616's exact-position evidence lane as the admission gate for historical
 * waiver candidates, then evaluates every admitted historical candidate against every exact live
 * BENCH/RESERVE roster player with compatible persisted production evidence. This makes the final
 * decision transaction-first: the drop does not need to share the add's position.</p>
 */
final class SleeperLiveWaiverCrossPositionTransactionSelector {
    private static final int PRODUCTION_SEASON = 2025;

    private final SleeperLiveWaiverFinalRecommendationBundle.ProductionSource productionSource;

    SleeperLiveWaiverCrossPositionTransactionSelector(
        SleeperLiveWaiverFinalRecommendationBundle.ProductionSource productionSource) {
        this.productionSource = Objects.requireNonNull(productionSource, "productionSource must not be null");
    }

    Result select(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        List<SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry> historical,
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport freshness) throws SQLException {
        Objects.requireNonNull(bundle, "bundle must not be null");
        Objects.requireNonNull(historical, "historical finalists must not be null");
        Objects.requireNonNull(freshness, "live target roster must not be null");
        validateFreshnessLineage(bundle, freshness);

        List<SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer> drops = freshness.targetPlayers().stream()
            .filter(value -> SleeperLiveWaiverCandidateRosterComparisonMethodology
                .eligibleReplacementSlot(value.rosterSlot()))
            .filter(value -> value.butlerPlayerId() != null && !value.butlerPlayerId().isBlank())
            .filter(value -> value.mappingState() != null
                && value.mappingState().trim().equalsIgnoreCase("EXACT_CANONICAL"))
            .sorted(Comparator.comparing(SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer::sleeperPlayerId))
            .toList();

        if (historical.isEmpty() || drops.isEmpty()) {
            return new Result(
                SleeperLiveWaiverFinalRecommendationBundle.SelectionState.CROSS_POSITION_NO_ACTIONABLE_TRANSACTION,
                null, null, List.of(), List.of());
        }

        Map<String, Map<String, PlayerSeasonProduction>> production = new LinkedHashMap<>();
        for (var add : historical) {
            String butlerId = add.candidate().butlerPlayerId();
            if (!production.containsKey(butlerId)) {
                production.put(butlerId, latest2025BySource(productionSource.load(butlerId)));
            }
        }
        for (var drop : drops) {
            String butlerId = drop.butlerPlayerId();
            if (!production.containsKey(butlerId)) {
                production.put(butlerId, latest2025BySource(productionSource.load(butlerId)));
            }
        }

        List<TransactionOption> options = new ArrayList<>();
        List<SleeperLiveWaiverFinalRecommendationBundle.DirectComparison> comparisons = new ArrayList<>();

        for (var addEntry : historical) {
            var add = addEntry.candidate();
            Map<String, PlayerSeasonProduction> addRows =
                production.getOrDefault(add.butlerPlayerId(), Map.of());

            for (var drop : drops) {
                Map<String, PlayerSeasonProduction> dropRows =
                    production.getOrDefault(drop.butlerPlayerId(), Map.of());
                PairEvaluation evaluation = evaluate(
                    add, drop, addRows, dropRows, bundle.methodology().exactLeagueScoringSettings());
                comparisons.add(evaluation.comparison());
                if (evaluation.option() != null) options.add(evaluation.option());
            }
        }

        options.sort(Comparator
            .comparing((TransactionOption value) -> value.add().sleeperPlayerId())
            .thenComparing(value -> value.drop().sleeperPlayerId()));

        if (options.isEmpty()) {
            return new Result(
                SleeperLiveWaiverFinalRecommendationBundle.SelectionState.CROSS_POSITION_NO_ACTIONABLE_TRANSACTION,
                null, null, List.of(), List.copyOf(comparisons));
        }

        for (int left = 0; left < options.size(); left++) {
            for (int right = left + 1; right < options.size(); right++) {
                if (!compatible(options.get(left), options.get(right))) {
                    return new Result(
                        SleeperLiveWaiverFinalRecommendationBundle.SelectionState.CROSS_POSITION_TRANSACTION_EVIDENCE_INCOMPATIBLE,
                        null, null, List.copyOf(options), List.copyOf(comparisons));
                }
            }
        }

        List<TransactionOption> winners = new ArrayList<>();
        for (TransactionOption left : options) {
            boolean dominatesAll = true;
            for (TransactionOption right : options) {
                if (left == right) continue;
                if (!strictlyGreaterOnEverySource(left, right)) {
                    dominatesAll = false;
                    break;
                }
            }
            if (dominatesAll) winners.add(left);
        }

        if (winners.size() != 1) {
            return new Result(
                SleeperLiveWaiverFinalRecommendationBundle.SelectionState.CROSS_POSITION_TRANSACTION_IMPROVEMENT_UNRESOLVED,
                null, null, List.copyOf(options), List.copyOf(comparisons));
        }

        TransactionOption winner = winners.get(0);
        return new Result(
            SleeperLiveWaiverFinalRecommendationBundle.SelectionState.UNIQUE_ADD_DROP_SELECTED,
            winner.add(), winner.drop(), List.copyOf(options), List.copyOf(comparisons));
    }

    private PairEvaluation evaluate(
        SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry add,
        SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer drop,
        Map<String, PlayerSeasonProduction> addRows,
        Map<String, PlayerSeasonProduction> dropRows,
        Map<String, Double> scoring) {
        Set<String> common = new TreeSet<>(addRows.keySet());
        common.retainAll(dropRows.keySet());
        if (common.isEmpty()) {
            return new PairEvaluation(
                new SleeperLiveWaiverFinalRecommendationBundle.DirectComparison(
                    add.sleeperPlayerId(), drop.sleeperPlayerId(), List.of(), List.of(),
                    SleeperLiveWaiverFinalRecommendationBundle.DirectDirection.NO_COMMON_SOURCE),
                null);
        }

        Map<String, Double> addPerGame = new LinkedHashMap<>();
        Map<String, Double> dropPerGame = new LinkedHashMap<>();
        Map<String, Double> improvementBySource = new LinkedHashMap<>();
        Map<String, List<String>> scoringKeysBySource = new LinkedHashMap<>();
        List<SleeperLiveWaiverFinalRecommendationBundle.DirectSourceComparison> details = new ArrayList<>();
        boolean unresolved = false;

        for (String source : common) {
            PlayerSeasonProduction addRow = addRows.get(source);
            PlayerSeasonProduction dropRow = dropRows.get(source);
            var addSubtotal = SleeperLiveWaiverCandidateRosterComparisonMethodology
                .supportedSubtotal(addRow, scoring);
            var dropSubtotal = SleeperLiveWaiverCandidateRosterComparisonMethodology
                .supportedSubtotal(dropRow, scoring);

            String state;
            if (addSubtotal.supportedSubtotalPerGame() == null
                || dropSubtotal.supportedSubtotalPerGame() == null) {
                unresolved = true;
                state = "NONCOMPARABLE_GAMES_PLAYED";
            } else if (!addSubtotal.includedScoringKeys().equals(dropSubtotal.includedScoringKeys())) {
                unresolved = true;
                state = "SCHEMA_SUPPORT_MISMATCH";
            } else {
                state = "COMPARABLE_COMMON_SOURCE";
                double addValue = addSubtotal.supportedSubtotalPerGame();
                double dropValue = dropSubtotal.supportedSubtotalPerGame();
                addPerGame.put(source, addValue);
                dropPerGame.put(source, dropValue);
                improvementBySource.put(source, addValue - dropValue);
                scoringKeysBySource.put(source, List.copyOf(addSubtotal.includedScoringKeys()));
            }

            details.add(new SleeperLiveWaiverFinalRecommendationBundle.DirectSourceComparison(
                source,
                addRow.asOfDate(),
                dropRow.asOfDate(),
                addSubtotal.supportedSubtotalPerGame(),
                dropSubtotal.supportedSubtotalPerGame(),
                addSubtotal.includedScoringKeys(),
                dropSubtotal.includedScoringKeys(),
                state));
        }

        SleeperLiveWaiverFinalRecommendationBundle.DirectDirection direction;
        if (unresolved) {
            direction = SleeperLiveWaiverFinalRecommendationBundle.DirectDirection.SOURCE_DIRECTION_UNRESOLVED;
        } else {
            direction = switch (SleeperLiveWaiverCandidateRosterComparisonMethodology
                .directionAcrossCommonSources(addPerGame, dropPerGame)) {
                case CANDIDATE_DIRECTIONALLY_SUPPORTED ->
                    SleeperLiveWaiverFinalRecommendationBundle.DirectDirection.LEFT_DIRECTIONALLY_SUPPORTED;
                case ROSTER_DIRECTIONALLY_SUPPORTED ->
                    SleeperLiveWaiverFinalRecommendationBundle.DirectDirection.RIGHT_DIRECTIONALLY_SUPPORTED;
                case TIED_ALL_COMMON_SOURCES ->
                    SleeperLiveWaiverFinalRecommendationBundle.DirectDirection.TIED_ALL_COMMON_SOURCES;
                case SOURCE_DIRECTION_UNRESOLVED ->
                    SleeperLiveWaiverFinalRecommendationBundle.DirectDirection.SOURCE_DIRECTION_UNRESOLVED;
                case NO_COMMON_SOURCE ->
                    SleeperLiveWaiverFinalRecommendationBundle.DirectDirection.NO_COMMON_SOURCE;
            };
        }

        var comparison = new SleeperLiveWaiverFinalRecommendationBundle.DirectComparison(
            add.sleeperPlayerId(),
            drop.sleeperPlayerId(),
            List.copyOf(common),
            List.copyOf(details),
            direction);

        if (direction != SleeperLiveWaiverFinalRecommendationBundle.DirectDirection.LEFT_DIRECTIONALLY_SUPPORTED) {
            return new PairEvaluation(comparison, null);
        }

        for (double improvement : improvementBySource.values()) {
            if (!(improvement > 0.0d)) {
                return new PairEvaluation(comparison, null);
            }
        }

        return new PairEvaluation(
            comparison,
            new TransactionOption(
                candidate(add),
                roster(drop),
                Collections.unmodifiableMap(new LinkedHashMap<>(improvementBySource)),
                immutableKeyMap(scoringKeysBySource)));
    }

    private static void validateFreshnessLineage(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport freshness) {
        if (!bundle.comparisons().leagueId().equals(freshness.leagueId())
            || !bundle.comparisons().sleeperOwnerId().equals(freshness.sleeperOwnerId())
            || !bundle.comparisons().marketSnapshotId().equals(freshness.marketSnapshotId())
            || !bundle.comparisons().waiverSnapshotId().equals(freshness.waiverSnapshotId())
            || !bundle.comparisons().sleeperLeagueId().equals(freshness.sleeperLeagueId())
            || bundle.comparisons().rosterId() != freshness.rosterId()) {
            throw new IllegalStateException(
                "BF-903 BLOCKED: transaction-first live-roster lineage differs from BF-615/BF-616");
        }
    }

    private static boolean compatible(TransactionOption left, TransactionOption right) {
        if (!left.improvementBySource().keySet().equals(right.improvementBySource().keySet())) return false;
        for (String source : left.improvementBySource().keySet()) {
            if (!Objects.equals(left.scoringKeysBySource().get(source), right.scoringKeysBySource().get(source))) {
                return false;
            }
        }
        return true;
    }

    private static boolean strictlyGreaterOnEverySource(TransactionOption left, TransactionOption right) {
        for (String source : left.improvementBySource().keySet()) {
            if (!(left.improvementBySource().get(source) > right.improvementBySource().get(source))) return false;
        }
        return true;
    }

    private static Map<String, List<String>> immutableKeyMap(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(result);
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
        latest.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(sorted);
    }

    private static SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer candidate(
        SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry value) {
        return new SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer(
            value.sleeperPlayerId(), value.displayName(), value.position(), "WAIVER_CANDIDATE",
            value.currentTeam(), value.currentStatus(), value.injuryStatus(),
            value.depthChartPosition(), value.depthChartOrder());
    }

    private static SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer roster(
        SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer value) {
        return new SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer(
            value.sleeperPlayerId(), value.displayName(), value.position(), value.rosterSlot(),
            null, null, null, null, null);
    }

    private record PairEvaluation(
        SleeperLiveWaiverFinalRecommendationBundle.DirectComparison comparison,
        TransactionOption option) {}

    record TransactionOption(
        SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer add,
        SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer drop,
        Map<String, Double> improvementBySource,
        Map<String, List<String>> scoringKeysBySource) {
        TransactionOption {
            Objects.requireNonNull(add);
            Objects.requireNonNull(drop);
            improvementBySource = Collections.unmodifiableMap(new LinkedHashMap<>(improvementBySource));
            scoringKeysBySource = immutableKeyMap(scoringKeysBySource);
        }
    }

    record Result(
        SleeperLiveWaiverFinalRecommendationBundle.SelectionState state,
        SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer selectedAdd,
        SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer selectedDrop,
        List<TransactionOption> options,
        List<SleeperLiveWaiverFinalRecommendationBundle.DirectComparison> directComparisons) {
        Result {
            Objects.requireNonNull(state);
            options = List.copyOf(options);
            directComparisons = List.copyOf(directComparisons);
        }
    }


}
