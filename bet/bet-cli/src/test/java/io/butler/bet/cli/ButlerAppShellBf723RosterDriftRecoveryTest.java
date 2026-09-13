package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerAppShellBf723RosterDriftRecoveryTest {

    @Test
    void recoveryRequiresExactBf610RosterDriftBeforeFirstWrite() throws Exception {
        String script = source("scripts/butler-recover-roster-drift.ps1");

        int preflight = script.indexOf("$preflight = Invoke-ButlerGradleTask -Task $bf610Task");
        int driftPrefix = script.indexOf("BF-610 BLOCKED: current roster membership drifted from BF-603/BF-602 frame; added=");
        int driftSuffix = script.indexOf("refresh BF-602/BF-603 and downstream live evidence before target-roster review");
        int exactGate = script.indexOf("$recoveryNeeded = $true", preflight);
        int firstWrite = script.indexOf(":bet:bet-cli:sleeperLiveWaiverSnapshotSync", exactGate);

        assertTrue(preflight >= 0);
        assertTrue(driftPrefix >= 0 && driftSuffix > driftPrefix);
        assertTrue(exactGate > preflight);
        assertTrue(firstWrite > exactGate, "BF-602 must remain downstream of exact BF-610 drift authorization");
        assertTrue(script.contains("BF-610 failed for a reason other than exact roster drift. No Butler evidence write was attempted."));
    }

    @Test
    void recoveryUsesOnlyTheFixedSixEvidenceStagesInGovernedOrder() throws Exception {
        String script = source("scripts/butler-recover-roster-drift.ps1");

        String[] tasks = {
            ":bet:bet-cli:sleeperLiveWaiverSnapshotSync",
            ":bet:bet-cli:sleeperLiveWaiverMarketAttentionSync",
            ":bet:bet-cli:sleeperLiveWaiverProductionHydration",
            ":bet:bet-cli:sleeperLiveWaiverAvailabilitySync",
            ":bet:bet-cli:sleeperLiveWaiverCurrentWeekStatSync",
            ":bet:bet-cli:sleeperLiveWaiverTargetRosterProductionHydration"
        };

        int previous = -1;
        for (String task : tasks) {
            assertEquals(1, occurrences(script, task), task + " should appear exactly once in the fixed recovery list");
            int at = script.indexOf(task);
            assertTrue(at > previous, "recovery task order must remain governed and deterministic");
            previous = at;
        }

        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverFinalRecommendationBundle"));
        assertFalse(script.contains(":bet:bet-cli:sleeperLiveWaiverRecommendationAuditCapture"));
        assertFalse(script.contains("Invoke-Expression"));
        assertFalse(script.contains("create_transaction"));
        assertFalse(script.contains("submitTransaction"));
        assertFalse(script.contains("/refresh"));
    }

    @Test
    void postRecoveryReadChecksPrecedeUnchangedAcceptance() throws Exception {
        String script = source("scripts/butler-recover-roster-drift.ps1");
        String wrapper = source("scripts/butler-recover-roster-drift.cmd");

        int postRoster = script.indexOf("BF-610 post-recovery target-roster verification");
        int postWaiver = script.indexOf("BF-615/BF-617 post-recovery waiver comparison verification", postRoster);
        int acceptance = script.indexOf("& $acceptanceCmd", postWaiver);

        assertTrue(postRoster >= 0 && postWaiver > postRoster && acceptance > postWaiver);
        assertTrue(script.contains("BF-723: starting unchanged BF-698 GET-only acceptance."));
        assertTrue(script.contains("app-league.txt"));
        assertTrue(script.contains("butler-acceptance-preflight.ps1"));
        assertTrue(script.contains("butler-acceptance.cmd"));

        assertTrue(wrapper.contains("-ExecutionPolicy Bypass"));
        assertTrue(wrapper.contains("butler-recover-roster-drift.ps1"));
        assertFalse(wrapper.toLowerCase().contains("set-executionpolicy"));

        byte[] scriptAscii = script.getBytes(StandardCharsets.US_ASCII);
        byte[] wrapperAscii = wrapper.getBytes(StandardCharsets.US_ASCII);
        assertEquals(script, new String(scriptAscii, StandardCharsets.US_ASCII));
        assertEquals(wrapper, new String(wrapperAscii, StandardCharsets.US_ASCII));
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
        throw new IOException("BF-723 test could not locate " + relativePath);
    }
}
