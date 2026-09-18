package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.PersonalizedSleeperTargetRepository;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.domain.TeamWeekRosterEvidence;
import io.butler.bet.intelligence.LeaguePositionalPressureAnalyzer;
import io.butler.bet.intelligence.LeagueRosterStrengthTierAnalyzer;
import io.butler.bet.intelligence.WeeklyMatchupWorkspaceAnalyzer;
import io.butler.bet.sleeper.SleeperLiveAutoFillLineupRecommendation;
import io.butler.bet.sleeper.SleeperLiveWaiverTargetRosterContextAudit;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * BF-849 read-only Weekly Matchup composition.
 * Passive Matchup uses persisted Butler/Sleeper identity plus persisted week evidence only.
 * Explicit AutoFill may opt into the existing BF-623/BF-610 live roster gate.
 */
public final class ButlerWeeklyMatchupEvidenceBundleCli {
    static final String MATCHUP_CONTEXT = "MATCHUP_CONTEXT";
    static final String MATCHUP = "MATCHUP";
    static final int EVIDENCE_WORKERS = 4;

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
            MatchupContext context = loadPersistedContext(database, leagueId);

            Future<LeagueRosterStrengthTierAnalyzer.RosterStrengthReport> strengthFuture = executor.submit(() ->
                new LeagueRosterStrengthTierAnalyzer(database).analyze(leagueId));
            Future<LeaguePositionalPressureAnalyzer.PositionalPressureReport> pressureFuture = executor.submit(() ->
                new LeaguePositionalPressureAnalyzer(database).analyze(leagueId));
            Future<WeeklyMatchupWorkspaceAnalyzer.MatchupReport> matchupFuture = executor.submit(() ->
                new WeeklyMatchupWorkspaceAnalyzer(database).analyze(
                    context.sleeperLeagueId(),
                    context.butlerTeamId(),
                    context.season(),
                    context.week(),
                    SOURCE));
            Future<SleeperLiveAutoFillLineupRecommendation.RecommendationReport> autoFillFuture = includeAutoFill
                ? executor.submit(() -> autoFillSafely(database, leagueId, context))
                : null;

            LeagueRosterStrengthTierAnalyzer.RosterStrengthReport strength = await(strengthFuture);
            LeaguePositionalPressureAnalyzer.PositionalPressureReport pressure = await(pressureFuture);

            String matchupContext = renderMatchupContext(context);
            String autoFill = includeAutoFill
                ? ButlerMyTeamEvidenceBundleCli.capture(() ->
                    ButlerAutoFillLineupRecommendationCli.print(await(autoFillFuture)))
                : null;
            String rosterStrength = ButlerMyTeamEvidenceBundleCli.capture(() ->
                ButlerLeagueRosterStrengthCli.print(strength));
            String positionalPressure = ButlerMyTeamEvidenceBundleCli.capture(() ->
                ButlerLeaguePositionalPressureCli.print(pressure));
            String matchup = renderMatchup(matchupFuture);

            ButlerMyTeamEvidenceBundleCli.emit(MATCHUP_CONTEXT, matchupContext);
            if (includeAutoFill) {
                ButlerMyTeamEvidenceBundleCli.emit(ButlerMyTeamEvidenceBundleCli.AUTOFILL, autoFill);
            }
            ButlerMyTeamEvidenceBundleCli.emit(ButlerMyTeamEvidenceBundleCli.ROSTER_STRENGTH, rosterStrength);
            ButlerMyTeamEvidenceBundleCli.emit(ButlerMyTeamEvidenceBundleCli.POSITIONAL_PRESSURE, positionalPressure);
            ButlerMyTeamEvidenceBundleCli.emit(MATCHUP, matchup);
            System.out.println(includeAutoFill
                ? "Boundary: BF-849 uses persisted Matchup identity/evidence and one explicitly requested BF-623/BF-610 read-only lineup review; no Butler or Sleeper write is executed."
                : "Boundary: BF-849 passive Matchup uses persisted Butler/Sleeper target, roster-week, and exact matchup evidence only; no provider fetch and no Butler or Sleeper write is executed.");
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

    static MatchupContext loadPersistedContext(Database database, String leagueId) throws Exception {
        var target = new PersonalizedSleeperTargetRepository(database).findByButlerLeagueId(leagueId)
            .orElseThrow(() -> new IllegalStateException(
                "BF-849 BLOCKED: persisted personalized Sleeper target is unavailable."));
        var league = new LeagueRepository(database).findById(leagueId)
            .orElseThrow(() -> new IllegalStateException(
                "BF-849 BLOCKED: Butler league is unavailable."));
        if (!target.sleeperLeagueId().equals(league.getExternalId())) {
            throw new IllegalStateException(
                "BF-849 BLOCKED: persisted personalized target does not match Butler provider linkage.");
        }
        if (league.getSeason() == null || league.getSeason() != target.season()) {
            throw new IllegalStateException(
                "BF-849 BLOCKED: persisted personalized target season does not match Butler league season.");
        }

        var team = new TeamRepository(database)
            .findByExternalId(leagueId, Integer.toString(target.rosterId()))
            .orElseThrow(() -> new IllegalStateException(
                "BF-849 BLOCKED: persisted personalized roster is not mapped to a Butler team."));
        TeamWeekRosterEvidence latest = new TeamWeekRosterEvidenceRepository(database)
            .findLatestByTeamSeason(leagueId, team.getId(), target.season(), SOURCE)
            .stream()
            .max(Comparator.comparingInt(TeamWeekRosterEvidence::week))
            .orElseThrow(() -> new IllegalStateException(
                "BF-849 BLOCKED: persisted weekly roster evidence is unavailable for the bound team."));

        return new MatchupContext(
            target.sleeperLeagueId(),
            target.leagueName(),
            target.rosterId(),
            team.getId(),
            team.getName(),
            target.season(),
            target.providerStatus(),
            latest.week());
    }

    static String renderMatchupContext(MatchupContext context) {
        return "Weekly matchup context" + System.lineSeparator()
            + "State: READY" + System.lineSeparator()
            + "Sleeper league ID: " + context.sleeperLeagueId() + System.lineSeparator()
            + "League name: " + context.leagueName() + System.lineSeparator()
            + "Roster ID: " + context.rosterId() + System.lineSeparator()
            + "Butler team ID: " + context.butlerTeamId() + System.lineSeparator()
            + "Butler team name: " + context.butlerTeamName() + System.lineSeparator()
            + "Season: " + context.season() + System.lineSeparator()
            + "Provider status: " + context.providerStatus() + System.lineSeparator()
            + "Week: " + context.week() + System.lineSeparator()
            + "Boundary: persisted personalized target plus persisted weekly roster evidence only.";
    }

    private static String renderMatchup(
        Future<WeeklyMatchupWorkspaceAnalyzer.MatchupReport> matchupFuture) {
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
        String leagueId,
        MatchupContext context) {
        try {
            var liveTarget = ButlerPersonalizedTargetCliSupport.verify(database, leagueId);
            var liveRoster = new SleeperLiveWaiverTargetRosterContextAudit(database)
                .audit(leagueId, liveTarget.sleeperUserId());
            return new SleeperLiveAutoFillLineupRecommendation(database).recommend(liveRoster);
        } catch (Exception e) {
            return SleeperLiveAutoFillLineupRecommendation.RecommendationReport.unavailable(
                context.season(),
                context.week(),
                null,
                "Lineup review is unavailable until current roster evidence is refreshed: " + safeMessage(e));
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

    record MatchupContext(
        String sleeperLeagueId,
        String leagueName,
        int rosterId,
        String butlerTeamId,
        String butlerTeamName,
        int season,
        String providerStatus,
        int week) {}
}
