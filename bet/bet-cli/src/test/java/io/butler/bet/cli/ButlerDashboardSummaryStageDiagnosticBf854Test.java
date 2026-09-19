package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerDashboardSummaryStageDiagnosticBf854Test {

    @Test
    void normalSummaryWorkerOperationRemainsOnOriginalEntryPoint() throws Exception {
        String worker = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerReadOnlyJvmWorker.java");

        int normal = worker.indexOf("case LATEST_SUMMARY -> executeCapturedWithExitCode");
        int diagnostic = worker.indexOf("case LATEST_SUMMARY_DIAGNOSTIC -> executeCapturedWithExitCode");
        assertTrue(normal >= 0 && diagnostic > normal);

        String normalBlock = worker.substring(normal, diagnostic);
        assertTrue(normalBlock.contains("ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.runEmbedded("));
        assertFalse(normalBlock.contains("runDiagnosticEmbedded"));
    }

    @Test
    void diagnosticReportsExactProductionSummaryStagesInsideWorker() throws Exception {
        String cli = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.java");
        String summary = source(
            "bet/bet-cli/src/main/java/io/butler/bet/sleeper/SleeperLiveWaiverLatestGovernedDecisionSummary.java");

        assertTrue(cli.contains("static int runDiagnosticEmbedded(String[] args)"));
        assertTrue(cli.contains(".summarize(target, stages::put)"));
        assertTrue(cli.contains("BF854_STAGE %s_ms=%.3f"));
        assertTrue(cli.contains("BF854_BOUNDARY read_only=true; persistent_jvm=true; refresh=false; sleeper_write=false"));

        assertTrue(summary.contains("return summarize(target, StageObserver.NO_OP);"));
        assertTrue(summary.contains("timing.observe(\"bf629_actionability\""));
        assertTrue(summary.contains("timing.observe(\"bf631_lineage\""));
        assertTrue(summary.contains("timing.observe(\"bf633_age\""));
        assertTrue(summary.contains("timing.observe(\"assembly\""));
    }

    @Test
    void focusedScriptUsesConfiguredLeagueAndGovernedDataWithoutRefreshOrWrites() throws Exception {
        String script = source("scripts/butler-dashboard-summary-stage-diagnostic.ps1");

        assertTrue(script.contains("app-league.txt"));
        assertTrue(script.contains("butler.db"));
        assertTrue(script.contains("LATEST_SUMMARY_DIAGNOSTIC"));
        assertTrue(script.contains("Stage p50 (5 warm persistent-JVM samples)"));
        assertTrue(script.contains("BF-854 RESULT: COMPLETE"));
        assertFalse(script.contains("'/refresh'"));
        assertFalse(script.contains("POST"));
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
        throw new IllegalStateException("BF-854 test could not locate " + relativePath);
    }
}
