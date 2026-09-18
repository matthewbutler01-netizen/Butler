package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.LeaguePositionalPressureAnalyzer;
import io.butler.bet.intelligence.LeagueRosterStrengthTierAnalyzer;
import io.butler.bet.intelligence.WeeklyMatchupWorkspaceAnalyzer;
import io.butler.bet.sleeper.SleeperLiveAutoFillLineupRecommendation;
import io.butler.bet.sleeper.SleeperLiveWaiverTargetRosterContextAudit;

import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * BF-849 read-only Weekly Matchup composition.
 * Reuses one initialized database and one verified target while rendering only the
 * roster, lineup, opponent-strength, positional-pressure, and exact matchup
 * evidence required by the manager-facing Weekly Matchup page.
 */
public final class ButlerWeeklyMatchupEvidenceBundleCli {
    static final String MATCHUP = "MATCHUP";
    static final int EVIDENCE_WORKERS = 3;

    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String SOURCE = "sleeper";

    private ButlerWeeklyMatchupEvidenceBundleCli() {}

    public static void main(String[] args) {
        int exitCode = runEmbedded(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    static int runEmbedded(String[] args) {
        boolean includeAutoFill = validAutoFillArgs(args);
        boolean normalBundle = args != null
            && args.length == 1
            && args[0] != null
            && !args[0].isBlank();
        if (!normalBundle && !includeAutoFill) {
            System.err.println("Error: weekly matchup evidence bundle requires <butler-league-id> [--autofill].");
            return 2;
        }

        ExecutorService executor = Executors.newFixedThreadPool(EVIDENCE_WORKERS);
        try {
            String leagueId = args[0].trim();
            Database database = initializedDatabase();
            var target = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);

            Future<SleeperLiveWaiverTargetRosterContextAudit.AuditReport> rosterFuture = executor.submit(() ->
                new SleeperLiveWaiverTargetRosterContextAudit(database).audit(leagueId, target.sleeperUserId()));
            Future<LeagueRosterStrengthTierAnalyzer.RosterStrengthReport> strengthFuture = executor.submit(() ->
                new LeagueRosterStrengthTierAnalyzer(database).analyze(leagueId));
            Future<LeaguePositionalPressureAnalyzer.PositionalPressureReport> pressureFuture = executor.submit(() ->
                new LeaguePositionalPressureAnalyzer(database).analyze(leagueId));

            SleeperLiveWaiverTargetRosterContextAudit.AuditReport roster = await(rosterFuture);
            int week = roster.providerLeg() == null ? 0 : roster.providerLeg();

            Future<WeeklyMatchupWorkspaceAnalyzer.MatchupReport> matchupFuture = null;
            if (week > 0) {
                matchupFuture = executor.submit(() ->
                    new WeeklyMatchupWorkspaceAnalyzer(database).analyze(
                        roster.sleeperLeagueId(),
                        roster.butlerTeamId(),
                        roster.providerSeason(),
                        week,
                        SOURCE));
            }

            Future<SleeperLiveAutoFillLineupRecommendation.RecommendationReport> autoFillFuture = includeAutoFill
                ? executor.submit(() -> autoFillSafely(database, roster))
                : null;

            LeagueRosterStrengthTierAnalyzer.RosterStrengthReport strength = await(strengthFuture);
            LeaguePositionalPressureAnalyzer.PositionalPressureReport pressure = await(pressureFuture);

            String rosterContext = ButlerMyTeamEvidenceBundleCli.capture(() -> {
                ButlerPersonalizedTargetCliSupport.printVerified(target);
                ButlerSleeperLiveWaiverTargetRosterContextAuditCli.print(roster);
            });
            String autoFill = includeAutoFill
                ? ButlerMyTeamEvidenceBundleCli.capture(() ->
                    ButlerAutoFillLineupRecommendationCli.print(await(autoFillFuture)))
                : null;
            String rosterStrength = ButlerMyTeamEvidenceBundleCli.capture(() ->
                ButlerLeagueRosterStrengthCli.print(strength));
            String positionalPressure = ButlerMyTeamEvidenceBundleCli.capture(() ->
                ButlerLeaguePositionalPressureCli.print(pressure));
            String matchup = renderMatchup(matchupFuture, week);

            ButlerMyTeamEvidenceBundleCli.emit(ButlerMyTeamEvidenceBundleCli.ROSTER_CONTEXT, rosterContext);
            if (includeAutoFill) {
                ButlerMyTeamEvidenceBundleCli.emit(ButlerMyTeamEvidenceBundleCli.AUTOFILL, autoFill);
            }
            ButlerMyTeamEvidenceBundleCli.emit(ButlerMyTeamEvidenceBundleCli.ROSTER_STRENGTH, rosterStrength);
            ButlerMyTeamEvidenceBundleCli.emit(ButlerMyTeamEvidenceBundleCli.POSITIONAL_PRESSURE, positionalPressure);
            ButlerMyTeamEvidenceBundleCli.emit(MATCHUP, matchup);
            System.out.println(includeAutoFill
                ? "Boundary: BF-849 composes exact persisted matchup evidence and one explicitly requested read-only lineup review in one JVM; no Butler or Sleeper write is executed."
                : "Boundary: BF-849 composes exact persisted matchup evidence with provider-free passive lineup state in one JVM; no provider fetch and no Butler or Sleeper write is executed.");
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + safeMessage(e));
            return 2;
        } finally {
            executor.shutdownNow();
        }
    }

    static boolean validAutoFillArgs(String[] args) {
        return args != null
            && args.length == 2
            && args[0] != null
            && !args[0].isBlank()
            && "--autofill".equals(args[1]);
    }

    static String unavailableMatchupSection(String reason) {
        String safeReason = reason == null || reason.isBlank()
            ? "Exact Sleeper matchup pairing is unavailable."
            : reason.trim();
        return "Weekly matchup workspace" + System.lineSeparator()
            + "State: UNAVAILABLE" + System.lineSeparator()
            + "Reason: " + safeReason + System.lineSeparator()
            + "Boundary: exact persisted Sleeper matchup pairing only; no opponent is guessed.";
    }

    private static String renderMatchup(
        Future<WeeklyMatchupWorkspaceAnalyzer.MatchupReport> matchupFuture,
        int week) throws Exception {
        if (week <= 0) {
            return unavailableMatchupSection(
                "Current Sleeper week is unavailable, so exact opponent pairing cannot be resolved.");
        }
        if (matchupFuture == null) {
            return unavailableMatchupSection("Exact Sleeper matchup pairing is unavailable.");
        }
        try {
            WeeklyMatchupWorkspaceAnalyzer.MatchupReport report = await(matchupFuture);
            return ButlerMyTeamEvidenceBundleCli.capture(() ->
                ButlerWeeklyMatchupWorkspaceCli.print(report));
        } catch (Exception e) {
            return unavailableMatchupSection(safeMessage(e));
        }
    }

    private static SleeperLiveAutoFillLineupRecommendation.RecommendationReport autoFillSafely(
        Database database,
        SleeperLiveWaiverTargetRosterContextAudit.AuditReport roster) {
        try {
            return new SleeperLiveAutoFillLineupRecommendation(database).recommend(roster);
        } catch (Exception e) {
            return SleeperLiveAutoFillLineupRecommendation.RecommendationReport.unavailable(
                roster.providerSeason(),
                roster.providerLeg(),
                null,
                "AutoFill evidence is unavailable: " + safeMessage(e));
        }
    }

    private static Database initializedDatabase() throws Exception {
        Database database = new Database(DATABASE_PATH);
        database.initialize();
        return database;
    }

    private static <T> T await(Future<T> future) throws Exception {
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

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
