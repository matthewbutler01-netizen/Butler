package io.butler.bet.sleeper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeperLiveWaiverSharedSummaryContextBf853Test {

    @Test
    void dashboardSummaryReusesOneExactLiveRosterObservation() throws Exception {
        String snapshot = source(
            "bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperLiveWaiverSharedTargetContext.java");
        String shared = source(
            "bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperLiveWaiverSharedSummaryContext.java");

        assertEquals(1, occurrences(snapshot, "client.getLeagueRosters(leagueId)"));
        assertTrue(shared.contains("new SleeperPersonalizedTargetService(database, targets, snapshot)"));
        assertTrue(shared.contains("parser.parseRosters(snapshot.rosters(target.sleeperLeagueId()))"));
        assertTrue(shared.contains("database, target.sleeperLeagueId(), sharedRosters"));
        assertTrue(shared.contains("target.sleeperLeagueId(), sharedRosters).inspect(target, summary)"));
    }

    @Test
    void bf629AndBf639SharedPathsKeepExactLeaguePinningAndLiveTransactionCheck() throws Exception {
        String actionability = source(
            "bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperLiveWaiverRecommendationActionabilityRevalidation.java");
        String convergence = source(
            "bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperLiveWaiverPostTransactionRosterConvergence.java");

        assertTrue(actionability.contains("BF-853 BLOCKED: shared BF-629 roster snapshot requested for a different league"));
        assertTrue(actionability.contains("new SleeperClient().getLeagueTransactions(leagueId, round)"));
        assertTrue(convergence.contains("BF-853 BLOCKED: shared BF-639 roster snapshot requested for a different league"));

        assertTrue(actionability.contains("public SleeperLiveWaiverRecommendationActionabilityRevalidation(Database database)"));
        assertTrue(convergence.contains("public SleeperLiveWaiverPostTransactionRosterConvergence()"));
    }

    @Test
    void dashboardCliUsesSharedCompositionWithoutWeakeningStandaloneClasses() throws Exception {
        String cli = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.java");
        int run = cli.indexOf("static int runEmbedded");
        int print = cli.indexOf("    static void print(", run);
        assertTrue(run >= 0 && print > run);
        String composition = cli.substring(run, print);

        assertTrue(composition.contains("new SleeperLiveWaiverSharedSummaryContext(database).resolve(leagueId)"));
        assertTrue(composition.contains("ButlerPersonalizedTargetCliSupport.printVerified(resolved.target())"));
        assertTrue(composition.contains("print(resolved.summary(), resolved.convergence())"));
        assertFalse(composition.contains("ButlerPersonalizedTargetCliSupport.verify(database, leagueId)"));
        assertFalse(composition.contains("new SleeperLiveWaiverLatestGovernedDecisionSummary(database)"));
        assertFalse(composition.contains("new SleeperLiveWaiverPostTransactionRosterConvergence()"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
    }

    private static String source(String relativePath) throws Exception {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("BF-853 test could not locate " + relativePath);
    }
}
