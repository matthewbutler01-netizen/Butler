package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.PersonalizedSleeperTargetRepository;

import java.io.IOException;
import java.nio.file.Path;

/**
 * BF-744 read-only diagnostic for the existing serial BF-623 personalized target verifier.
 * It changes no production verification behavior; it only times the exact Source calls that the
 * existing verifier requests.
 */
public final class SleeperPersonalizedTargetStageDiagnostic {
    static final int DEFAULT_SAMPLES = 3;
    static final int MAX_SAMPLES = 9;
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private SleeperPersonalizedTargetStageDiagnostic() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length < 1 || args.length > 2
                || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: SleeperPersonalizedTargetStageDiagnostic <butler-league-id> [samples]");
            }
            String leagueId = args[0].trim();
            int samples = args.length == 2 ? parseSamples(args[1]) : DEFAULT_SAMPLES;

            Database database = new Database(DATABASE_PATH);
            database.initialize();
            PersonalizedSleeperTargetRepository targets = new PersonalizedSleeperTargetRepository(database);

            for (int sample = 1; sample <= samples; sample++) {
                TimingSource source = new TimingSource(liveSource());
                SleeperPersonalizedTargetService service =
                    new SleeperPersonalizedTargetService(database, targets, source);
                long verifyStarted = System.nanoTime();
                service.verifyBoundTarget(leagueId);
                long verifyWallMs = elapsedMillis(verifyStarted);
                StageTiming timing = source.snapshot();
                timing.requireExactSinglePass();
                System.out.println(formatMarker(sample, timing, verifyWallMs));
            }
            System.out.println(
                "Boundary: BF-744 times the unchanged serial BF-623 live target verification only; "
                    + "it caches nothing, parallelizes nothing, invokes no /refresh path, and performs no Butler or Sleeper write.");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static int parseSamples(String value) {
        try {
            int samples = Integer.parseInt(value == null ? "" : value.trim());
            if (samples < 1 || samples > MAX_SAMPLES) throw new NumberFormatException();
            return samples;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("samples must be between 1 and " + MAX_SAMPLES);
        }
    }

    static String formatMarker(int sample, StageTiming timing, long verifyWallMs) {
        if (sample <= 0) throw new IllegalArgumentException("sample must be positive");
        if (timing == null) throw new IllegalArgumentException("timing must not be null");
        if (verifyWallMs < 0) throw new IllegalArgumentException("verifyWallMs must not be negative");
        long providerSumMs = timing.providerSumMs();
        long residualMs = Math.max(0L, verifyWallMs - providerSumMs);
        return "===BUTLER_TARGET_STAGE_TIMING:"
            + "sample=" + sample
            + ";user_ms=" + timing.userMs()
            + ";user_leagues_ms=" + timing.userLeaguesMs()
            + ";league_ms=" + timing.leagueMs()
            + ";rosters_ms=" + timing.rostersMs()
            + ";users_ms=" + timing.usersMs()
            + ";provider_sum_ms=" + providerSumMs
            + ";verify_wall_ms=" + verifyWallMs
            + ";residual_ms=" + residualMs
            + "===";
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

    static final class TimingSource implements SleeperPersonalizedTargetService.Source {
        private final SleeperPersonalizedTargetService.Source delegate;
        private long userMs;
        private long userLeaguesMs;
        private long leagueMs;
        private long rostersMs;
        private long usersMs;
        private int userCalls;
        private int userLeaguesCalls;
        private int leagueCalls;
        private int rostersCalls;
        private int usersCalls;

        TimingSource(SleeperPersonalizedTargetService.Source delegate) {
            if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
            this.delegate = delegate;
        }

        @Override public String user(String usernameOrId) throws IOException, InterruptedException {
            long started = System.nanoTime();
            try {
                return delegate.user(usernameOrId);
            } finally {
                userMs += elapsedMillis(started);
                userCalls++;
            }
        }

        @Override public String userLeagues(String userId, int season) throws IOException, InterruptedException {
            long started = System.nanoTime();
            try {
                return delegate.userLeagues(userId, season);
            } finally {
                userLeaguesMs += elapsedMillis(started);
                userLeaguesCalls++;
            }
        }

        @Override public String league(String sleeperLeagueId) throws IOException, InterruptedException {
            long started = System.nanoTime();
            try {
                return delegate.league(sleeperLeagueId);
            } finally {
                leagueMs += elapsedMillis(started);
                leagueCalls++;
            }
        }

        @Override public String rosters(String sleeperLeagueId) throws IOException, InterruptedException {
            long started = System.nanoTime();
            try {
                return delegate.rosters(sleeperLeagueId);
            } finally {
                rostersMs += elapsedMillis(started);
                rostersCalls++;
            }
        }

        @Override public String users(String sleeperLeagueId) throws IOException, InterruptedException {
            long started = System.nanoTime();
            try {
                return delegate.users(sleeperLeagueId);
            } finally {
                usersMs += elapsedMillis(started);
                usersCalls++;
            }
        }

        StageTiming snapshot() {
            return new StageTiming(
                userMs, userLeaguesMs, leagueMs, rostersMs, usersMs,
                userCalls, userLeaguesCalls, leagueCalls, rostersCalls, usersCalls);
        }
    }

    record StageTiming(
        long userMs,
        long userLeaguesMs,
        long leagueMs,
        long rostersMs,
        long usersMs,
        int userCalls,
        int userLeaguesCalls,
        int leagueCalls,
        int rostersCalls,
        int usersCalls) {

        StageTiming {
            if (userMs < 0 || userLeaguesMs < 0 || leagueMs < 0 || rostersMs < 0 || usersMs < 0) {
                throw new IllegalArgumentException("stage timings must not be negative");
            }
        }

        long providerSumMs() {
            return userMs + userLeaguesMs + leagueMs + rostersMs + usersMs;
        }

        void requireExactSinglePass() {
            if (userCalls != 1 || userLeaguesCalls != 1 || leagueCalls != 1 || rostersCalls != 1 || usersCalls != 1) {
                throw new IllegalStateException(
                    "BF-744 BLOCKED: expected one serial BF-623 provider call per stage but observed "
                        + "user=" + userCalls
                        + " userLeagues=" + userLeaguesCalls
                        + " league=" + leagueCalls
                        + " rosters=" + rostersCalls
                        + " users=" + usersCalls);
            }
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
