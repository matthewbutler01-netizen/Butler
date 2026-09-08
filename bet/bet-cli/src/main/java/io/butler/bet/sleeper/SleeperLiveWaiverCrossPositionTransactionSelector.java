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
import java.util.TreeMap;
import java.util.TreeSet;

/** BF-624 governed cross-position selection over complete add/drop transaction improvement. */
final class SleeperLiveWaiverCrossPositionTransactionSelector {
    private static final int PRODUCTION_SEASON = 2025;

    private final SleeperLiveWaiverFinalRecommendationBundle.ProductionSource productionSource;

    SleeperLiveWaiverCrossPositionTransactionSelector(
        SleeperLiveWaiverFinalRecommendationBundle.ProductionSource productionSource) {
        this.productionSource = Objects.requireNonNull(productionSource, "productionSource must not be null");
    }

    Result select(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        List<SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry> historical) throws SQLException {
        Objects.requireNonNull(bundle, "bundle must not be null");
        Objects.requireNonNull(historical, "historical finalists must not be null");

        Map<String, List<SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry>> byPosition = new TreeMap<>();
        for (var entry : historical) {
            String position = requireText(entry.candidate().position(), "historical finalist position").toUpperCase();
            byPosition.computeIfAbsent(position, ignored -> new ArrayList<>()).add(entry);
        }
        if (byPosition.size() < 2) {
            throw new IllegalArgumentException("BF-624 requires historical finalists from at least two positions");
        }

        List<TransactionOption> options = new ArrayList<>();
        List<SleeperLiveWaiverFinalRecommendationBundle.DirectComparison> directComparisons = new ArrayList<>();
        for (var group : byPosition.entrySet()) {
            PositionResult position = selectPosition(bundle, group.getKey(), List.copyOf(group.getValue()));
            directComparisons.addAll(position.directComparisons());
            if (position.option() != null) options.add(position.option());
        }

        options.sort(Comparator.comparing(value -> value.add().sleeperPlayerId()));
        if (options.isEmpty()) {
            return new Result(
                SleeperLiveWaiverFinalRecommendationBundle.SelectionState.CROSS_POSITION_NO_ACTIONABLE_TRANSACTION,
                null, null, List.of(), List.copyOf(directComparisons));
        }
        if (options.size() == 1) {
            TransactionOption only = options.get(0);
            return new Result(
                SleeperLiveWaiverFinalRecommendationBundle.SelectionState.UNIQUE_ADD_DROP_SELECTED,
                only.add(), only.drop(), List.copyOf(options), List.copyOf(directComparisons));
        }

        for (int left = 0; left < options.size(); left++) {
            for (int right = left + 1; right < options.size(); right++) {
                if (!compatible(options.get(left), options.get(right))) {
                    return new Result(
                        SleeperLiveWaiverFinalRecommendationBundle.SelectionState.CROSS_POSITION_TRANSACTION_EVIDENCE_INCOMPATIBLE,
                        null, null, List.copyOf(options), List.copyOf(directComparisons));
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
                null, null, List.copyOf(options), List.copyOf(directComparisons));
        }

        TransactionOption winner = winners.get(0);
        return new Result(
            SleeperLiveWaiverFinalRecommendationBundle.SelectionState.UNIQUE_ADD_DROP_SELECTED,
            winner.add(), winner.drop(), List.copyOf(options), List.copyOf(directComparisons));
    }

    private PositionResult selectPosition(
        SleeperLiveWaiverComparisonExecutionBundle.BundleReport bundle,
        String position,
        List<SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry> finalists) throws SQLException {
        List<SleeperLiveWaiverComparisonExecutionBundle.ShortlistEntry> addWinners = new ArrayList<>();
        List<SleeperLiveWaiverFinalRecommendationBundle.DirectComparison> comparisons = new ArrayList<>();
        for (var left : finalists) {
            boolean dominatesAll = true;
            for (var right : finalists) {
                if (left == right) continue;
                var comparison = comparePlayers(
                    left.candidate().butlerPlayerId(), left.candidate().sleeperPlayerId(),
                    right.candidate().butlerPlayerId(), right.candidate().sleeperPlayerId(),
                    bundle.methodology().exactLeagueScoringSettings());
                comparisons.add(comparison);
                if (comparison.direction()
                    != SleeperLiveWaiverFinalRecommendationBundle.DirectDirection.LEFT_DIRECTIONALLY_SUPPORTED) {
                    dominatesAll = false;
                }
            }
            if (dominatesAll) addWinners.add(left);
        }
        if (addWinners.size() != 1) return new PositionResult(position, null, List.copyOf(comparisons));

        var addEntry = addWinners.get(0);
        var add = candidate(addEntry.candidate());
        var candidateExecution = bundle.comparisons().candidates().stream()
            .filter(value -> value.candidate().sleeperPlayerId().equals(add.sleeperPlayerId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "BF-624 BLOCKED: position-selected add is absent from BF-615 candidate execution"));

        List<SleeperLiveWaiverComparisonExecutionBundle.RosterEntry> supportedDrops = candidateExecution.pairs().stream()
            .filter(value -> value.state()
                == SleeperLiveWaiverComparisonExecutionBundle.PairState.CANDIDATE_DIRECTIONALLY_SUPPORTED)
            .map(SleeperLiveWaiverComparisonExecutionBundle.PairComparison::roster)
            .filter(value -> SleeperLiveWaiverCandidateRosterComparisonMethodology.eligibleReplacementSlot(value.rosterSlot()))
            .filter(SleeperLiveWaiverComparisonExecutionBundle.RosterEntry::priorProductionPresent)
            .distinct()
            .sorted(Comparator.comparing(SleeperLiveWaiverComparisonExecutionBundle.RosterEntry::sleeperPlayerId))
            .toList();
        if (supportedDrops.isEmpty()) return new PositionResult(position, null, List.copyOf(comparisons));

        List<SleeperLiveWaiverComparisonExecutionBundle.RosterEntry> dropWinners = new ArrayList<>();
        if (supportedDrops.size() == 1) {
            dropWinners.add(supportedDrops.get(0));
        } else {
            for (var left : supportedDrops) {
                boolean weakerThanAll = true;
                for (var right : supportedDrops) {
                    if (left == right) continue;
                    var comparison = comparePlayers(
                        left.butlerPlayerId(), left.sleeperPlayerId(),
                        right.butlerPlayerId(), right.sleeperPlayerId(),
                        bundle.methodology().exactLeagueScoringSettings());
                    comparisons.add(comparison);
                    if (comparison.direction()
                        != SleeperLiveWaiverFinalRecommendationBundle.DirectDirection.RIGHT_DIRECTIONALLY_SUPPORTED) {
                        weakerThanAll = false;
                    }
                }
                if (weakerThanAll) dropWinners.add(left);
            }
        }
        if (dropWinners.size() != 1) return new PositionResult(position, null, List.copyOf(comparisons));

        TransactionOption option = transactionOption(
            addEntry.candidate(), dropWinners.get(0), bundle.methodology().exactLeagueScoringSettings());
        if (option == null) return new PositionResult(position, null, List.copyOf(comparisons));
        return new PositionResult(position, option, List.copyOf(comparisons));
    }

    private TransactionOption transactionOption(
        SleeperLiveWaiverComparisonExecutionBundle.CandidateEntry add,
        SleeperLiveWaiverComparisonExecutionBundle.RosterEntry drop,
        Map<String, Double> scoring) throws SQLException {
        Map<String, PlayerSeasonProduction> addRows = latest2025BySource(productionSource.load(add.butlerPlayerId()));
        Map<String, PlayerSeasonProduction> dropRows = latest2025BySource(productionSource.load(drop.butlerPlayerId()));
        Set<String> common = new TreeSet<>(addRows.keySet());
        common.retainAll(dropRows.keySet());
        if (common.isEmpty()) return null;

        Map<String, Double> improvementBySource = new LinkedHashMap<>();
        Map<String, List<String>> scoringKeysBySource = new LinkedHashMap<>();
        for (String source : common) {
            var addSubtotal = SleeperLiveWaiverCandidateRosterComparisonMethodology
                .supportedSubtotal(addRows.get(source), scoring);
            var dropSubtotal = SleeperLiveWaiverCandidateRosterComparisonMethodology
                .supportedSubtotal(dropRows.get(source), scoring);
            Double addPerGame = addSubtotal.supportedSubtotalPerGame();
            Double dropPerGame = dropSubtotal.supportedSubtotalPerGame();
            if (addPerGame == null || dropPerGame == null) return null;
            if (!addSubtotal.includedScoringKeys().equals(dropSubtotal.includedScoringKeys())) return null;
            double improvement = addPerGame - dropPerGame;
            if (!(improvement > 0.0d)) return null;
            improvementBySource.put(source, improvement);
            scoringKeysBySource.put(source, List.copyOf(addSubtotal.includedScoringKeys()));
        }
        return new TransactionOption(
            candidate(add), roster(drop),
            Collections.unmodifiableMap(new LinkedHashMap<>(improvementBySource)),
            immutableKeyMap(scoringKeysBySource));
    }

    private SleeperLiveWaiverFinalRecommendationBundle.DirectComparison comparePlayers(
        String leftButlerId,
        String leftSleeperId,
        String rightButlerId,
        String rightSleeperId,
        Map<String, Double> scoring) throws SQLException {
        Map<String, PlayerSeasonProduction> leftRows = latest2025BySource(productionSource.load(leftButlerId));
        Map<String, PlayerSeasonProduction> rightRows = latest2025BySource(productionSource.load(rightButlerId));
        Set<String> common = new TreeSet<>(leftRows.keySet());
        common.retainAll(rightRows.keySet());
        if (common.isEmpty()) {
            return new SleeperLiveWaiverFinalRecommendationBundle.DirectComparison(
                leftSleeperId, rightSleeperId, List.of(), List.of(),
                SleeperLiveWaiverFinalRecommendationBundle.DirectDirection.NO_COMMON_SOURCE);
        }

        Map<String, Double> leftPerGame = new LinkedHashMap<>();
        Map<String, Double> rightPerGame = new LinkedHashMap<>();
        List<SleeperLiveWaiverFinalRecommendationBundle.DirectSourceComparison> details = new ArrayList<>();
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
            details.add(new SleeperLiveWaiverFinalRecommendationBundle.DirectSourceComparison(
                source, leftRow.asOfDate(), rightRow.asOfDate(),
                leftSubtotal.supportedSubtotalPerGame(), rightSubtotal.supportedSubtotalPerGame(),
                leftSubtotal.includedScoringKeys(), rightSubtotal.includedScoringKeys(), state));
        }

        SleeperLiveWaiverFinalRecommendationBundle.DirectDirection direction;
        if (unresolved) {
            direction = SleeperLiveWaiverFinalRecommendationBundle.DirectDirection.SOURCE_DIRECTION_UNRESOLVED;
        } else {
            direction = switch (SleeperLiveWaiverCandidateRosterComparisonMethodology
                .directionAcrossCommonSources(leftPerGame, rightPerGame)) {
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
        return new SleeperLiveWaiverFinalRecommendationBundle.DirectComparison(
            leftSleeperId, rightSleeperId, List.copyOf(common), List.copyOf(details), direction);
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
        latest.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
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
        SleeperLiveWaiverComparisonExecutionBundle.RosterEntry value) {
        return new SleeperLiveWaiverFinalRecommendationBundle.SelectedPlayer(
            value.sleeperPlayerId(), value.displayName(), value.position(), value.rosterSlot(),
            null, null, null, null, null);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    record PositionResult(
        String position,
        TransactionOption option,
        List<SleeperLiveWaiverFinalRecommendationBundle.DirectComparison> directComparisons) {
        PositionResult {
            directComparisons = List.copyOf(directComparisons);
        }
    }

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
