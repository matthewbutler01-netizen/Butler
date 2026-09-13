package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeagueCompetitiveTierAnalyzer;
import io.butler.bet.intelligence.LeagueFutureCapitalTierAnalyzer;
import io.butler.bet.intelligence.LeaguePositionalPressureAnalyzer;
import io.butler.bet.intelligence.LeagueRosterStrengthTierAnalyzer;
import io.butler.bet.intelligence.LeagueTeamContextAnalyzer;
import io.butler.bet.intelligence.LeagueTeamPostureAnalyzer;
import io.butler.bet.sleeper.SleeperLiveWaiverTargetRosterContextAudit;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Read-only BF-692 composition of the exact BF-668 My Team evidence sources in one JVM.
 * BF-699 reuses one initialized database across those exact reads.
 * BF-711 overlaps independent evidence analysis, then renders deterministically.
 * BF-716 overlaps BF-610 roster analysis with the four analyzers that do not need its season.
 * BF-717 reuses the existing roster-strength report when composing team posture.
 * This class adds no analyzer, score, recommendation, mutation, or evidence synthesis.
 */
public final class ButlerMyTeamEvidenceBundleCli {
    static final String ROSTER_CONTEXT = "ROSTER_CONTEXT";
    static final String TEAM_CONTEXT = "TEAM_CONTEXT";
    static final String ROSTER_STRENGTH = "ROSTER_STRENGTH";
    static final String POSITIONAL_PRESSURE = "POSITIONAL_PRESSURE";
    static final String TEAM_POSTURE = "TEAM_POSTURE";
    static final String FUTURE_CAPITAL = "FUTURE_CAPITAL";
    static final int POST_ROSTER_WORKERS = 5;

    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerMyTeamEvidenceBundleCli() {}

    public static void main(String[] args) {
        if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
            System.err.println("Error: butlerMyTeamEvidenceBundle requires one Butler league id.");
            System.exit(2);
            return;
        }

        try {
            String leagueId = args[0].trim();
            Database database = initializedDatabase();
            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            LeagueTeamPostureAnalyzer teamPostureAnalyzer = new LeagueTeamPostureAnalyzer(database);

            ExecutorService executor = newEvidenceExecutor();
            try {
                Future<SleeperLiveWaiverTargetRosterContextAudit.AuditReport> rosterContextFuture = submitEvidence(executor, () ->
                    new SleeperLiveWaiverTargetRosterContextAudit(database).audit(leagueId, target.sleeperUserId()));
                Future<LeagueTeamContextAnalyzer.TeamContextReport> teamContextFuture = submitEvidence(executor, () ->
                    new LeagueTeamContextAnalyzer(database).analyze(leagueId));
                Future<LeagueRosterStrengthTierAnalyzer.RosterStrengthReport> rosterStrengthFuture = submitEvidence(executor, () ->
                    new LeagueRosterStrengthTierAnalyzer(database).analyze(leagueId));
                Future<LeaguePositionalPressureAnalyzer.PositionalPressureReport> positionalPressureFuture = submitEvidence(executor, () ->
                    new LeaguePositionalPressureAnalyzer(database).analyze(leagueId));
                Future<LeagueFutureCapitalTierAnalyzer.FutureCapitalReport> futureCapitalFuture = submitEvidence(executor, () ->
                    new LeagueFutureCapitalTierAnalyzer(database).analyze(leagueId));

                SleeperLiveWaiverTargetRosterContextAudit.AuditReport rosterContextReport = await(rosterContextFuture);
                int season = rosterContextReport.providerSeason();
                Future<LeagueCompetitiveTierAnalyzer.CompetitiveTierReport> postureCompetitiveFuture = submitEvidence(executor, () ->
                    teamPostureAnalyzer.analyzeCompetitiveEvidence(leagueId, season));

                LeagueTeamContextAnalyzer.TeamContextReport teamContextReport = await(teamContextFuture);
                LeagueRosterStrengthTierAnalyzer.RosterStrengthReport rosterStrengthReport = await(rosterStrengthFuture);
                LeaguePositionalPressureAnalyzer.PositionalPressureReport positionalPressureReport = await(positionalPressureFuture);
                LeagueFutureCapitalTierAnalyzer.FutureCapitalReport futureCapitalReport = await(futureCapitalFuture);
                LeagueTeamPostureAnalyzer.PostureReport teamPostureReport = LeagueTeamPostureAnalyzer.compose(
                    await(postureCompetitiveFuture), rosterStrengthReport);

                String rosterContext = capture(() -> {
                    ButlerPersonalizedTargetCliSupport.printVerified(target);
                    ButlerSleeperLiveWaiverTargetRosterContextAuditCli.print(rosterContextReport);
                });
                String teamContext = capture(() -> ButlerMain.printLeagueTeamContext(teamContextReport));
                String rosterStrength = capture(() -> ButlerLeagueRosterStrengthCli.print(rosterStrengthReport));
                String positionalPressure = capture(() -> ButlerLeaguePositionalPressureCli.print(positionalPressureReport));
                String teamPosture = capture(() -> ButlerLeagueTeamPostureCli.print(teamPostureReport));
                String futureCapital = capture(() -> ButlerLeagueFutureCapitalCli.print(futureCapitalReport));

                emit(ROSTER_CONTEXT, rosterContext);
                emit(TEAM_CONTEXT, teamContext);
                emit(ROSTER_STRENGTH, rosterStrength);
                emit(POSITIONAL_PRESSURE, positionalPressure);
                emit(TEAM_POSTURE, teamPosture);
                emit(FUTURE_CAPITAL, futureCapital);
                System.out.println("Boundary: BF-699 reuses one initialized database for the existing read-only My Team evidence only; no Butler or Sleeper write is executed.");
            } finally {
                executor.shutdownNow();
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    private static Database initializedDatabase() throws Exception {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    static ExecutorService newEvidenceExecutor() {
        return Executors.newFixedThreadPool(POST_ROSTER_WORKERS);
    }

    static <T> Future<T> submitEvidence(ExecutorService executor, CheckedSupplier<T> supplier) {
        if (executor == null) throw new IllegalArgumentException("executor must not be null");
        if (supplier == null) throw new IllegalArgumentException("supplier must not be null");
        return executor.submit(supplier::get);
    }

    static <T> T await(Future<T> future) throws Exception {
        if (future == null) throw new IllegalArgumentException("future must not be null");
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
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

    static void emit(String name, String body) {
        System.out.println(beginMarker(name));
        if (body != null && !body.isEmpty()) System.out.println(body);
        System.out.println(endMarker(name));
    }

    static String beginMarker(String name) {
        return "===BUTLER_TEAM_BUNDLE:" + name + ":BEGIN===";
    }

    static String endMarker(String name) {
        return "===BUTLER_TEAM_BUNDLE:" + name + ":END===";
    }

    @FunctionalInterface
    interface CheckedCommand {
        void run() throws Exception;
    }

    @FunctionalInterface
    interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
