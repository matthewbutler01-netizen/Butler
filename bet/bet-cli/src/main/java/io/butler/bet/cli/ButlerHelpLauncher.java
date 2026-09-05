package io.butler.bet.cli;

/** Preserves the existing global help surface and appends governed evidence and manual lifecycle guidance. */
public final class ButlerHelpLauncher {
    private ButlerHelpLauncher() {}

    public static void main(String[] args) {
        ButlerLauncher.main(args == null ? new String[0] : args);
        printGovernedLineupEvidenceUsage();
        printManualCounterUsage();
    }

    static void printGovernedLineupEvidenceUsage() {
        System.out.println();
        System.out.println("Governed lineup evidence:");
        System.out.println("  butler league team-week-potential-lineup <league-id> <team-id> <season> <week>");
        System.out.println("    Recalculates retrospective legal potential lineup points from governed production evidence.");
        System.out.println("  butler league team-week-started-lineup-evidence <league-id> <team-id> <season> <week>");
        System.out.println("    Scores the exact persisted Sleeper starter order; explicit starter 0 remains an empty slot.");
        System.out.println("  butler league team-week-lineup-points-gap-evidence <league-id> <team-id> <season> <week>");
        System.out.println("    Complete-lineup-only descriptive potential-minus-started points gap for one observed week.");
        System.out.println("  butler league team-season-potential-lineup-evidence <league-id> <team-id> <season>");
        System.out.println("    Preserves blocked and incomplete observed roster weeks with an explicit qualifying denominator.");
        System.out.println("  butler league team-season-lineup-points-gap-evidence <league-id> <team-id> <season>");
        System.out.println("    Aggregates raw points-gap totals only over comparable complete observed roster weeks.");
        System.out.println("  butler league season-potential-lineup-evidence <league-id> <season>");
        System.out.println("    Exposes team potential-lineup evidence in repository team-name order without ranking.");
        System.out.println("  butler league season-lineup-points-gap-evidence <league-id> <season>");
        System.out.println("    Exposes team points-gap evidence with each team's coverage denominator kept separate.");
        System.out.println("  Boundary: potential uses observed provider configuration, not reconstructed historical startability.");
        System.out.println("  Points-gap evidence is descriptive only: no efficiency percentage, manager score, rank, tier,");
        System.out.println("  recommendation, intent, fault, or skill attribution is computed.");
        System.out.println("  See docs/governed-lineup-evidence.md for the evidence model and safety boundaries.");
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
        System.out.println("    For trades only, exact completed readback may supersede an earlier unfinalized no-action acknowledgment while preserving that acknowledgment as immutable history.");
        System.out.println("    Once no-action was finalized FAILED + CONSUME, later exact completed readback never rewrites local history; Butler records a post-closure external-action discrepancy for investigation.");
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
