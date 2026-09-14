package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWaiverRosterContextCoalescingBf762Test {
    @Test
    void dashboardUsesOneCoalescedComparisonContextBranch() throws Exception {
        String source = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerWaiverDashboardEvidenceBundleCli.java");

        assertTrue(source.contains("new SleeperLiveWaiverCoalescedComparisonEvidence(database)"));
        assertTrue(source.contains("runConcurrentPair("));
        assertTrue(source.contains("reports.second().bundle()"));
        assertTrue(source.contains("reports.second().rosterContext()"));
        assertFalse(source.contains("new SleeperLiveWaiverTargetRosterContextAudit(database)"));
        assertFalse(source.contains("new SleeperLiveWaiverComparisonEvidenceReuse(database)"));
    }

    @Test
    void slowRouteDiagnosticMeasuresSameCoalescedProductionPath() throws Exception {
        String source = source(
            "bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSlowRouteStageDiagnosticCli.java");

        assertTrue(source.contains("new SleeperLiveWaiverCoalescedComparisonEvidence(database)"));
        assertTrue(source.contains("runConcurrentPair("));
        assertTrue(source.contains("coalesced.rosterContextMs()"));
        assertFalse(source.contains("new SleeperLiveWaiverTargetRosterContextAudit(database)"));
        assertFalse(source.contains("new SleeperLiveWaiverComparisonStageDiagnostic(database)"));
    }

    private static String source(String relativePath) throws Exception {
        return Files.readString(locate(relativePath), StandardCharsets.UTF_8);
    }

    private static Path locate(String relativePath) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 7 && current != null; depth++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("BF-762 test could not locate " + relativePath);
    }
}
