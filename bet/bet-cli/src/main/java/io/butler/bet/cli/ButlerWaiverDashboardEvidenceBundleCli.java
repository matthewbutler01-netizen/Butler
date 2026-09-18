package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperLiveWaiverCoalescedComparisonEvidence;
import io.butler.bet.sleeper.SleeperLiveWaiverLatestGovernedDecisionSummary;
import io.butler.bet.sleeper.SleeperLiveWaiverPostTransactionRosterConvergence;
import io.butler.bet.sleeper.SleeperLiveWaiverSharedTargetContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * BF-712/BF-713/BF-762 read-only composition of established Waiver Board evidence sources.
 * Analysis may overlap after exact BF-623 target verification; rendering remains sequential.
 */
public final class ButlerWaiverDashboardEvidenceBundleCli {
    static final String SUMMARY = "SUMMARY";
    static final String WAIVER_BOARD = "WAIVER_BOARD";
    static final String ROSTER_CONTEXT = "ROSTER_CONTEXT";
    static final String COMPARISON_ROSTER_ONLY = "--comparison-roster-only";

    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerWaiverDashboardEvidenceBundleCli() {}

    public static void main(String[] args) {
        int exitCode = runEmbedded(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int runEmbedded(String[] args) {
        if (args == null || args.length < 1 || args.length > 2
            || args[0] == null || args[0].isBlank()
            || (args.length == 2 && !COMPARISON_ROSTER_ONLY.equals(args[1]))) {
            System.err.println("Error: waiver dashboard evidence bundle requires one Butler league id and optional --comparison-roster-only.");
            return 2;
        }

        try {
            String leagueId = args[0].trim();
            if (args.length == 2) {
                runComparisonRosterOnly(leagueId);
            } else {
                runFullBundle(leagueId);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + rootMessage(e));
            return 2;
        }
    }

    private static void runFullBundle(String leagueId) throws Exception {
        Database database = initializedDatabase();
        var shared = new SleeperLiveWaiverSharedTargetContext(database).resolve(leagueId);
        var target = shared.target();
        var exactRosterContext = shared.rosterContext();
        String verifiedTarget = capture(() -> ButlerPersonalizedTargetCliSupport.printVerified(target));

        var reports = runConcurrentPair(
            () -> {
                var summary = new SleeperLiveWaiverLatestGovernedDecisionSummary(database).summarize(target);
                var convergence = new SleeperLiveWaiverPostTransactionRosterConvergence().inspect(target, summary);
                return new SummaryEvidence(summary, convergence);
            },
            () -> new SleeperLiveWaiverCoalescedComparisonEvidence(database)
                .run(leagueId, target.sleeperUserId(), exactRosterContext));

        String summary = withVerifiedTarget(verifiedTarget, capture(() ->
            ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.print(
                reports.first().summary(), reports.first().convergence())));
        String waiverBoard = withVerifiedTarget(verifiedTarget, capture(() ->
            ButlerSleeperLiveWaiverComparisonBundleCli.print(reports.second().bundle())));
        String rosterContext = withVerifiedTarget(verifiedTarget, capture(() ->
            ButlerSleeperLiveWaiverTargetRosterContextAuditCli.print(reports.second().rosterContext())));

        emit(SUMMARY, summary);
        emit(WAIVER_BOARD, waiverBoard);
        emit(ROSTER_CONTEXT, rosterContext);
        System.out.println("Boundary: BF-850 shares one live Sleeper snapshot across exact BF-623 target verification and BF-610 roster audit, then reuses that exact roster report for comparison; no Butler or Sleeper write is executed.");
    }

    private static void runComparisonRosterOnly(String leagueId) throws Exception {
        Database database = initializedDatabase();
        var shared = new SleeperLiveWaiverSharedTargetContext(database).resolve(leagueId);
        var target = shared.target();
        var exactRosterContext = shared.rosterContext();
        String verifiedTarget = capture(() -> ButlerPersonalizedTargetCliSupport.printVerified(target));

        var report = new SleeperLiveWaiverCoalescedComparisonEvidence(database)
            .run(leagueId, target.sleeperUserId(), exactRosterContext);

        String waiverBoard = withVerifiedTarget(verifiedTarget, capture(() ->
            ButlerSleeperLiveWaiverComparisonBundleCli.print(report.bundle())));
        String rosterContext = withVerifiedTarget(verifiedTarget, capture(() ->
            ButlerSleeperLiveWaiverTargetRosterContextAuditCli.print(report.rosterContext())));

        emit(WAIVER_BOARD, waiverBoard);
        emit(ROSTER_CONTEXT, rosterContext);
        System.out.println("Boundary: BF-850 shares one live Sleeper snapshot across exact BF-623 target verification and BF-610 roster audit, then reuses that exact roster report for comparison. No Butler or Sleeper write is executed.");
    }

    private static Database initializedDatabase() throws Exception {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    static <A, B, C> Triple<A, B, C> runConcurrent(
        Callable<A> first,
        Callable<B> second,
        Callable<C> third) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<A> firstFuture = executor.submit(first);
            Future<B> secondFuture = executor.submit(second);
            Future<C> thirdFuture = executor.submit(third);
            return new Triple<>(await(firstFuture), await(secondFuture), await(thirdFuture));
        } finally {
            executor.shutdownNow();
        }
    }

    static <A, B> Pair<A, B> runConcurrentPair(
        Callable<A> first,
        Callable<B> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<A> firstFuture = executor.submit(first);
            Future<B> secondFuture = executor.submit(second);
            return new Pair<>(await(firstFuture), await(secondFuture));
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw e;
        }
    }

    static String capture(CheckedCommand command) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream captured = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            command.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8).stripTrailing();
    }

    private static String withVerifiedTarget(String verifiedTarget, String body) {
        if (verifiedTarget == null || verifiedTarget.isBlank()) return body;
        if (body == null || body.isBlank()) return verifiedTarget;
        return verifiedTarget + System.lineSeparator() + body;
    }

    static void emit(String name, String body) {
        System.out.println(beginMarker(name));
        if (body != null && !body.isEmpty()) System.out.println(body);
        System.out.println(endMarker(name));
    }

    static String beginMarker(String name) {
        return "===BUTLER_WAIVER_BUNDLE:" + name + ":BEGIN===";
    }

    static String endMarker(String name) {
        return "===BUTLER_WAIVER_BUNDLE:" + name + ":END===";
    }

    private static String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    record SummaryEvidence(
        SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport summary,
        SleeperLiveWaiverPostTransactionRosterConvergence.ConvergenceReport convergence) {}

    record Pair<A, B>(A first, B second) {}
    record Triple<A, B, C>(A first, B second, C third) {}

    @FunctionalInterface
    interface CheckedCommand {
        void run() throws Exception;
    }
}
