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
            "butler league team-pair-lineup-capture-contrast-evidence <league-id> <team-a-id> <team-b-id> <season>"));
        assertTrue(output.contains("exact shared comparable weeks"));
        assertTrue(output.contains(
            "butler league season-potential-lineup-evidence <league-id> <season>"));
        assertTrue(output.contains(
            "butler league season-lineup-points-gap-evidence <league-id> <season>"));
        assertTrue(output.contains(
            "butler league season-lineup-capture-evidence <league-id> <season>"));
        assertTrue(output.contains("independently scoped team capture rates"));
        assertTrue(output.contains(
            "butler league season-lineup-capture-common-universe-evidence <league-id> <season>"));
        assertTrue(output.contains("exact all-team common comparable week intersection"));
        assertTrue(output.contains("no team is dropped to widen the common universe"));
        assertTrue(output.contains(
            "butler league season-lineup-capture-ranking-evidence <league-id> <season>"));
        assertTrue(output.contains("at least four common weeks exist"));
        assertTrue(output.contains("otherwise no partial ranking is published"));
        assertTrue(output.contains(
            "butler league season-lineup-capture-ranking-stability-evidence <league-id> <season>"));
        assertTrue(output.contains("Omits each baseline common comparable week exactly once"));
        assertTrue(output.contains("at least five common weeks"));
        assertTrue(output.contains("no partial stability summary is published"));
        assertTrue(output.contains("not reconstructed historical startability"));
        assertTrue(output.contains("independently scoped season rates are not compared"));
        assertTrue(output.contains("neutral common-universe table remains team-name ordered"));
        assertTrue(output.contains("separate ranking surface permits lineup-capture rank only, not manager rank or manager efficiency"));
        assertTrue(output.contains("governed six-decimal rates with competition ties"));
        assertTrue(output.contains("gap and coverage never break ties"));
        assertTrue(output.contains("four-week ranking floor is a governance threshold, not statistical confidence or skill evidence"));
        assertTrue(output.contains("Ranking stability is deterministic leave-one-common-week-out sensitivity"));
        assertTrue(output.contains("baseline rank remains authoritative"));
        assertTrue(output.contains("Stability requires five common weeks"));
        assertTrue(output.contains("every perturbation retains the four-week ranking floor"));
        assertTrue(output.contains("No stable/unstable tier, confidence interval, probability, manager consistency/reliability label"));
        assertTrue(output.contains("manager grade, percentile, recommendation, intent, fault, skill attribution, league-wide stability score"));
        assertTrue(output.contains("coverage-adjusted composite, stability-adjusted replacement rank, or cross-league stability comparison is computed"));
        assertTrue(output.contains("See docs/governed-lineup-evidence.md for the evidence model and safety boundaries."));
        assertTrue(output.contains("See docs/lineup-capture-methodology.md for the governed capture formula and stop boundary."));
        assertTrue(output.contains("See docs/lineup-capture-comparability-methodology.md for shared-week pairwise contrast rules."));
        assertTrue(output.contains("See docs/league-lineup-capture-common-universe-methodology.md for neutral league-table rules."));
        assertTrue(output.contains("See docs/league-lineup-capture-ranking-methodology.md for governed lineup-capture ranking rules."));
        assertTrue(output.contains("See docs/league-lineup-capture-ranking-stability-methodology.md for deterministic rank-sensitivity rules."));
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
