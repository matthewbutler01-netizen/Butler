package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.intelligence.SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer;

import java.nio.file.Path;
import java.util.Comparator;

/** No-argument BF-587 operator surface for the complete BF-565 provider-points frame. */
public final class ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");

    private ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            print(new SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer(database).audit());
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }

    static void parse(String[] args) {
        if (args != null && args.length != 0) {
            throw new IllegalArgumentException("Usage: sleeperProviderNativeLineupSensitivityCorpusAudit");
        }
    }

    static void print(SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer.AuditReport report) {
        var summary = report.summary();
        System.out.println("Sleeper provider-native lineup-sensitivity fixed-frame corpus audit");
        System.out.println("Policy: " + report.policyId());
        System.out.println("Frame policy: " + report.framePolicy());
        System.out.println("Metric scope: " + report.metricScope());
        System.out.println("Source: " + report.source());
        System.out.println("Fixed-frame league-seasons: " + summary.fixedFrameLeagueSeasons());
        System.out.println("Distinct league IDs: " + summary.distinctLeagueIds());
        System.out.println("Distinct seasons: " + summary.distinctSeasons());
        System.out.println("Provider-native READY: " + summary.providerNativeReadyLeagueSeasons());
        System.out.println("Provider-native BLOCKED: " + summary.providerNativeBlockedLeagueSeasons());
        System.out.println("Downstream BF-518 audited: " + summary.downstreamAuditedLeagueSeasons());
        System.out.println("Downstream source exceptions retained: " + summary.sourceEvidenceUnavailableLeagueSeasons());
        System.out.println("League-seasons with available BF-518 cutoffs: "
            + summary.leagueSeasonsWithAvailableCutoffs());
        System.out.println("Available BF-518 cutoffs: " + summary.availableCutoffs());
        System.out.println("Excluded BF-518 cutoffs: " + summary.excludedCutoffs());
        System.out.println("Repository team-count distribution: " + summary.repositoryTeamCountDistribution());
        System.out.println("Common-week-count distribution: " + summary.commonWeekCountDistribution());
        System.out.println("BF-518 cutoff-state counts:");
        if (summary.cutoffStateCounts().isEmpty()) {
            System.out.println("  none");
        } else {
            summary.cutoffStateCounts().entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().name()))
                .forEach(entry -> System.out.println("  " + entry.getKey() + ": " + entry.getValue()));
        }

        System.out.println();
        System.out.println("Season | League | BF-586 state | selector state | scoring lane | scoring policy | BF-518 state | common weeks | teams | available/excluded cutoffs");
        for (var entry : report.entries()) {
            String bf518State = entry.downstreamAudit().map(value -> value.state().name()).orElse("n/a");
            String commonWeeks = entry.downstreamAudit()
                .map(value -> Integer.toString(value.sourceCommonUniverse().commonComparableWeeks().size()))
                .orElse("n/a");
            String teams = entry.downstreamAudit()
                .map(value -> Integer.toString(value.sourceCommonUniverse().teams().size()))
                .orElse("n/a");
            String availableCutoffs = entry.downstreamAudit()
                .map(value -> Long.toString(value.cutoffs().stream()
                    .filter(cutoff -> cutoff.state()
                        == io.butler.bet.intelligence.LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer
                            .CutoffState.AVAILABLE)
                    .count()))
                .orElse("n/a");
            String excludedCutoffs = entry.downstreamAudit()
                .map(value -> Long.toString(value.cutoffs().stream()
                    .filter(cutoff -> cutoff.state()
                        != io.butler.bet.intelligence.LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer
                            .CutoffState.AVAILABLE)
                    .count()))
                .orElse("n/a");

            System.out.println(entry.season()
                + " | " + entry.leagueName() + " [" + entry.leagueId() + "]"
                + " | " + entry.state()
                + " | " + entry.selection().state()
                + " | " + entry.selection().lane()
                + " | " + entry.selection().scoringPolicyId()
                + " | " + bf518State
                + " | " + commonWeeks
                + " | " + teams
                + " | " + availableCutoffs + "/" + excludedCutoffs);
            System.out.println("  scoring lane selector: " + entry.selection().policyId());
            if (entry.selection().providerAudit() != null) {
                var provider = entry.selection().providerAudit();
                System.out.println("  provider audit: " + provider.state()
                    + " | provider league=" + provider.providerLeagueId()
                    + " | source surface=" + provider.sourceSurface()
                    + " | points as-of=" + provider.providerPointsAsOf());
            }
            for (String blocker : entry.selection().blockers()) {
                System.out.println("  selector blocker: " + blocker);
            }
            entry.detail().ifPresent(detail -> System.out.println("  downstream exception: " + detail));
            entry.downstreamAudit().ifPresent(audit -> {
                System.out.println("  BF-518 common-universe state: " + audit.sourceCommonUniverse().commonUniverseState());
                for (var cutoff : audit.cutoffs()) {
                    System.out.println("  cutoff after week " + cutoff.cutoffAfterWeek()
                        + " | baseline=" + cutoff.baselineCommonWeeks()
                        + " | holdout=" + cutoff.futureHoldoutCommonWeeks()
                        + " | state=" + cutoff.state()
                        + " | team rows=" + cutoff.teams().size());
                }
            });
        }

        System.out.println();
        System.out.println("Selection rule: every distinct league-season with persisted Sleeper provider-points evidence is included exactly once before provider-native, BF-518, BF-521, candidate-study, or support-audit outcomes are observed. This command accepts no selection arguments.");
        System.out.println("Boundary: read-only evidence audit. BLOCKED and unavailable entries remain visible. BF-518 cutoff states are descriptive evidence, not BF-521 readiness, statistical confidence, candidate quality, or a threshold-selection score. This command does not fit, rank, select, recommend, or publish a threshold and does not evaluate managers.");
    }
}
