package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.integration.SleeperWeeklyProjectionProvider;
import io.butler.bet.sleeper.SleeperLiveWaiverCandidateRosterComparisonMethodology;
import io.butler.bet.sleeper.SleeperLiveWaiverComparisonExecutionBundle;
import io.butler.bet.sleeper.SleeperLiveWaiverTargetRosterContextAudit;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * BF-903 diagnostic-only probe for a current-week numeric lane for newcomer waiver candidates.
 *
 * <p>This command does not alter governed BF-618/BF-620 semantics. It uses the exact live
 * BF-610 roster, the exact BF-616 newcomer shortlist, persisted league scoring settings, and
 * Sleeper current-week projections to show whether newcomers and live BENCH/RESERVE drops have
 * comparable current evidence.</p>
 */
public final class ButlerBf903NewcomerProjectionDiagnosticCli {
    private ButlerBf903NewcomerProjectionDiagnosticCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: ButlerBf903NewcomerProjectionDiagnosticCli <butler-league-id>");
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
                    "BF-903 BLOCKED: newcomer projection diagnostic lineage differs from BF-615/BF-610");
            }
            if (roster.providerLeg() == null || roster.providerLeg() <= 0) {
                throw new IllegalStateException(
                    "BF-903 BLOCKED: current Sleeper scoring week is unavailable");
            }

            Map<String, Double> scoring = new LeagueScoringSettingsRepository(database)
                .findByLeagueId(leagueId);
            SleeperWeeklyProjectionProvider.ScoringBasis basis =
                SleeperWeeklyProjectionProvider.ScoringBasis.fromReceptionPoints(scoring.get("rec"));

            SleeperWeeklyProjectionProvider.ProjectionSnapshot snapshot =
                new SleeperWeeklyProjectionProvider().load(
                    roster.providerSeason(), roster.providerLeg(), basis, scoring);

            if (snapshot.season() != roster.providerSeason()
                || snapshot.week() != roster.providerLeg()
                || snapshot.scoring() != basis) {
                throw new IllegalStateException(
                    "BF-903 BLOCKED: weekly projection frame differs from exact live roster season/week/scoring");
            }

            Map<String, BigDecimal> projections = new LinkedHashMap<>();
            for (var projection : snapshot.projections()) {
                if (projections.putIfAbsent(
                    projection.sleeperPlayerId(), projection.projectedPoints()) != null) {
                    throw new IllegalStateException(
                        "BF-903 BLOCKED: duplicate weekly projection identity "
                            + projection.sleeperPlayerId());
                }
            }

            Map<String, String> gaps = new LinkedHashMap<>();
            for (var gap : snapshot.gaps()) {
                if (gaps.putIfAbsent(gap.sleeperPlayerId(), gap.reason()) != null) {
                    throw new IllegalStateException(
                        "BF-903 BLOCKED: duplicate weekly projection gap identity "
                            + gap.sleeperPlayerId());
                }
                if (projections.containsKey(gap.sleeperPlayerId())) {
                    throw new IllegalStateException(
                        "BF-903 BLOCKED: contradictory projection/gap identity "
                            + gap.sleeperPlayerId());
                }
            }

            List<Newcomer> newcomers = bundle.shortlist().shortlist().stream()
                .filter(value -> value.lane()
                    == SleeperLiveWaiverComparisonExecutionBundle.ShortlistLane.NEWCOMER_REVIEW)
                .map(value -> new Newcomer(
                    value.candidate().sleeperPlayerId(),
                    value.candidate().displayName(),
                    value.candidate().position()))
                .sorted(Comparator.comparing(Newcomer::sleeperPlayerId))
                .toList();

            List<Drop> drops = roster.targetPlayers().stream()
                .filter(value -> SleeperLiveWaiverCandidateRosterComparisonMethodology
                    .eligibleReplacementSlot(value.rosterSlot()))
                .map(value -> new Drop(
                    value.sleeperPlayerId(),
                    value.displayName(),
                    value.position(),
                    value.rosterSlot()))
                .sorted(Comparator.comparing(Drop::sleeperPlayerId))
                .toList();

            Diagnostic diagnostic = evaluate(newcomers, drops, projections, gaps);

            System.out.println();
            System.out.println("BF-903 newcomer current-week projection diagnostic");
            System.out.println("Boundary: DIAGNOSTIC_ONLY_READ_ONLY; BF-618/BF-620 recommendation semantics are unchanged.");
            System.out.println("Season/week/scoring: " + roster.providerSeason() + "/" + roster.providerLeg() + "/" + basis);
            System.out.println("Projection source: " + snapshot.sourceName());
            System.out.println("Projection observed at: " + snapshot.observedAt());
            System.out.println("Newcomer shortlist count: " + newcomers.size());
            System.out.println("Live BENCH/RESERVE drop count: " + drops.size());
            System.out.println("Newcomers with current projection: " + diagnostic.newcomersWithProjection()
                + "/" + newcomers.size());
            System.out.println("Drops with current projection: " + diagnostic.dropsWithProjection()
                + "/" + drops.size());
            System.out.println("Positive newcomer/drop projected transactions: "
                + diagnostic.positiveTransactions().size());

            for (Newcomer newcomer : newcomers) {
                BigDecimal projection = projections.get(newcomer.sleeperPlayerId());
                String gap = gaps.get(newcomer.sleeperPlayerId());
                System.out.println("  NEWCOMER " + newcomer.displayName()
                    + " (" + newcomer.position() + ", Sleeper " + newcomer.sleeperPlayerId() + ")"
                    + " | projected=" + points(projection)
                    + " | gap=" + text(gap));
            }

            for (ProjectedTransaction option : diagnostic.positiveTransactions()) {
                System.out.println("  POSITIVE ADD " + option.addName()
                    + " (" + option.addPosition() + ", Sleeper " + option.addSleeperId() + ")"
                    + " / DROP " + option.dropName()
                    + " (" + option.dropPosition() + ", Sleeper " + option.dropSleeperId() + ")"
                    + " | add-projected=" + points(option.addProjectedPoints())
                    + " | drop-projected=" + points(option.dropProjectedPoints())
                    + " | projected-delta=" + signedPoints(option.projectedDelta()));
            }

            System.out.println("Diagnostic state: " + diagnostic.state());
            System.out.println("Boundary: this command does not refresh evidence, modify Butler state, set FAAB, "
                + "or submit a Sleeper transaction.");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Diagnostic evaluate(
        List<Newcomer> newcomers,
        List<Drop> drops,
        Map<String, BigDecimal> projections,
        Map<String, String> gaps) {
        newcomers = List.copyOf(Objects.requireNonNull(newcomers, "newcomers must not be null"));
        drops = List.copyOf(Objects.requireNonNull(drops, "drops must not be null"));
        projections = Map.copyOf(Objects.requireNonNull(projections, "projections must not be null"));
        gaps = Map.copyOf(Objects.requireNonNull(gaps, "gaps must not be null"));

        int newcomerCoverage = 0;
        for (Newcomer newcomer : newcomers) {
            if (projections.containsKey(newcomer.sleeperPlayerId())) newcomerCoverage++;
        }

        int dropCoverage = 0;
        for (Drop drop : drops) {
            if (projections.containsKey(drop.sleeperPlayerId())) dropCoverage++;
        }

        List<ProjectedTransaction> positive = new ArrayList<>();
        for (Newcomer newcomer : newcomers) {
            BigDecimal addProjection = projections.get(newcomer.sleeperPlayerId());
            if (addProjection == null) continue;
            for (Drop drop : drops) {
                BigDecimal dropProjection = projections.get(drop.sleeperPlayerId());
                if (dropProjection == null) continue;
                BigDecimal delta = addProjection.subtract(dropProjection);
                if (delta.signum() <= 0) continue;
                positive.add(new ProjectedTransaction(
                    newcomer.sleeperPlayerId(),
                    newcomer.displayName(),
                    newcomer.position(),
                    drop.sleeperPlayerId(),
                    drop.displayName(),
                    drop.position(),
                    addProjection,
                    dropProjection,
                    delta));
            }
        }

        positive.sort(Comparator
            .comparing(ProjectedTransaction::projectedDelta).reversed()
            .thenComparing(ProjectedTransaction::addSleeperId)
            .thenComparing(ProjectedTransaction::dropSleeperId));

        DiagnosticState state;
        if (newcomers.isEmpty()) {
            state = DiagnosticState.NO_NEWCOMERS;
        } else if (newcomerCoverage == 0) {
            state = DiagnosticState.NO_NEWCOMER_PROJECTION_COVERAGE;
        } else if (dropCoverage == 0) {
            state = DiagnosticState.NO_DROP_PROJECTION_COVERAGE;
        } else if (positive.isEmpty()) {
            state = DiagnosticState.CURRENT_PROJECTIONS_DO_NOT_SUPPORT_NEWCOMER_TRANSACTION;
        } else {
            state = DiagnosticState.CURRENT_PROJECTION_LANE_AVAILABLE;
        }

        return new Diagnostic(
            newcomerCoverage,
            dropCoverage,
            List.copyOf(positive),
            state);
    }

    private static String points(BigDecimal value) {
        return value == null ? "none" : value.stripTrailingZeros().toPlainString();
    }

    private static String signedPoints(BigDecimal value) {
        if (value == null) return "none";
        String rendered = value.stripTrailingZeros().toPlainString();
        return value.signum() > 0 ? "+" + rendered : rendered;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    enum DiagnosticState {
        NO_NEWCOMERS,
        NO_NEWCOMER_PROJECTION_COVERAGE,
        NO_DROP_PROJECTION_COVERAGE,
        CURRENT_PROJECTIONS_DO_NOT_SUPPORT_NEWCOMER_TRANSACTION,
        CURRENT_PROJECTION_LANE_AVAILABLE
    }

    record Newcomer(String sleeperPlayerId, String displayName, String position) {
        Newcomer {
            sleeperPlayerId = requireText(sleeperPlayerId, "newcomer sleeperPlayerId");
            displayName = requireText(displayName, "newcomer displayName");
            position = requireText(position, "newcomer position");
        }
    }

    record Drop(String sleeperPlayerId, String displayName, String position, String rosterSlot) {
        Drop {
            sleeperPlayerId = requireText(sleeperPlayerId, "drop sleeperPlayerId");
            displayName = requireText(displayName, "drop displayName");
            position = requireText(position, "drop position");
            rosterSlot = requireText(rosterSlot, "drop rosterSlot");
        }
    }

    record ProjectedTransaction(
        String addSleeperId,
        String addName,
        String addPosition,
        String dropSleeperId,
        String dropName,
        String dropPosition,
        BigDecimal addProjectedPoints,
        BigDecimal dropProjectedPoints,
        BigDecimal projectedDelta) {}

    record Diagnostic(
        int newcomersWithProjection,
        int dropsWithProjection,
        List<ProjectedTransaction> positiveTransactions,
        DiagnosticState state) {
        Diagnostic {
            positiveTransactions = List.copyOf(positiveTransactions);
            Objects.requireNonNull(state);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
