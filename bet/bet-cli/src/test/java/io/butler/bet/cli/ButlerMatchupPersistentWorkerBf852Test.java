package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerMatchupPersistentWorkerBf852Test {

    @Test
    void workerExecutesPassiveMatchupBundleWithoutAutofill() throws Exception {
        String worker = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerReadOnlyJvmWorker.java");

        assertTrue(worker.contains("case \"MATCHUP_BUNDLE\" ->"));
        int start = worker.indexOf("case MATCHUP_BUNDLE -> executeCapturedWithExitCode");
        int end = worker.indexOf("case EXPLANATION_LOOKUP ->", start);
        assertTrue(start >= 0 && end > start);

        String block = worker.substring(start, end);
        assertTrue(block.contains("ButlerWeeklyMatchupEvidenceBundleCli.runEmbedded("));
        assertTrue(block.contains("new String[] {request.leagueId()}"));
        assertFalse(block.contains("--autofill"));
        assertFalse(block.contains("--weekly-matchup-bundle-autofill"));
    }

    @Test
    void stagingRoutesOnlyPassiveMatchupThroughAuthenticatedWorker() throws Exception {
        String transform = source("scripts/butler-app-bf852-matchup-persistent-worker-transform.ps1");
        String staging = source("scripts/butler-dashboard-bf715-transform.ps1");

        int bf840 = staging.indexOf("& $bf840Transform -CorePath $stagedCore");
        int bf852 = staging.indexOf("& $bf852Transform -CorePath $stagedCore -DashboardPath $DashboardPath");
        assertTrue(bf840 >= 0 && bf852 > bf840);

        assertTrue(transform.contains("/__butler/internal/matchup-bundle"));
        assertTrue(transform.contains("'MATCHUP_BUNDLE'"));
        assertTrue(transform.contains("X-Butler-Internal-Token"));
        assertTrue(transform.contains("$bundleText = if ($requestAutoFill)"));
        assertTrue(transform.contains("Invoke-ButlerReadOnlyTask -Task \":bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit\""));
        assertTrue(transform.contains("--weekly-matchup-bundle-autofill"));
        assertTrue(transform.contains("Invoke-Bf742DashboardWorkerRead -Path \\"/__butler/internal/matchup-bundle\\" -BoundaryName \\"BF-852\\""));
        assertFalse(transform.contains("Method = \"POST\""));
        assertFalse(transform.contains("submitTransaction"));
        assertFalse(transform.contains("setFaab"));
    }

    @Test
    void persistentWorkerWhitelistIncludesOnlyExplicitMatchupOperation() throws Exception {
        String helper = source("scripts/butler-persistent-core-worker.ps1");

        assertTrue(helper.contains("'MATCHUP_BUNDLE'"));
        assertTrue(helper.contains("ValidateSet("));
        assertFalse(helper.contains("weeklyMatchupWorkspace"));
        assertFalse(helper.contains("sleeperCurrentWeekMatchupSync"));
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
        throw new IllegalStateException("BF-852 test could not locate " + relativePath);
    }
}
