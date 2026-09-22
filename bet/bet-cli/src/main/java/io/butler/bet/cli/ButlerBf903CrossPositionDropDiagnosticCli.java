package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerSeasonProductionRepository;
import io.butler.bet.domain.PlayerSeasonProduction;
import io.butler.bet.sleeper.SleeperLiveWaiverCandidateRosterComparisonMethodology;
import io.butler.bet.sleeper.SleeperLiveWaiverComparisonExecutionBundle;
import io.butler.bet.sleeper.SleeperLiveWaiverTargetRosterContextAudit;

import java.nio.file.Path;
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
 * BF-903 diagnostic-only proof for cross-position BENCH/RESERVE drop expansion.
 *
 * <p>This command does not change governed recommendation semantics. It reuses the current
 * BF-903 historical shortlist, exact live roster identity, persisted 2025 production, and
 * persisted league scoring settings to show which complete candidate/drop transactions would
 * remain positive if the drop were allowed to come from any BENCH/RESERVE position.</p>
 */
public final class ButlerBf903CrossPositionDropDiagnosticCli {
    private static final int PRODUCTION_SEASON = 2025;

    private ButlerBf903CrossPositionDropDiagnosticCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: ButlerBf903CrossPositionDropDiagnosticCli <butler-league-id>");
            }

            String leagueId = args[0].trim();
            Database database = new Database(Path.of("butler.db"));
            database.initialize();

            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            ButlerPersonalizedTargetCliSupport.printVerified(target);

            var bundle = new SleeperLiveWaiverComparisonExecutionBundle(database)
                .run(leagueId, target.sleeperUserId());
            var roster = new SleeperLiveWaiverTargetRosterContextAudit(database)
                .audit(leagueId, target.sleeperUserId());

            if (!bundle.comparisons().marketSnapshotId().equals(roster.marketSnapshotId())
                || !bundle.comparisons().waiverSnapshotId().equals(roster.waiverSnapshotId())
                || bundle.comparisons().rosterId() != roster.rosterId()) {
                throw new IllegalStateException(
                    "BF-903 BLOCKED: comparison and live-roster lineage do not reconcile");
            }

            var historical = bundle.shortlist().shortlist().stream()
                .filter(value -> value.lane()
                    == SleeperLiveWaiverComparisonExecutionBundle.ShortlistLane.HISTORICAL_DIRECTIONAL)
                .sorted(Comparator.comparing(value -> value.candidate().sleeperPlayerId()))
                .toList();

            var drops = roster.targetPlayers().stream()
                .filter(value -> SleeperLiveWaiverCandidateRosterComparisonMethodology
                    .eligibleReplacementSlot(value.rosterSlot()))
                .filter(value -> value.butlerPlayerId() != null && !value.butlerPlayerId().isBlank())
                .sorted(Comparator.comparing(SleeperLiveWaiverTargetRosterContextAudit.TargetPlayer::sleeperPlayerId))
                .toList();

            PlayerSeasonProductionRepository production = new PlayerSeasonProductionRepository(database);
            List<Option> options = new ArrayList<>();
            for (var addEntry : historical) {
                var add = addEntry.candidate();
                for (var drop : drops) {
                    Option option = option(
                        add.sleeperPlayerId(), add.displayName(), add.position(), add.butlerPlayerId(),
                        drop.sleeperPlayerId(), drop.displayName(), drop.position(), drop.butlerPlayerId(),
                        bundle.methodology().exactLeagueScoringSettings(), production);
                    if (option != null) options.add(option);
                }
            }

            options.sort(Comparator
                .comparing(Option::addSleeperId)
                .thenComparing(Option::dropSleeperId));

            System.out.println();
            System.out.println("BF-903 cross-position drop diagnostic");
            System.out.println("Boundary: DIAGNOSTIC_ONLY_READ_ONLY; governed recommendation semantics are unchanged.");
            System.out.println("Historical transaction candidates: " + historical.size());
            System.out.println("Live BENCH/RESERVE drop candidates: " + drops.size());
            System.out.println("Positive complete transactions across any drop position: " + options.size());

            for (Option option : options) {
                System.out.println("  ADD " + option.addName() + " (" + option.addPosition() + ", Sleeper "
                    + option.addSleeperId() + ") / DROP " + option.dropName() + " (" + option.dropPosition()
                    + ", Sleeper " + option.dropSleeperId() + ")");
                for (var source : option.improvementBySource().entrySet()) {
                    System.out.println("    source=" + source.getKey()
                        + " | transaction-improvement="
                        + String.format(java.util.Locale.ROOT, "%.4f", source.getValue())
                        + " supported-points/game"
                        + " | scoring-keys=" + option.scoringKeysBySource().get(source.getKey()));
                }
            }

            DiagnosticState state = state(options);
            System.out.println("Diagnostic state: " + state);
            if (state == DiagnosticState.UNIQUE_STRICT_TRANSACTION_WINNER) {
                Option winner = uniqueWinner(options);
                System.out.println("Diagnostic winner: ADD " + winner.addName() + " (Sleeper "
                    + winner.addSleeperId() + ") / DROP " + winner.dropName() + " (Sleeper "
                    + winner.dropSleeperId() + ")");
            } else {
                System.out.println("Diagnostic winner: none");
            }
            System.out.println("Boundary: this command does not refresh evidence, alter BF-620/BF-624, set FAAB, "
                + "write Butler state, or submit a Sleeper transaction.");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    private static Option option(
        String addSleeperId,
        String addName,
        String addPosition,
        String addButlerId,
        String dropSleeperId,
        String dropName,
        String dropPosition,
        String dropButlerId,
        Map<String, Double> scoring,
        PlayerSeasonProductionRepository production) throws SQLException {
        Map<String, PlayerSeasonProduction> addRows =
            latest2025BySource(production.findByPlayerId(addButlerId));
        Map<String, PlayerSeasonProduction> dropRows =
            latest2025BySource(production.findByPlayerId(dropButlerId));

        Set<String> common = new TreeSet<>(addRows.keySet());
        common.retainAll(dropRows.keySet());
        if (common.isEmpty()) return null;

        Map<String, Double> improvement = new LinkedHashMap<>();
        Map<String, List<String>> scoringKeys = new LinkedHashMap<>();
        for (String source : common) {
            var addSubtotal = SleeperLiveWaiverCandidateRosterComparisonMethodology
                .supportedSubtotal(addRows.get(source), scoring);
            var dropSubtotal = SleeperLiveWaiverCandidateRosterComparisonMethodology
                .supportedSubtotal(dropRows.get(source), scoring);
            if (addSubtotal.supportedSubtotalPerGame() == null
                || dropSubtotal.supportedSubtotalPerGame() == null
                || !addSubtotal.includedScoringKeys().equals(dropSubtotal.includedScoringKeys())) {
                return null;
            }
            double delta = addSubtotal.supportedSubtotalPerGame() - dropSubtotal.supportedSubtotalPerGame();
            if (!(delta > 0.0d)) return null;
            improvement.put(source, delta);
            scoringKeys.put(source, List.copyOf(addSubtotal.includedScoringKeys()));
        }

        return new Option(
            addSleeperId, addName, addPosition,
            dropSleeperId, dropName, dropPosition,
            Collections.unmodifiableMap(new LinkedHashMap<>(improvement)),
            immutableKeys(scoringKeys));
    }

    static DiagnosticState state(List<Option> options) {
        Objects.requireNonNull(options, "options must not be null");
        if (options.isEmpty()) return DiagnosticState.NO_POSITIVE_TRANSACTION;

        for (int left = 0; left < options.size(); left++) {
            for (int right = left + 1; right < options.size(); right++) {
                if (!compatible(options.get(left), options.get(right))) {
                    return DiagnosticState.INCOMPATIBLE_EVIDENCE;
                }
            }
        }

        return uniqueWinner(options) == null
            ? DiagnosticState.NO_UNIQUE_STRICT_TRANSACTION_WINNER
            : DiagnosticState.UNIQUE_STRICT_TRANSACTION_WINNER;
    }

    static Option uniqueWinner(List<Option> options) {
        List<Option> winners = new ArrayList<>();
        for (Option left : options) {
            boolean dominatesAll = true;
            for (Option right : options) {
                if (left == right) continue;
                if (!strictlyGreaterOnEverySource(left, right)) {
                    dominatesAll = false;
                    break;
                }
            }
            if (dominatesAll) winners.add(left);
        }
        return winners.size() == 1 ? winners.get(0) : null;
    }

    private static boolean compatible(Option left, Option right) {
        if (!left.improvementBySource().keySet().equals(right.improvementBySource().keySet())) return false;
        for (String source : left.improvementBySource().keySet()) {
            if (!Objects.equals(left.scoringKeysBySource().get(source), right.scoringKeysBySource().get(source))) {
                return false;
            }
        }
        return true;
    }

    private static boolean strictlyGreaterOnEverySource(Option left, Option right) {
        for (String source : left.improvementBySource().keySet()) {
            if (!(left.improvementBySource().get(source) > right.improvementBySource().get(source))) return false;
        }
        return true;
    }

    private static Map<String, List<String>> immutableKeys(Map<String, List<String>> source) {
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

    enum DiagnosticState {
        NO_POSITIVE_TRANSACTION,
        INCOMPATIBLE_EVIDENCE,
        NO_UNIQUE_STRICT_TRANSACTION_WINNER,
        UNIQUE_STRICT_TRANSACTION_WINNER
    }

    record Option(
        String addSleeperId,
        String addName,
        String addPosition,
        String dropSleeperId,
        String dropName,
        String dropPosition,
        Map<String, Double> improvementBySource,
        Map<String, List<String>> scoringKeysBySource) {
        Option {
            addSleeperId = requireText(addSleeperId, "addSleeperId");
            addName = requireText(addName, "addName");
            addPosition = requireText(addPosition, "addPosition");
            dropSleeperId = requireText(dropSleeperId, "dropSleeperId");
            dropName = requireText(dropName, "dropName");
            dropPosition = requireText(dropPosition, "dropPosition");
            improvementBySource = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(improvementBySource, "improvementBySource must not be null")));
            scoringKeysBySource = immutableKeys(
                Objects.requireNonNull(scoringKeysBySource, "scoringKeysBySource must not be null"));
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
