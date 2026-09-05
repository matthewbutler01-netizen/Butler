package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerHelpLauncherTest {
    @Test
    void governedLineupHelpSurfacesFullEvidenceFamilyAndPreservesNonAttributionBoundary() {
        String output = capture(ButlerHelpLauncher::printGovernedLineupEvidenceUsage);

        assertTrue(output.contains("Governed lineup evidence:"));
        assertTrue(output.contains(
            "butler league team-week-potential-lineup <league-id> <team-id> <season> <week>"));
        assertTrue(output.contains(
            "butler league team-week-started-lineup-evidence <league-id> <team-id> <season> <week>"));
        assertTrue(output.contains("explicit starter 0 remains an empty slot"));
        assertTrue(output.contains(
            "butler league team-week-lineup-points-gap-evidence <league-id> <team-id> <season> <week>"));
        assertTrue(output.contains(
            "butler league team-week-lineup-capture-evidence <league-id> <team-id> <season> <week>"));
        assertTrue(output.contains("Descriptive started-over-potential capture rate"));
        assertTrue(output.contains(
            "butler league team-season-potential-lineup-evidence <league-id> <team-id> <season>"));
        assertTrue(output.contains(
            "butler league team-season-lineup-points-gap-evidence <league-id> <team-id> <season>"));
        assertTrue(output.contains(
            "butler league team-season-lineup-capture-evidence <league-id> <team-id> <season>"));
        assertTrue(output.contains("coverage as a separate denominator"));
        assertTrue(output.contains(
            "butler league season-potential-lineup-evidence <league-id> <season>"));
        assertTrue(output.contains(
            "butler league season-lineup-points-gap-evidence <league-id> <season>"));
        assertTrue(output.contains(
            "butler league season-lineup-capture-evidence <league-id> <season>"));
        assertTrue(output.contains("no league average or ranking"));
        assertTrue(output.contains("not reconstructed historical startability"));
        assertTrue(output.contains("Lineup capture is descriptive normalization only"));
        assertTrue(output.contains("no manager efficiency, manager grade, rank, tier"));
        assertTrue(output.contains("recommendation, intent, fault, or skill attribution is computed"));
        assertTrue(output.contains("See docs/governed-lineup-evidence.md for the evidence model and safety boundaries."));
        assertTrue(output.contains("See docs/lineup-capture-methodology.md for the governed capture formula and stop boundary."));
    }

    @Test
    void manualCounterHelpSeparatesSuccessEvidenceNoActionRecoveryAndManualSleeperBoundary() {
        String output = capture(ButlerHelpLauncher::printManualCounterUsage);

        assertTrue(output.contains("butler trade counter-status <trusted-grant-id>"));
        assertTrue(output.contains("Local-only trade lifecycle inspection; does not infer current Sleeper completion."));
        assertTrue(output.contains("butler trade counter-reconcile <trusted-grant-id> <sleeper-week>"));
        assertTrue(output.contains("official GET-only Sleeper transaction evidence"));
        assertTrue(output.contains("butler trade counter-finalize <trusted-grant-id> <sleeper-week>"));
        assertTrue(output.contains("only exact completed readback may finalize local success and consume authorization"));
        assertTrue(output.contains("exact completed readback may supersede an earlier unfinalized no-action acknowledgment"));
        assertTrue(output.contains("preserving that acknowledgment as immutable history"));
        assertTrue(output.contains("Once no-action was finalized FAILED + CONSUME"));
        assertTrue(output.contains("never rewrites local history"));
        assertTrue(output.contains("post-closure external-action discrepancy for investigation"));
        assertTrue(output.contains("butler trade counter-message-status <trusted-grant-id>"));
        assertTrue(output.contains("Butler does not send the message"));
        assertTrue(output.contains("butler trade counter-message-ack <trusted-grant-id> [--confirm SENT_EXACT_MESSAGE]"));
        assertTrue(output.contains("sent outside Butler"));
        assertTrue(output.contains("butler trade counter-message-finalize <trusted-grant-id>"));
        assertTrue(output.contains("butler trade counter-no-action-ack <trusted-grant-id> [--confirm NO_EXTERNAL_ACTION_TAKEN]"));
        assertTrue(output.contains("not acted on externally"));
        assertTrue(output.contains("does not mark the attempt FAILED or consume authorization"));
        assertTrue(output.contains("butler trade counter-no-action-finalize <trusted-grant-id>"));
        assertTrue(output.contains("local FAILED + authorization close"));
        assertTrue(output.contains("fresh explicit authorization"));
        assertTrue(output.contains("mutually exclusive"));
        assertTrue(output.contains("Sleeper writes remain manual"));
        assertTrue(output.contains("official Sleeper API access for this lifecycle is read-only"));
    }

    private static String capture(Runnable runnable) {
        PrintStream previous = System.out;
        var bytes = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bytes));
            runnable.run();
            return bytes.toString();
        } finally {
            System.setOut(previous);
        }
    }
}
