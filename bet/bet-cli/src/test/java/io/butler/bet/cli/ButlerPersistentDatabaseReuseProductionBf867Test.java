package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPersistentDatabaseReuseProductionBf867Test {

    @Test
    void directCliStillInitializesDatabaseEveryInvocation() throws Exception {
        String cli = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.java");

        int directStart = cli.indexOf("static int runEmbedded(String[] args)");
        int persistentStart = cli.indexOf("static int runPersistentWorkerEmbedded(String[] args)");
        assertTrue(directStart >= 0);
        assertTrue(persistentStart > directStart);

        String direct = cli.substring(directStart, persistentStart);
        assertTrue(direct.contains("Database database = new Database(Path.of(\"butler.db\"));"));
        assertTrue(direct.contains("database.initialize();"));
        assertFalse(direct.contains("bf866DatabaseHandle()"));
    }

    @Test
    void persistentWorkerSummaryUsesInitializedDatabaseReuseOnly() throws Exception {
        String cli = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.java");
        String worker = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerReadOnlyJvmWorker.java");

        int persistentStart = cli.indexOf("static int runPersistentWorkerEmbedded(String[] args)");
        int diagnosticStart = cli.indexOf("static int runDiagnosticEmbedded(String[] args)");
        assertTrue(persistentStart >= 0);
        assertTrue(diagnosticStart > persistentStart);

        String persistent = cli.substring(persistentStart, diagnosticStart);
        assertTrue(persistent.contains("Database database = bf866DatabaseHandle().database();"));
        assertFalse(persistent.contains("database.initialize();"));
        assertFalse(persistent.contains("new Database(Path.of(\"butler.db\"))"));

        assertTrue(worker.contains(
            "case LATEST_SUMMARY -> executeCapturedWithExitCode(() ->\n"
                + "                ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.runPersistentWorkerEmbedded("));
        assertFalse(worker.contains(
            "case LATEST_SUMMARY -> executeCapturedWithExitCode(() ->\n"
                + "                ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.runEmbedded("));
    }

    @Test
    void reusePublishesDatabaseOnlyAfterSuccessfulInitializationAndSharesNoConnection() throws Exception {
        String cli = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.java");

        assertTrue(cli.contains("synchronized (BF866_DATABASE_LOCK)"));
        assertTrue(cli.contains("if (bf866Database != null)"));
        assertTrue(cli.indexOf("database.initialize();") < cli.indexOf("bf866Database = database;"));
        assertTrue(cli.contains("new Bf866DatabaseHandle(bf866Database, false, 0.0)"));
        assertTrue(cli.contains("new Bf866DatabaseHandle(database, true, initializeMs)"));
        assertFalse(cli.contains("static Connection"));
        assertFalse(cli.contains("java.sql.Connection"));
        assertFalse(cli.contains("DriverManager"));
    }

    @Test
    void productionPathStillRerunsAllDecisionReadsOnEveryCall() throws Exception {
        String persistent = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.java");

        assertTrue(persistent.contains("ButlerPersonalizedTargetCliSupport.verify(database, leagueId)"));
        assertTrue(persistent.contains("new SleeperLiveWaiverLatestGovernedDecisionSummary(database).summarize(target)"));
        assertTrue(persistent.contains("new SleeperLiveWaiverPostTransactionRosterConvergence().inspect(target, summary)"));
        assertFalse(persistent.contains("static SleeperLiveWaiverLatestGovernedDecisionSummary.SummaryReport"));
        assertFalse(persistent.contains("static SleeperPersonalizedTargetService.VerifiedTarget"));
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
        throw new IllegalStateException("BF-867 test could not locate " + relativePath);
    }
}
