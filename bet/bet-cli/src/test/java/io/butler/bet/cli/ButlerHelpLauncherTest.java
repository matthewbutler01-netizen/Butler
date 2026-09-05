package io.butler.bet.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ButlerHelpLauncherTest {
    @Test
    void manualCounterHelpSeparatesSuccessEvidenceNoActionRecoveryAndManualSleeperBoundary() {
        String output = capture(ButlerHelpLauncher::printManualCounterUsage);

        assertTrue(output.contains("butler trade counter-status <trusted-grant-id>"));
        assertTrue(output.contains("Local-only trade lifecycle inspection; does not infer current Sleeper completion."));
        assertTrue(output.contains("butler trade counter-reconcile <trusted-grant-id> <sleeper-week>"));
        assertTrue(output.contains("official GET-only Sleeper transaction evidence"));
        assertTrue(output.contains("butler trade counter-finalize <trusted-grant-id> <sleeper-week>"));
        assertTrue(output.contains("only exact completed readback may finalize local success and consume authorization"));
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
