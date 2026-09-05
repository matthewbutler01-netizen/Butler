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
        System.out.println("  butler league team-week-lineup-capture-evidence <league-id> <team-id> <season> <week>");
        System.out.println("    Descriptive started-over-potential capture rate from complete governed points-gap evidence.");
        System.out.println("  butler league team-season-potential-lineup-evidence <league-id> <team-id> <season>");
        System.out.println("    Preserves blocked and incomplete observed roster weeks with an explicit qualifying denominator.");
        System.out.println("  butler league team-season-lineup-points-gap-evidence <league-id> <team-id> <season>");
        System.out.println("    Aggregates raw points-gap totals only over comparable complete observed roster weeks.");
        System.out.println("  butler league team-season-lineup-capture-evidence <league-id> <team-id> <season>");
        System.out.println("    Normalizes comparable started/potential totals while keeping coverage as a separate denominator.");
        System.out.println("  butler league team-pair-lineup-capture-contrast-evidence <league-id> <team-a-id> <team-b-id> <season>");
        System.out.println("    Recalculates both teams over their exact shared comparable weeks and reports a descriptive signed contrast.");
        System.out.println("  butler league season-potential-lineup-evidence <league-id> <season>");
        System.out.println("    Exposes team potential-lineup evidence in repository team-name order without ranking.");
        System.out.println("  butler league season-lineup-points-gap-evidence <league-id> <season>");
        System.out.println("    Exposes team points-gap evidence with each team's coverage denominator kept separate.");
        System.out.println("  butler league season-lineup-capture-evidence <league-id> <season>");
        System.out.println("    Exposes independently scoped team capture rates in repository team-name order without ranking.");
        System.out.println("  butler league season-lineup-capture-common-universe-evidence <league-id> <season>");
        System.out.println("    Recalculates every repository team over the exact all-team common comparable week intersection.");
        System.out.println("    Presents one neutral team-name-ordered table; no team is dropped to widen the common universe.");
        System.out.println("  butler league season-lineup-capture-ranking-evidence <league-id> <season>");
        System.out.println("    Ranks only the governed all-team common-universe lineup-capture rate when at least four common weeks exist.");
        System.out.println("    Every repository team must have an available common rate; otherwise no partial ranking is published.");
        System.out.println("  butler league season-lineup-capture-ranking-stability-evidence <league-id> <season>");
        System.out.println("    Omits each baseline common comparable week exactly once and recalculates deterministic rank/rate sensitivity.");
        System.out.println("    Requires an available baseline rank and at least five common weeks; no partial stability summary is published.");
        System.out.println("  butler league season-lineup-capture-ranking-sensitivity-classification-evidence <league-id> <season>");
        System.out.println("    Classifies observed maximum absolute rank movement only: 0=LOW, 1=MODERATE, 2+=HIGH sensitivity.");
        System.out.println("    Requires complete available ranking-stability evidence; no partial classification is published.");
        System.out.println("  butler league season-lineup-capture-ranking-change-frequency-evidence <league-id> <season>");
        System.out.println("    Reports changed required one-week-out scenarios divided by the complete perturbation count.");
        System.out.println("    Uses six-decimal governed frequency; BF-508 movement magnitude remains separate with no frequency tier or combined score.");
        System.out.println("  butler league lineup-capture-ranking-sensitivity-calibration-corpus-audit <start-season> <end-season>");
        System.out.println("    Audits persisted historical league-seasons with temporally disjoint baseline and future-only holdout windows.");
        System.out.println("    Baseline requires at least five common weeks; future-only holdout requires at least four common weeks.");
        System.out.println("    Preserves cutoff exclusions and corpus breadth; it fits no calibration threshold or confidence model.");
        System.out.println("  butler league lineup-capture-ranking-sensitivity-calibration-corpus-readiness <start-season> <end-season>");
        System.out.println("    Checks six BF-521 structural-variation gates over the governed BF-518 historical corpus.");
        System.out.println("    Gates require multiple league IDs, seasons, available league-seasons, team-count strata, perturbation denominators,");
        System.out.println("    and both retained and moved future-only rank outcomes; all six must pass for structural readiness.");
        System.out.println("    Structural readiness authorizes only later threshold-study methodology design; it is not sample-size adequacy or calibration.");
        System.out.println("  Boundary: potential uses observed provider configuration, not reconstructed historical startability.");
        System.out.println("  Pairwise contrast uses only shared comparable weeks; independently scoped season rates are not compared.");
        System.out.println("  The neutral common-universe table remains team-name ordered and contains no ranking or league benchmark arithmetic.");
        System.out.println("  The separate ranking surface permits lineup-capture rank only, not manager rank or manager efficiency.");
        System.out.println("  Ranking uses governed six-decimal rates with competition ties; gap and coverage never break ties.");
        System.out.println("  The four-week ranking floor is a governance threshold, not statistical confidence or skill evidence.");
        System.out.println("  Ranking stability is deterministic leave-one-common-week-out sensitivity; the baseline rank remains authoritative.");
        System.out.println("  Stability requires five common weeks so every perturbation retains the four-week ranking floor.");
        System.out.println("  BF-508 sensitivity classes still use maximum absolute rank movement only; rank-change frequency does not alter the class.");
        System.out.println("  BF-512 frequency is deterministic changed-over-total perturbation context, not probability, confidence, significance, or prediction.");
        System.out.println("  The historical calibration audit uses future weeks only as an out-of-window persistence comparison; a later rank is not true,");
        System.out.println("  corrected, or ground-truth rank, and a future rank change does not prove the earlier governed rank was wrong.");
        System.out.println("  Calibration team-cutoff rows are correlated within league-seasons and are not automatically an independent sample size.");
        System.out.println("  BF-521 readiness gates test minimum structural variation only; passing them does not establish statistical adequacy,");
        System.out.println("  generalization, a trusted threshold, or permission to generate or fit threshold candidates.");
        System.out.println("  No arbitrary corpus-size sufficiency threshold, qualitative frequency threshold, probability model, or confidence score is authorized.");
        System.out.println("  Magnitude and frequency remain separate; Butler computes no combined magnitude-frequency score, frequency-adjusted rank,");
        System.out.println("  sensitivity leaderboard, league-wide sensitivity score, manager consistency/reliability label, recommendation,");
        System.out.println("  skill/fault attribution, coverage-adjusted composite, or cross-league manager comparison.");
        System.out.println("  See docs/governed-lineup-evidence.md for the evidence model and safety boundaries.");
        System.out.println("  See docs/lineup-capture-methodology.md for the governed capture formula and stop boundary.");
        System.out.println("  See docs/lineup-capture-comparability-methodology.md for shared-week pairwise contrast rules.");
        System.out.println("  See docs/league-lineup-capture-common-universe-methodology.md for neutral league-table rules.");
        System.out.println("  See docs/league-lineup-capture-ranking-methodology.md for governed lineup-capture ranking rules.");
        System.out.println("  See docs/league-lineup-capture-ranking-stability-methodology.md for deterministic rank-sensitivity rules.");
        System.out.println("  See docs/league-lineup-capture-ranking-sensitivity-classification-methodology.md for observed sensitivity classes.");
        System.out.println("  See docs/league-lineup-capture-ranking-change-frequency-methodology.md for observed changed-over-total frequency rules.");
        System.out.println("  See docs/league-lineup-capture-ranking-sensitivity-calibration-methodology.md for historical corpus-audit rules.");
        System.out.println("  See docs/league-lineup-capture-ranking-sensitivity-calibration-corpus-adequacy-methodology.md for structural-readiness rules.");
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
