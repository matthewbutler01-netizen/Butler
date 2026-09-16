package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerReadOnlyJvmWorkerBf817TeamBundleFailureIsolationTest {

    @Test
    void embeddedMyTeamValidationReturnsExitCodeInsteadOfTerminatingJvm() {
        int exitCode = ButlerMyTeamEvidenceBundleCli.runEmbedded(new String[0]);
        assertEquals(2, exitCode);
    }

    @Test
    void persistentWorkerCapturesExplicitNonZeroExitCodeWithoutThrowing() {
        ButlerReadOnlyJvmWorker.Execution execution =
            ButlerReadOnlyJvmWorker.executeCapturedWithExitCode(() -> {
                System.out.print("partial-output");
                System.err.print("governed-read-failed");
                return 2;
            });

        assertEquals(2, execution.exitCode());
        assertEquals("partial-output", execution.stdout());
        assertEquals("governed-read-failed", execution.stderr());
    }

    @Test
    void teamBundleRouteUsesNonExitingEmbeddedBoundary() throws Exception {
        String worker = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerReadOnlyJvmWorker.java");
        String bundle = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerMyTeamEvidenceBundleCli.java");

        assertTrue(worker.contains("case TEAM_BUNDLE -> executeCapturedWithExitCode"));
        assertTrue(worker.contains("ButlerMyTeamEvidenceBundleCli.runEmbedded"));
        assertFalse(worker.contains("case TEAM_BUNDLE -> executeCaptured(() -> ButlerSleeperLiveWaiverTargetRosterContextAuditCli.main"));

        assertTrue(bundle.contains("int exitCode = runEmbedded(args);"));
        assertTrue(bundle.contains("if (exitCode != 0) System.exit(exitCode);"));
        assertTrue(bundle.contains("static int runEmbedded(String[] args)"));
        assertTrue(bundle.contains("return 2;"));
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
        throw new IOException("BF-817 test could not locate " + relativePath);
    }
}
