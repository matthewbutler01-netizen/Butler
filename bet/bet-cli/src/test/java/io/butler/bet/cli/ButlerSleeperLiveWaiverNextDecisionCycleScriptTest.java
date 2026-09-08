package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerSleeperLiveWaiverNextDecisionCycleScriptTest {

    private static final List<String> ORDERED_TASKS = List.of(
        ":bet:bet-cli:sleeperLiveWaiverSnapshotSync",
        ":bet:bet-cli:sleeperLiveWaiverMarketAttentionSync",
        ":bet:bet-cli:sleeperLiveWaiverProductionHydration",
        ":bet:bet-cli:sleeperLiveWaiverAvailabilitySync",
        ":bet:bet-cli:sleeperLiveWaiverCurrentWeekStatSync",
        ":bet:bet-cli:sleeperLiveWaiverTargetRosterProductionHydration",
        ":bet:bet-cli:sleeperLiveWaiverFinalRecommendationBundle",
        ":bet:bet-cli:sleeperLiveWaiverRecommendationAuditCapture",
        ":bet:bet-cli:sleeperLiveWaiverLatestGovernedDecisionSummary");

    @Test
    void preflightRequiresCompletedConvergedBf640ReadyStateBeforeWrites() throws Exception {
        String script = script();

        assertTrue(script.contains("Decision status: TRANSACTION_ALREADY_COMPLETE"));
        assertTrue(script.contains("BF-629 live actionability: AUDITED_TRANSACTION_COMPLETE"));
        assertTrue(script.contains("BF-639 post-transaction roster convergence: POST_TRANSACTION_ROSTER_CONVERGED"));
        assertTrue(script.contains("Plan state: NEXT_DECISION_PLAN_READY"));
        assertTrue(script.contains("No BF-602/BF-603/etc. write stage was executed"));

        int preflight = script.indexOf("$requiredPreflight = @(");
        int steps = script.indexOf("$steps = @(");
        assertTrue(preflight >= 0 && steps > preflight,
            "BF-641 must validate preflight before defining/executing the write sequence");
    }

    @Test
    void pinsTheExactBf640NineStageOrder() throws Exception {
        String script = script();
        int stageBlock = script.indexOf("$steps = @(");
        assertTrue(stageBlock >= 0, "BF-641 stage block is missing");
        String stages = script.substring(stageBlock);

        int previous = -1;
        for (String task : ORDERED_TASKS) {
            int current = stages.indexOf("Task = \"" + task + "\"");
            assertTrue(current > previous, "BF-641 stage order drifted at " + task);
            previous = current;
        }
    }

    @Test
    void nativeStderrIsNonTerminatingOnlyAroundGradleAndStrictHandlingIsRestored() throws Exception {
        String script = script();

        assertTrue(script.contains("$ErrorActionPreference = \"Stop\""));
        assertTrue(script.contains("$previousErrorActionPreference = $ErrorActionPreference"));
        assertTrue(script.contains("$ErrorActionPreference = \"Continue\""));
        assertTrue(script.contains("$lines = & $gradle @gradleArgs 2>&1"));
        assertTrue(script.contains("$exitCode = $LASTEXITCODE"));
        assertTrue(script.contains("$ErrorActionPreference = $previousErrorActionPreference"));

        int save = script.indexOf("$previousErrorActionPreference = $ErrorActionPreference");
        int relax = script.indexOf("$ErrorActionPreference = \"Continue\"", save);
        int invoke = script.indexOf("$lines = & $gradle @gradleArgs 2>&1", relax);
        int exit = script.indexOf("$exitCode = $LASTEXITCODE", invoke);
        int restore = script.indexOf("$ErrorActionPreference = $previousErrorActionPreference", exit);
        assertTrue(save >= 0 && relax > save && invoke > relax && exit > invoke && restore > exit,
            "BF-642 must relax native stderr handling only around Gradle, capture LASTEXITCODE, then restore strict handling");
    }

    @Test
    void everyNativeGradleFailureStopsTheCycleBeforeLaterStages() throws Exception {
        String script = script();

        assertTrue(script.contains("$exitCode = $LASTEXITCODE"));
        assertTrue(script.contains("if ($exitCode -ne 0)"));
        assertTrue(script.contains("BF-641 STOPPED:"));
        assertTrue(script.contains("No later stage was executed."));
        assertFalse(script.contains("-ErrorAction SilentlyContinue"));
    }

    @Test
    void keepsSleeperTransactionExecutionOutsideTheOrchestratorBoundary() throws Exception {
        String script = script();

        assertTrue(script.contains("This runner never submits, cancels, or replaces a Sleeper transaction and never sets FAAB."));
        assertTrue(script.contains("BF-641 did not submit a Sleeper transaction."));
        assertFalse(script.contains("create_transaction"));
        assertFalse(script.contains("submitTransaction"));
    }

    private static String script() throws IOException {
        Path path = locateScript();
        return Files.readString(path);
    }

    private static Path locateScript() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int depth = 0; depth < 6 && current != null; depth++) {
            Path candidate = current.resolve("scripts/sleeper-live-waiver-next-decision-cycle.ps1");
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("BF-641 test could not locate scripts/sleeper-live-waiver-next-decision-cycle.ps1");
    }
}
