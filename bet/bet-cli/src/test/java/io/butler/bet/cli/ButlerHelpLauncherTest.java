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
        assertTrue(output.contains(
            "butler league season-lineup-capture-ranking-sensitivity-classification-evidence <league-id> <season>"));
        assertTrue(output.contains("0=LOW, 1=MODERATE, 2+=HIGH sensitivity"));
        assertTrue(output.contains("Requires complete available ranking-stability evidence"));
        assertTrue(output.contains("no partial classification is published"));
        assertTrue(output.contains(
            "butler league season-lineup-capture-ranking-change-frequency-evidence <league-id> <season>"));
        assertTrue(output.contains("changed required one-week-out scenarios divided by the complete perturbation count"));
        assertTrue(output.contains("six-decimal governed frequency"));
        assertTrue(output.contains("BF-508 movement magnitude remains separate"));
        assertTrue(output.contains("no frequency tier or combined score"));
        assertTrue(output.contains(
            "butler league lineup-capture-ranking-sensitivity-calibration-corpus-audit <start-season> <end-season>"));
        assertTrue(output.contains("temporally disjoint baseline and future-only holdout windows"));
        assertTrue(output.contains("Baseline requires at least five common weeks"));
        assertTrue(output.contains("future-only holdout requires at least four common weeks"));
        assertTrue(output.contains("Preserves cutoff exclusions and corpus breadth"));
        assertTrue(output.contains("fits no calibration threshold or confidence model"));
        assertTrue(output.contains(
            "butler league lineup-capture-ranking-sensitivity-calibration-corpus-readiness <start-season> <end-season>"));
        assertTrue(output.contains("six BF-521 structural-variation gates"));
        assertTrue(output.contains("multiple league IDs, seasons, available league-seasons, team-count strata, perturbation denominators"));
        assertTrue(output.contains("both retained and moved future-only rank outcomes"));
        assertTrue(output.contains("all six must pass for structural readiness"));
        assertTrue(output.contains("authorizes only later threshold-study methodology design"));
        assertTrue(output.contains("not sample-size adequacy or calibration"));
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
        assertTrue(output.contains("BF-508 sensitivity classes still use maximum absolute rank movement only"));
        assertTrue(output.contains("rank-change frequency does not alter the class"));
        assertTrue(output.contains("BF-512 frequency is deterministic changed-over-total perturbation context"));
        assertTrue(output.contains("not probability, confidence, significance, or prediction"));
        assertTrue(output.contains("historical calibration audit uses future weeks only as an out-of-window persistence comparison"));
        assertTrue(output.contains("a later rank is not true"));
        assertTrue(output.contains("a future rank change does not prove the earlier governed rank was wrong"));
        assertTrue(output.contains("team-cutoff rows are correlated within league-seasons"));
        assertTrue(output.contains("not automatically an independent sample size"));
        assertTrue(output.contains("BF-521 readiness gates test minimum structural variation only"));
        assertTrue(output.contains("does not establish statistical adequacy"));
        assertTrue(output.contains("permission to generate or fit threshold candidates"));
        assertTrue(output.contains("No arbitrary corpus-size sufficiency threshold"));
        assertTrue(output.contains("qualitative frequency threshold, probability model, or confidence score is authorized"));
        assertTrue(output.contains("Magnitude and frequency remain separate"));
        assertTrue(output.contains("combined magnitude-frequency score, frequency-adjusted rank"));
        assertTrue(output.contains("manager consistency/reliability label"));
        assertTrue(output.contains("coverage-adjusted composite, or cross-league manager comparison"));
        assertTrue(output.contains("See docs/governed-lineup-evidence.md for the evidence model and safety boundaries."));
        assertTrue(output.contains("See docs/lineup-capture-methodology.md for the governed capture formula and stop boundary."));
        assertTrue(output.contains("See docs/lineup-capture-comparability-methodology.md for shared-week pairwise contrast rules."));
        assertTrue(output.contains("See docs/league-lineup-capture-common-universe-methodology.md for neutral league-table rules."));
        assertTrue(output.contains("See docs/league-lineup-capture-ranking-methodology.md for governed lineup-capture ranking rules."));
        assertTrue(output.contains("See docs/league-lineup-capture-ranking-stability-methodology.md for deterministic rank-sensitivity rules."));
        assertTrue(output.contains("See docs/league-lineup-capture-ranking-sensitivity-classification-methodology.md for observed sensitivity classes."));
        assertTrue(output.contains("See docs/league-lineup-capture-ranking-change-frequency-methodology.md for observed changed-over-total frequency rules."));
        assertTrue(output.contains("See docs/league-lineup-capture-ranking-sensitivity-calibration-methodology.md for historical corpus-audit rules."));
        assertTrue(output.contains("See docs/league-lineup-capture-ranking-sensitivity-calibration-corpus-adequacy-methodology.md for structural-readiness rules."));
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
