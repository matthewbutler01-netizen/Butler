package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerPersistentReadWorkerSurvivalBf821Test {

    @Test
    void embeddedReadFailuresReturnExitCodesInsteadOfExitingTheJvm() {
        assertEquals(2,
            ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.runEmbedded(new String[]{}));
        assertEquals(2,
            ButlerWaiverDashboardEvidenceBundleCli.runEmbedded(new String[]{}));
        assertEquals(2,
            ButlerSleeperLiveWaiverGovernedExplanationLookupCli.runEmbedded(new String[]{}));
    }

    @Test
    void standaloneEntrypointsStillPreserveProcessExitSemantics() throws Exception {
        String summary = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.java");
        String dashboard = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerWaiverDashboardEvidenceBundleCli.java");
        String explanation = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperLiveWaiverGovernedExplanationLookupCli.java");

        for (String cli : new String[]{summary, dashboard, explanation}) {
            assertTrue(cli.contains("int exitCode = runEmbedded(args);"));
            assertTrue(cli.contains("System.exit(exitCode);"));
        }
    }

    @Test
    void persistentWorkerUsesNonTerminatingEmbeddedReadPaths() throws Exception {
        String worker = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerReadOnlyJvmWorker.java");

        assertTrue(worker.contains(
            "ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.runEmbedded("));
        assertTrue(worker.contains(
            "ButlerWaiverDashboardEvidenceBundleCli.runEmbedded("));
        assertTrue(worker.contains(
            "ButlerSleeperLiveWaiverGovernedExplanationLookupCli.runEmbedded("));
        assertTrue(worker.contains("executeCapturedWithExitCode"));

        assertFalse(worker.contains(
            "ButlerSleeperLiveWaiverLatestGovernedDecisionSummaryCli.main("));
        assertFalse(worker.contains(
            "ButlerSleeperLiveWaiverGovernedExplanationLookupCli.main("));
        assertFalse(worker.contains(
            "ButlerSleeperLiveWaiverTargetRosterContextAuditCli.main("));
    }

    private static String source(String relativePath) throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("BF-821 test could not locate " + relativePath);
    }
}
