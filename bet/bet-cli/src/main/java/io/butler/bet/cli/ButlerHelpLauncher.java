package io.butler.bet.cli;

/** Preserves the existing global help surface and appends governed manual Sleeper lifecycle guidance. */
public final class ButlerHelpLauncher {
    private ButlerHelpLauncher() {}

    public static void main(String[] args) {
        ButlerLauncher.main(args == null ? new String[0] : args);
        printManualCounterUsage();
    }

    static void printManualCounterUsage() {
        System.out.println();
        System.out.println("Governed manual Sleeper counter lifecycle:");
        System.out.println("  butler trade counter-handoff <trusted-grant-id>");
        System.out.println("  butler trade counter-status <trusted-grant-id>");
        System.out.println("    Local-only trade lifecycle inspection; does not infer current Sleeper completion.");
        System.out.println("  butler trade counter-reconcile <trusted-grant-id> <sleeper-week>");
        System.out.println("    Explicit-week official GET-only Sleeper transaction evidence; no local finalization.");
        System.out.println("  butler trade counter-finalize <trusted-grant-id> <sleeper-week>");
        System.out.println("    Rechecks GET-only evidence; only exact completed readback may finalize local success and consume authorization.");
        System.out.println("  butler trade counter-message-status <trusted-grant-id>");
        System.out.println("    Local-only message lifecycle inspection; Butler does not send the message.");
        System.out.println("  butler trade counter-message-ack <trusted-grant-id> [--confirm SENT_EXACT_MESSAGE]");
        System.out.println("    Records explicit human evidence that the exact trusted message was sent outside Butler.");
        System.out.println("  butler trade counter-message-finalize <trusted-grant-id>");
        System.out.println("    Local-only finalization from pre-existing durable sent-message acknowledgment evidence.");
        System.out.println("  butler trade counter-no-action-ack <trusted-grant-id> [--confirm NO_EXTERNAL_ACTION_TAKEN]");
        System.out.println("    Alternate recovery path for either manual action when the exact presented handoff was not acted on externally.");
        System.out.println("    Records local evidence only; it does not mark the attempt FAILED or consume authorization.");
        System.out.println("  butler trade counter-no-action-finalize <trusted-grant-id>");
        System.out.println("    Separately finalizes durable exact no-action evidence as local FAILED + authorization close.");
        System.out.println("    Any retry after no-action finalization requires a fresh explicit authorization.");
        System.out.println("  Sent-message evidence and no-action evidence are mutually exclusive for the same manual message handoff.");
        System.out.println("  Sleeper writes remain manual; Butler's official Sleeper API access for this lifecycle is read-only.");
        System.out.println("  See docs/manual-sleeper-counter-lifecycle.md for the full safety model and command sequence.");
    }
}
