package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.sleeper.SleeperPersonalizedTargetService;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** BF-855/BF-862 diagnostic-only timing of exact BF-623 live Sleeper surfaces. */
public final class ButlerSleeperPersonalTargetVerificationDiagnosticCli {
    private ButlerSleeperPersonalTargetVerificationDiagnosticCli() {}

    public static void main(String[] args) {
        int exitCode = runEmbedded(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int runEmbedded(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperPersonalTargetVerificationDiagnostic <butler-league-id>");
            }
            String leagueId = args[0].trim();
            Database database = new Database(Path.of("butler.db"));
            database.initialize();

            Map<String, Double> stages = new LinkedHashMap<>();
            var target = new SleeperPersonalizedTargetService(database)
                .verifyBoundTarget(leagueId, stages::put);

            for (String stage : stages()) {
                Double elapsed = stages.get(stage);
                if (elapsed == null) {
                    throw new IllegalStateException("BF-855 BLOCKED: missing provider stage " + stage);
                }
                System.out.printf(Locale.ROOT, "BF855_STAGE %s_ms=%.3f%n", stage, elapsed);
            }
            System.out.println("BF855_STATE " + target.state());
            System.out.println("BF855_TARGET " + signature(target));
            System.out.println("BF855_BOUNDARY read_only=true; persistent_jvm=true; refresh=false; sleeper_write=false");
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    static int runParallelEmbedded(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
                throw new IllegalArgumentException(
                    "Usage: sleeperPersonalTargetParallelVerificationDiagnostic <butler-league-id>");
            }
            String leagueId = args[0].trim();
            Database database = new Database(Path.of("butler.db"));
            database.initialize();

            Map<String, Double> stages = new LinkedHashMap<>();
            var target = new SleeperPersonalizedTargetService(database)
                .verifyBoundTargetParallelDiagnostic(leagueId, stages::put);

            for (String stage : stages()) {
                Double elapsed = stages.get(stage);
                if (elapsed == null) {
                    throw new IllegalStateException("BF-862 BLOCKED: missing provider stage " + stage);
                }
                System.out.printf(Locale.ROOT, "BF862_STAGE %s_ms=%.3f%n", stage, elapsed);
            }
            System.out.println("BF862_STATE " + target.state());
            System.out.println("BF862_TARGET " + signature(target));
            System.out.println(
                "BF862_BOUNDARY read_only=true; persistent_jvm=true; production_serial_unchanged=true; "
                    + "refresh=false; sleeper_write=false");
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    private static String[] stages() {
        return new String[] {
            "user",
            "user_leagues",
            "league",
            "rosters",
            "league_users",
            "verify_total"
        };
    }

    private static String signature(SleeperPersonalizedTargetService.VerifiedTarget target) {
        return String.join("|",
            target.policyId(),
            target.butlerLeagueId(),
            target.sleeperUsername(),
            target.sleeperUserId(),
            target.sleeperLeagueId(),
            target.leagueName(),
            target.providerStatus(),
            Integer.toString(target.rosterId()),
            target.membershipRole().name(),
            value(target.displayName()),
            value(target.teamName()),
            target.state().name());
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "none" : value.trim();
    }
}
