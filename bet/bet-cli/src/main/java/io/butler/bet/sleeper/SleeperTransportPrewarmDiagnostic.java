package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PersonalizedSleeperTargetRepository;

import java.io.IOException;
import java.nio.file.Path;

/**
 * BF-745 diagnostic that proves whether one discarded read-only request to Sleeper's API origin
 * removes the fresh-JVM transport penalty before the unchanged BF-623 verification runs.
 */
public final class SleeperTransportPrewarmDiagnostic {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private SleeperTransportPrewarmDiagnostic() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: SleeperTransportPrewarmDiagnostic <butler-league-id>");
            }
            String leagueId = args[0].trim();

            Database database = new Database(DATABASE_PATH);
            database.initialize();
            PersonalizedSleeperTargetRepository targets = new PersonalizedSleeperTargetRepository(database);

            Measurement measurement = measure(
                database,
                targets,
                liveSource(),
                liveWarmup(),
                leagueId);
            System.out.println(formatMarker(measurement));
            System.out.println(
                "Boundary: BF-745 performs exactly one discarded read-only Sleeper state/nfl transport prewarm, "
                    + "then the unchanged serial BF-623 verification; no provider data is cached or reused, "
                    + "no concurrency is added, no /refresh path is invoked, and no Butler or Sleeper write is performed.");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static Measurement measure(
        Database database,
        PersonalizedSleeperTargetRepository targets,
        SleeperPersonalizedTargetService.Source source,
        TransportWarmup warmup,
        String leagueId) throws Exception {
        if (database == null) throw new IllegalArgumentException("database must not be null");
        if (targets == null) throw new IllegalArgumentException("targets must not be null");
        if (source == null) throw new IllegalArgumentException("source must not be null");
        if (warmup == null) throw new IllegalArgumentException("warmup must not be null");
        if (leagueId == null || leagueId.isBlank()) throw new IllegalArgumentException("leagueId must not be blank");

        long warmupStarted = System.nanoTime();
        warmup.run();
        long warmupMs = elapsedMillis(warmupStarted);

        SleeperPersonalizedTargetStageDiagnostic.TimingSource timingSource =
            new SleeperPersonalizedTargetStageDiagnostic.TimingSource(source);
        SleeperPersonalizedTargetService service =
            new SleeperPersonalizedTargetService(database, targets, timingSource);
        long verifyStarted = System.nanoTime();
        service.verifyBoundTarget(leagueId.trim());
        long verifyWallMs = elapsedMillis(verifyStarted);
        SleeperPersonalizedTargetStageDiagnostic.StageTiming stageTiming = timingSource.snapshot();
        stageTiming.requireExactSinglePass();
        return new Measurement(warmupMs, stageTiming, verifyWallMs);
    }

    static String formatMarker(Measurement measurement) {
        if (measurement == null) throw new IllegalArgumentException("measurement must not be null");
        long providerSumMs = measurement.stageTiming().providerSumMs();
        long residualMs = Math.max(0L, measurement.verifyWallMs() - providerSumMs);
        return "===BUTLER_TRANSPORT_PREWARM_TIMING:"
            + "warmup_ms=" + measurement.warmupMs()
            + ";user_ms=" + measurement.stageTiming().userMs()
            + ";user_leagues_ms=" + measurement.stageTiming().userLeaguesMs()
            + ";league_ms=" + measurement.stageTiming().leagueMs()
            + ";rosters_ms=" + measurement.stageTiming().rostersMs()
            + ";users_ms=" + measurement.stageTiming().usersMs()
            + ";provider_sum_ms=" + providerSumMs
            + ";verify_wall_ms=" + measurement.verifyWallMs()
            + ";residual_ms=" + residualMs
            + "===";
    }

    private static TransportWarmup liveWarmup() {
        SleeperClient client = new SleeperClient();
        return () -> {
            client.getNflState();
        };
    }

    private static SleeperPersonalizedTargetService.Source liveSource() {
        SleeperClient client = new SleeperClient();
        return new SleeperPersonalizedTargetService.Source() {
            @Override public String user(String usernameOrId) throws IOException, InterruptedException {
                return client.getUser(usernameOrId);
            }

            @Override public String userLeagues(String userId, int season) throws IOException, InterruptedException {
                return client.getUserLeagues(userId, Integer.toString(season));
            }

            @Override public String league(String sleeperLeagueId) throws IOException, InterruptedException {
                return client.getLeague(sleeperLeagueId);
            }

            @Override public String rosters(String sleeperLeagueId) throws IOException, InterruptedException {
                return client.getLeagueRosters(sleeperLeagueId);
            }

            @Override public String users(String sleeperLeagueId) throws IOException, InterruptedException {
                return client.getLeagueUsers(sleeperLeagueId);
            }
        };
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    @FunctionalInterface
    interface TransportWarmup {
        void run() throws Exception;
    }

    record Measurement(
        long warmupMs,
        SleeperPersonalizedTargetStageDiagnostic.StageTiming stageTiming,
        long verifyWallMs) {
        Measurement {
            if (warmupMs < 0) throw new IllegalArgumentException("warmupMs must not be negative");
            if (stageTiming == null) throw new IllegalArgumentException("stageTiming must not be null");
            if (verifyWallMs < 0) throw new IllegalArgumentException("verifyWallMs must not be negative");
        }
    }
}
