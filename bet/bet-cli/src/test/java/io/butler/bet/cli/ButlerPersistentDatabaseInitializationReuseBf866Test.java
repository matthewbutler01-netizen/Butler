package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPersistentDatabaseInitializationReuseBf866Test {

    @Test
    void diagnosticReuseIsProcessScopedAndInitializesBeforePublication() throws Exception {
        String cli = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.java");

        assertTrue(cli.contains("private static final Object BF866_DATABASE_LOCK"));
        assertTrue(cli.contains("private static Database bf866Database;"));
        assertTrue(cli.contains("synchronized (BF866_DATABASE_LOCK)"));
        assertTrue(cli.contains("if (bf866Database != null)"));
        assertTrue(cli.contains("database.initialize();"));
        assertTrue(cli.indexOf("database.initialize();") < cli.indexOf("bf866Database = database;"));
        assertTrue(cli.contains("new Bf866DatabaseHandle(bf866Database, false, 0.0)"));
        assertTrue(cli.contains("new Bf866DatabaseHandle(database, true, initializeMs)"));
        assertFalse(cli.contains("static Connection"));
        assertFalse(cli.contains("DriverManager"));
    }

    @Test
    void productionWorkerUsesProvenReuseWhileDiagnosticOperationRemainsDedicated() throws Exception {
        String worker = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerReadOnlyJvmWorker.java");

        assertTrue(worker.contains("LATEST_SUMMARY_REUSE_DIAGNOSTIC"));
        assertTrue(worker.contains("runDatabaseReuseDiagnosticEmbedded("));
        assertTrue(worker.contains(
            "case LATEST_SUMMARY -> executeCapturedWithExitCode(() ->\n"
                + "                ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.runPersistentWorkerEmbedded("));
        assertFalse(worker.contains(
            "case LATEST_SUMMARY -> executeCapturedWithExitCode(() ->\n"
                + "                ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.runDatabaseReuseDiagnosticEmbedded("));
    }

    @Test
    void bothDiagnosticPathsEmitComparableExactDecisionSignatures() throws Exception {
        String cli = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.java");

        assertTrue(cli.contains("BF854_SIGNATURE "));
        assertTrue(cli.contains("BF866_SIGNATURE "));
        assertTrue(cli.contains("decisionSignature(target, summary, convergence)"));
        assertTrue(cli.contains("summary.bf629State().name()"));
        assertTrue(cli.contains("summary.bf631State().name()"));
        assertTrue(cli.contains("summary.bf633State().name()"));
        assertTrue(cli.contains("summary.state().name()"));
        assertTrue(cli.contains("convergence == null ? \"none\" : convergence.state().name()"));
    }

    @Test
    void runnerProvesFirstUseWarmReuseRestartAndReadOnlyBoundary() throws Exception {
        String script = source("scripts/butler-dashboard-db-init-reuse-diagnostic.ps1");

        assertTrue(script.contains("LATEST_SUMMARY_DIAGNOSTIC"));
        assertTrue(script.contains("LATEST_SUMMARY_REUSE_DIAGNOSTIC"));
        assertTrue(script.contains("first reuse call did not initialize the database"));
        assertTrue(script.contains("second reuse call repeated database initialization"));
        assertTrue(script.contains("new worker process did not initialize the database"));
        assertTrue(script.contains("decision_equivalence=EXACT"));
        assertTrue(script.contains("first_reuse_initialized=TRUE"));
        assertTrue(script.contains("warm_reuse_reinitialized=FALSE"));
        assertTrue(script.contains("restart_reinitialized=TRUE"));
        assertTrue(script.contains("shared_connection=false"));
        assertTrue(script.contains("production persistent-worker LATEST_SUMMARY uses proven reuse"));
        assertTrue(script.contains("direct CLI remains unchanged"));
        assertTrue(script.contains("BF-866 RESULT: COMPLETE"));
        assertFalse(script.contains("'/refresh'"));
        assertFalse(script.contains("submitTransaction"));
        assertFalse(script.contains("setFaab"));
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
        throw new IllegalStateException("BF-866 test could not locate " + relativePath);
    }
}
