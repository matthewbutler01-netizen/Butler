package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.FranchiseValueRankingAnalyzer;
import io.butler.bet.intelligence.LeagueActionPlanAnalyzer;
import io.butler.bet.intelligence.LeagueValueMoverAnalyzer;
import io.butler.bet.sleeper.SleeperLiveWaiverComparisonStageDiagnostic;
import io.butler.bet.sleeper.SleeperLiveWaiverLatestGovernedDecisionSummary;
import io.butler.bet.sleeper.SleeperLiveWaiverPostTransactionRosterConvergence;
import io.butler.bet.sleeper.SleeperLiveWaiverTargetRosterContextAudit;

import java.nio.file.Path;

/**
 * BF-733/BF-736 read-only timing diagnostic for the slow app-route evidence pipelines.
 *
 * <p>This intentionally reuses the existing BF-712 three-worker Waiver Board composition after
 * one exact BF-623 target verification. It does not refresh evidence, weaken target verification,
 * change analyzer order, or execute any Butler/Sleeper write path.</p>
 */
public final class ButlerSlowRouteStageDiagnosticCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSlowRouteStageDiagnosticCli() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: ButlerSlowRouteStageDiagnosticCli <butler-league-id>");
            }
            System.out.println(timingMarker(measure(args[0].trim())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Error: BF-733 timing diagnostic interrupted");
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Error: " + rootMessage(e));
            System.exit(2);
        }
    }

    static StageTiming measure(String leagueId) throws Exception {
        if (leagueId == null || leagueId.isBlank()) {
            throw new IllegalArgumentException("leagueId must not be blank");
        }
        String normalizedLeagueId = leagueId.trim();
        long totalStarted = System.nanoTime();

        long databaseStarted = System.nanoTime();
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        long databaseMs = elapsedMs(databaseStarted);

        long targetStarted = System.nanoTime();
        var target = ButlerPersonalizedTargetCliSupport.verify(database, normalizedLeagueId);
        long targetMs = elapsedMs(targetStarted);

        long waiverWallStarted = System.nanoTime();
        var waiverStages = ButlerWaiverDashboardEvidenceBundleCli.runConcurrent(
            () -> timed(() -> {
                var summary = new SleeperLiveWaiverLatestGovernedDecisionSummary(database).summarize(target);
                new SleeperLiveWaiverPostTransactionRosterConvergence().inspect(target, summary);
                return summary;
            }),
            () -> timed(() -> new SleeperLiveWaiverComparisonStageDiagnostic(database)
                .measure(normalizedLeagueId, target.sleeperUserId())),
            () -> timed(() -> new SleeperLiveWaiverTargetRosterContextAudit(database)
                .audit(normalizedLeagueId, target.sleeperUserId())));
        long waiverWallMs = elapsedMs(waiverWallStarted);

        long summaryMs = waiverStages.first().elapsedMs();
        long comparisonMs = waiverStages.second().elapsedMs();
        var comparisonTiming = waiverStages.second().value().timing();
        long rosterContextMs = waiverStages.third().elapsedMs();

        long leagueStarted = System.nanoTime();
        Timed<LeagueActionPlanAnalyzer.ActionPlan> actionPlan = timed(() ->
            new LeagueActionPlanAnalyzer(database).analyze(normalizedLeagueId));
        var health = actionPlan.value().health();

        long rankingsMs = 0L;
        if (health.franchiseRankingsReady()) {
            rankingsMs = timed(() -> health.minimumAsOfDate() == null
                ? new FranchiseValueRankingAnalyzer(database).rank(health.leagueId(), health.source())
                : new FranchiseValueRankingAnalyzer(database).rank(
                    health.leagueId(), health.source(), health.minimumAsOfDate())).elapsedMs();
        }

        long movementMs = 0L;
        var readiness = health.movementReadiness();
        if (readiness != null
            && readiness.previousDate() != null
            && readiness.latestDate() != null
            && readiness.comparablePlayers() > 0) {
            movementMs = timed(() -> new LeagueValueMoverAnalyzer(database).analyze(
                health.leagueId(), health.source(), readiness.previousDate(), readiness.latestDate())).elapsedMs();
        }
        long leagueMs = elapsedMs(leagueStarted);

        long homeEvidenceMs = targetMs + summaryMs;
        long waiverEvidenceMs = targetMs + waiverWallMs;
        return new StageTiming(
            databaseMs,
            targetMs,
            summaryMs,
            comparisonMs,
            comparisonTiming.methodologyMs(),
            comparisonTiming.candidateFrameMs(),
            comparisonTiming.rosterFrameMs(),
            comparisonTiming.productionLoadMs(),
            comparisonTiming.residualMs(),
            rosterContextMs,
            waiverWallMs,
            homeEvidenceMs,
            waiverEvidenceMs,
            actionPlan.elapsedMs(),
            rankingsMs,
            movementMs,
            leagueMs,
            elapsedMs(totalStarted));
    }

    static String timingMarker(StageTiming timing) {
        return "===BUTLER_SLOW_ROUTE_TIMING:"
            + "database_ms=" + timing.databaseMs()
            + ";target_ms=" + timing.targetMs()
            + ";summary_ms=" + timing.summaryMs()
            + ";comparison_ms=" + timing.comparisonMs()
            + ";comparison_methodology_ms=" + timing.comparisonMethodologyMs()
            + ";comparison_candidate_frame_ms=" + timing.comparisonCandidateFrameMs()
            + ";comparison_roster_frame_ms=" + timing.comparisonRosterFrameMs()
            + ";comparison_production_load_ms=" + timing.comparisonProductionLoadMs()
            + ";comparison_residual_ms=" + timing.comparisonResidualMs()
            + ";roster_context_ms=" + timing.rosterContextMs()
            + ";waiver_wall_ms=" + timing.waiverWallMs()
            + ";home_evidence_ms=" + timing.homeEvidenceMs()
            + ";waiver_evidence_ms=" + timing.waiverEvidenceMs()
            + ";league_action_plan_ms=" + timing.leagueActionPlanMs()
            + ";league_rankings_ms=" + timing.leagueRankingsMs()
            + ";league_movement_ms=" + timing.leagueMovementMs()
            + ";league_evidence_ms=" + timing.leagueEvidenceMs()
            + ";total_ms=" + timing.totalMs()
            + "===";
    }

    private static <T> Timed<T> timed(CheckedSupplier<T> supplier) throws Exception {
        long started = System.nanoTime();
        T value = supplier.get();
        return new Timed<>(value, elapsedMs(started));
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    record StageTiming(
        long databaseMs,
        long targetMs,
        long summaryMs,
        long comparisonMs,
        long comparisonMethodologyMs,
        long comparisonCandidateFrameMs,
        long comparisonRosterFrameMs,
        long comparisonProductionLoadMs,
        long comparisonResidualMs,
        long rosterContextMs,
        long waiverWallMs,
        long homeEvidenceMs,
        long waiverEvidenceMs,
        long leagueActionPlanMs,
        long leagueRankingsMs,
        long leagueMovementMs,
        long leagueEvidenceMs,
        long totalMs) {}

    private record Timed<T>(T value, long elapsedMs) {}

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
