package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerWaiverDashboardEvidenceBundleBf712Test {

    @Test
    void threeReadAnalysesCanOverlapOnBoundedExecutor() throws Exception {
        CountDownLatch started = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        var task = (java.util.concurrent.Callable<String>) () -> {
            started.countDown();
            int now = active.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out");
                return "ok";
            } finally {
                active.decrementAndGet();
            }
        };

        Thread caller = new Thread(() -> {
            try {
                ButlerWaiverDashboardEvidenceBundleCli.runConcurrent(task, task, task);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        caller.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertEquals(3, peak.get());
        release.countDown();
        caller.join(Duration.ofSeconds(2).toMillis());
        assertFalse(caller.isAlive());
    }

    @Test
    void bundleUsesOneDatabaseOneTargetVerificationAndDeterministicSections() throws Exception {
        String bundle = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerWaiverDashboardEvidenceBundleCli.java");

        assertEquals(1, occurrences(bundle, "new Database(DATABASE_PATH)"));
        assertEquals(1, occurrences(bundle, "database.initialize()"));
        assertEquals(1, occurrences(bundle, "ButlerPersonalizedTargetCliSupport.verify(database, leagueId)"));
        assertTrue(bundle.contains("Executors.newFixedThreadPool(3)"));
        assertTrue(bundle.contains("new SleeperLiveWaiverLatestGovernedDecisionSummary(database).summarize(target)"));
        assertTrue(bundle.contains("new SleeperLiveWaiverComparisonExecutionBundle(database)"));
        assertTrue(bundle.contains("new SleeperLiveWaiverTargetRosterContextAudit(database)"));
        assertTrue(bundle.contains("emit(SUMMARY, summary)"));
        assertTrue(bundle.contains("emit(WAIVER_BOARD, waiverBoard)"));
        assertTrue(bundle.contains("emit(ROSTER_CONTEXT, rosterContext)"));
        assertTrue(bundle.contains("===BUTLER_WAIVER_BUNDLE:"));
        assertFalse(bundle.contains("create_transaction"));
        assertFalse(bundle.contains("submitTransaction"));
        assertFalse(bundle.contains("waiver_budget"));
    }

    @Test
    void existingRosterTaskExposesExplicitWaiverBundleModeWithoutExpandingDispatcherWhitelist() throws Exception {
        String rosterCli = source("bet/bet-cli/src/main/java/io/butler/bet/cli/ButlerSleeperLiveWaiverTargetRosterContextAuditCli.java");
        String dispatch = source("scripts/butler-direct-java-dispatch.ps1");

        assertTrue(rosterCli.contains("isWaiverDashboardBundle(args)"));
        assertTrue(rosterCli.contains("\"--waiver-dashboard-bundle\".equals(args[1])"));
        assertTrue(rosterCli.contains("ButlerWaiverDashboardEvidenceBundleCli.main(new String[]{args[0].trim()})"));
        assertFalse(dispatch.contains("':bet:bet-cli:waiverDashboardEvidenceBundle'"));
        assertTrue(dispatch.contains("':bet:bet-cli:sleeperLiveWaiverTargetRosterContextAudit'"));
    }

    @Test
    void dashboardScopedDispatcherUsesOneShotWorkerCacheAndFallsBackOutsideDashboard() throws Exception {
        String dispatch = source("scripts/butler-direct-java-dispatch.ps1");

        assertTrue(dispatch.contains("Get-ButlerDashboardAncestorPid"));
        assertTrue(dispatch.contains("'*butler-dashboard.ps1*'"));
        assertTrue(dispatch.contains(".bf712-waiver-cache"));
        assertTrue(dispatch.contains("$ageSeconds -gt 30"));
        assertTrue(dispatch.contains("Read-Bf712FreshCacheText -Path $cachePaths.Comparison"));
        assertTrue(dispatch.contains("Read-Bf712FreshCacheText -Path $cachePaths.Roster"));
        assertTrue(dispatch.contains("'--waiver-dashboard-bundle'"));
        assertTrue(dispatch.contains("Get-Bf712BundleSection -Text $bundleText -Name 'SUMMARY'"));
        assertTrue(dispatch.contains("Get-Bf712BundleSection -Text $bundleText -Name 'WAIVER_BOARD'"));
        assertTrue(dispatch.contains("Get-Bf712BundleSection -Text $bundleText -Name 'ROSTER_CONTEXT'"));
        assertTrue(dispatch.contains("& $java '--enable-native-access=ALL-UNNAMED' '-cp' $classPath $mainClass @mainArguments"));
        assertFalse(dispatch.contains("/refresh"));
        assertFalse(dispatch.contains("create_transaction"));
        assertFalse(dispatch.contains("submitTransaction"));
    }

    @Test
    void dispatcherRemainsAsciiOnly() throws Exception {
        String dispatch = source("scripts/butler-direct-java-dispatch.ps1");
        byte[] encoded = dispatch.getBytes(StandardCharsets.US_ASCII);
        assertEquals(dispatch, new String(encoded, StandardCharsets.US_ASCII));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
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
        throw new IOException("BF-712 test could not locate " + relativePath);
    }
}
