package io.butler.bet.cli;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueConfigurationObservationRepository;
import io.butler.bet.data.PlayerFantasyPositionObservationRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;
import io.butler.bet.intelligence.LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer;
import io.butler.bet.intelligence.LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer;
import io.butler.bet.intelligence.LineupSlotEligibilityPolicy;
import io.butler.bet.intelligence.SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** No-argument BF-587/BF-588/BF-589/BF-590 operator surface for the complete BF-565 provider-points frame. */
public final class ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli {
    private static final Path DATABASE_PATH = Path.of("butler.db");
    private static final String SLEEPER_SOURCE = "sleeper";

    private ButlerSleeperProviderNativeLineupSensitivityCorpusAuditCli() {}

    public static void main(String[] args) {
        try {
            parse(args);
            Database database = new Database(DATABASE_PATH);
            database.initialize();
            var report = new SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer(database).audit();
            print(report, collectStarterSlotDiagnostics(database, report));
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
        print(report, Map.of());
    }

    static void print(
        SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer.AuditReport report,
        Map<LeagueSeasonKey, StarterSlotDiagnostics> starterSlotDiagnostics) {
        Objects.requireNonNull(starterSlotDiagnostics, "starterSlotDiagnostics must not be null");
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
                var source = audit.sourceCommonUniverse();
                System.out.println("  BF-518 common-universe state: " + source.commonUniverseState());
                if (source.commonUniverseState()
                    == LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState
                        .UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS) {
                    printZeroCommonWeekDiagnostics(
                        source,
                        starterSlotDiagnostics.get(new LeagueSeasonKey(entry.leagueId(), entry.season())));
                }
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

    static Map<LeagueSeasonKey, StarterSlotDiagnostics> collectStarterSlotDiagnostics(
        Database database,
        SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer.AuditReport report) throws SQLException {
        Objects.requireNonNull(database, "database must not be null");
        Objects.requireNonNull(report, "report must not be null");
        var configurations = new LeagueConfigurationObservationRepository(database);
        var rosters = new TeamWeekRosterEvidenceRepository(database);
        var players = new PlayerRepository(database);
        var fantasyPositions = new PlayerFantasyPositionObservationRepository(database);
        var slotPolicy = new LineupSlotEligibilityPolicy();
        Map<LeagueSeasonKey, StarterSlotDiagnostics> result = new LinkedHashMap<>();

        for (var entry : report.entries()) {
            if (entry.downstreamAudit().isEmpty()) continue;
            var source = entry.downstreamAudit().orElseThrow().sourceCommonUniverse();
            if (source.commonUniverseState()
                != LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.CommonUniverseState
                    .UNAVAILABLE_NO_COMMON_COMPARABLE_WEEKS) {
                continue;
            }

            var configuration = configurations.findLatestForSeason(entry.leagueId(), entry.season(), SLEEPER_SOURCE)
                .orElseThrow(() -> new IllegalStateException(
                    "BF-589 diagnostic unavailable: no persisted Sleeper configuration for "
                        + entry.leagueId() + "/" + entry.season()));
            List<String> supportedStartingSlots = configuration.lineupSlots().stream()
                .filter(slot -> slotPolicy.ruleFor(slot).state()
                    == LineupSlotEligibilityPolicy.SlotState.STARTING_SUPPORTED)
                .toList();
            List<String> unsupportedSlots = configuration.lineupSlots().stream()
                .filter(slot -> slotPolicy.ruleFor(slot).state()
                    == LineupSlotEligibilityPolicy.SlotState.UNSUPPORTED)
                .toList();

            Map<Integer, Integer> starterCountDistribution = new TreeMap<>();
            List<io.butler.bet.domain.TeamWeekRosterEvidence> observedRosters = new ArrayList<>();
            int rosterSnapshots = 0;
            int snapshotsContainingZeroSentinel = 0;
            int literalZeroSentinelEntries = 0;
            for (var team : source.teams()) {
                for (var week : team.sourceSeasonPointsGap().weeks()) {
                    var roster = rosters.findLatest(team.teamId(), entry.season(), week.week(), SLEEPER_SOURCE)
                        .orElseThrow(() -> new IllegalStateException(
                            "BF-589 diagnostic unavailable: nested source references missing roster evidence for "
                                + team.teamId() + "/" + entry.season() + "/" + week.week()));
                    observedRosters.add(roster);
                    int starterCount = roster.providerStarterIds().size();
                    starterCountDistribution.merge(starterCount, 1, Integer::sum);
                    rosterSnapshots++;
                    int zeroCount = (int) roster.providerStarterIds().stream().filter("0"::equals).count();
                    if (zeroCount > 0) snapshotsContainingZeroSentinel++;
                    literalZeroSentinelEntries += zeroCount;
                }
            }

            List<OmittedSlotCandidateDiagnostics> omissionCandidates = new ArrayList<>();
            int oneShortSnapshots = 0;
            if (!supportedStartingSlots.isEmpty()) {
                for (var roster : observedRosters) {
                    if (roster.providerStarterIds().size() == supportedStartingSlots.size() - 1) oneShortSnapshots++;
                }
                for (int omittedOrdinal = 0; omittedOrdinal < supportedStartingSlots.size(); omittedOrdinal++) {
                    int compatibleSnapshots = 0;
                    for (var roster : observedRosters) {
                        if (roster.providerStarterIds().size() != supportedStartingSlots.size() - 1) continue;
                        if (isCompatibleWithSingleOmittedSlot(
                            roster.providerStarterIds(),
                            supportedStartingSlots,
                            omittedOrdinal,
                            slotPolicy,
                            players,
                            fantasyPositions)) {
                            compatibleSnapshots++;
                        }
                    }
                    omissionCandidates.add(new OmittedSlotCandidateDiagnostics(
                        omittedOrdinal,
                        supportedStartingSlots.get(omittedOrdinal),
                        oneShortSnapshots,
                        compatibleSnapshots));
                }
            }

            result.put(
                new LeagueSeasonKey(entry.leagueId(), entry.season()),
                new StarterSlotDiagnostics(
                    configuration.asOfDate(),
                    configuration.lineupSlots(),
                    supportedStartingSlots,
                    unsupportedSlots,
                    starterCountDistribution,
                    rosterSnapshots,
                    snapshotsContainingZeroSentinel,
                    literalZeroSentinelEntries,
                    oneShortSnapshots,
                    omissionCandidates));
        }
        return Map.copyOf(result);
    }

    private static boolean isCompatibleWithSingleOmittedSlot(
        List<String> providerStarterIds,
        List<String> supportedStartingSlots,
        int omittedOrdinal,
        LineupSlotEligibilityPolicy slotPolicy,
        PlayerRepository players,
        PlayerFantasyPositionObservationRepository fantasyPositions) throws SQLException {
        int starterOrdinal = 0;
        for (int slotOrdinal = 0; slotOrdinal < supportedStartingSlots.size(); slotOrdinal++) {
            if (slotOrdinal == omittedOrdinal) continue;
            String providerStarterId = providerStarterIds.get(starterOrdinal++);
            if ("0".equals(providerStarterId)) continue;

            var player = players.findByExternalId(providerStarterId)
                .orElseThrow(() -> new IllegalStateException(
                    "BF-590 diagnostic unavailable: no Butler player mapping for Sleeper starter "
                        + providerStarterId));
            var positions = fantasyPositions.findLatest(player.getId(), SLEEPER_SOURCE)
                .orElseThrow(() -> new IllegalStateException(
                    "BF-590 diagnostic unavailable: no Sleeper fantasy-position observation for starter "
                        + providerStarterId));
            if (!slotPolicy.isPlayerEligible(
                supportedStartingSlots.get(slotOrdinal), positions.providerFantasyPositions())) {
                return false;
            }
        }
        return true;
    }

    private static void printZeroCommonWeekDiagnostics(
        LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer.LeagueCommonUniverseReport source,
        StarterSlotDiagnostics starterSlotDiagnostics) {
        System.out.println("  BF-588 zero-common-week diagnostics: descriptive source evidence only; all-team intersection remains authoritative.");
        if (starterSlotDiagnostics != null) {
            System.out.println("  BF-589 starter-slot count diagnostics: persisted evidence only; no reconstruction or padding.");
            System.out.println("    configuration as-of: " + starterSlotDiagnostics.configurationAsOf());
            System.out.println("    persisted roster_positions: " + starterSlotDiagnostics.rosterPositions());
            System.out.println("    supported starting slots: " + starterSlotDiagnostics.supportedStartingSlots());
            System.out.println("    supported starting-slot count: " + starterSlotDiagnostics.supportedStartingSlots().size());
            System.out.println("    unsupported roster positions: " + starterSlotDiagnostics.unsupportedSlots());
            System.out.println("    observed ordered starter-array length distribution: "
                + starterSlotDiagnostics.starterCountDistribution());
            System.out.println("    roster snapshots: " + starterSlotDiagnostics.rosterSnapshots()
                + " | snapshots containing literal 0 sentinel: "
                + starterSlotDiagnostics.snapshotsContainingZeroSentinel()
                + " | literal 0 sentinel entries: " + starterSlotDiagnostics.literalZeroSentinelEntries());
            System.out.println("  BF-589 diagnostic boundary: these counts do not authorize dropping a configured slot, padding a starter array, reordering starters, or reconstructing missing starter identities.");

            System.out.println("  BF-590 ordered single-omitted-slot compatibility: observed starter order and identities remain unchanged.");
            System.out.println("    exactly-one-short snapshots evaluated: "
                + starterSlotDiagnostics.oneShortSnapshots() + "/" + starterSlotDiagnostics.rosterSnapshots());
            for (var candidate : starterSlotDiagnostics.omissionCandidates()) {
                System.out.println("    omit ordinal " + candidate.omittedOrdinal()
                    + " " + candidate.slot()
                    + " | compatible snapshots=" + candidate.compatibleSnapshots()
                    + "/" + candidate.evaluatedSnapshots()
                    + " | fully compatible=" + candidate.fullyCompatible());
            }
            List<OmittedSlotCandidateDiagnostics> fullyCompatible = starterSlotDiagnostics.omissionCandidates().stream()
                .filter(OmittedSlotCandidateDiagnostics::fullyCompatible)
                .toList();
            List<String> fullyCompatibleLabels = fullyCompatible.stream()
                .map(OmittedSlotCandidateDiagnostics::slot)
                .distinct()
                .toList();
            System.out.println("    fully compatible candidate ordinals: "
                + fullyCompatible.stream().map(OmittedSlotCandidateDiagnostics::omittedOrdinal).toList());
            System.out.println("    fully compatible slot labels: " + fullyCompatibleLabels);
            String evidenceState;
            if (starterSlotDiagnostics.oneShortSnapshots() == 0) {
                evidenceState = "NOT_APPLICABLE_NO_EXACTLY_ONE_SHORT_SNAPSHOTS";
            } else if (fullyCompatible.isEmpty()) {
                evidenceState = "NO_FULLY_COMPATIBLE_SINGLE_OMISSION";
            } else if (fullyCompatible.size() == 1) {
                evidenceState = "UNIQUE_ORDINAL_COMPATIBILITY";
            } else if (fullyCompatibleLabels.size() == 1) {
                evidenceState = "UNIQUE_SLOT_LABEL_MULTIPLE_EQUIVALENT_ORDINALS";
            } else {
                evidenceState = "AMBIGUOUS_MULTIPLE_SLOT_LABELS";
            }
            System.out.println("    compatibility evidence state: " + evidenceState);
            System.out.println("  BF-590 diagnostic boundary: compatibility is descriptive structural evidence only. It does not remove a slot, rewrite historical configuration, reconstruct starters, or authorize downstream use of an inferred lineup shape.");
        }
        System.out.println("  Team individually-comparable evidence:");
        for (var team : source.teams()) {
            var season = team.sourceSeasonPointsGap();
            var stateCounts = new EnumMap<LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState, Integer>(
                LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.class);
            List<Integer> comparableWeeks = new ArrayList<>();
            for (var week : season.weeks()) {
                stateCounts.merge(week.state(), 1, Integer::sum);
                if (week.state() == LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.COMPARABLE_COMPLETE) {
                    comparableWeeks.add(week.week());
                }
            }
            System.out.println("    " + team.teamName() + " [" + team.teamId() + "]"
                + " | observed=" + season.aggregate().observedWeeks()
                + " | comparable=" + season.aggregate().comparableCompleteWeeks()
                + " | comparable weeks=" + comparableWeeks
                + " | week states=" + stateCounts);
        }

        TreeSet<Integer> observedWeeks = new TreeSet<>();
        for (var team : source.teams()) {
            for (var week : team.sourceSeasonPointsGap().weeks()) observedWeeks.add(week.week());
        }
        System.out.println("  Week-by-week all-team comparability coverage:");
        for (int weekNumber : observedWeeks) {
            int comparableTeams = 0;
            List<String> nonComparable = new ArrayList<>();
            for (var team : source.teams()) {
                var week = team.sourceSeasonPointsGap().weeks().stream()
                    .filter(value -> value.week() == weekNumber)
                    .findFirst();
                if (week.isEmpty()) {
                    nonComparable.add(team.teamName() + "=NO_OBSERVED_ROSTER_WEEK");
                    continue;
                }
                var evidence = week.orElseThrow();
                if (evidence.state()
                    == LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WeekState.COMPARABLE_COMPLETE) {
                    comparableTeams++;
                    continue;
                }
                String detail = team.teamName() + "=" + evidence.state();
                if (!evidence.blockers().isEmpty()) {
                    detail += "(" + String.join(" / ", evidence.blockers()) + ")";
                }
                nonComparable.add(detail);
            }
            System.out.println("    week " + weekNumber
                + " | comparable teams=" + comparableTeams + "/" + source.teams().size()
                + " | non-comparable=" + nonComparable);
        }
        System.out.println("  BF-588 diagnostic boundary: these rows explain loss of the all-team intersection; they do not authorize a partial common-universe fallback or change BF-518/BF-521 semantics.");
    }

    record LeagueSeasonKey(String leagueId, int season) {
        LeagueSeasonKey {
            if (leagueId == null || leagueId.isBlank()) throw new IllegalArgumentException("leagueId must not be blank");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
        }
    }

    record OmittedSlotCandidateDiagnostics(
        int omittedOrdinal,
        String slot,
        int evaluatedSnapshots,
        int compatibleSnapshots) {
        OmittedSlotCandidateDiagnostics {
            if (omittedOrdinal < 0) throw new IllegalArgumentException("omittedOrdinal must not be negative");
            if (slot == null || slot.isBlank()) throw new IllegalArgumentException("slot must not be blank");
            if (evaluatedSnapshots < 0 || compatibleSnapshots < 0 || compatibleSnapshots > evaluatedSnapshots) {
                throw new IllegalArgumentException("invalid BF-590 candidate counts");
            }
        }

        boolean fullyCompatible() {
            return evaluatedSnapshots > 0 && compatibleSnapshots == evaluatedSnapshots;
        }
    }

    record StarterSlotDiagnostics(
        LocalDate configurationAsOf,
        List<String> rosterPositions,
        List<String> supportedStartingSlots,
        List<String> unsupportedSlots,
        Map<Integer, Integer> starterCountDistribution,
        int rosterSnapshots,
        int snapshotsContainingZeroSentinel,
        int literalZeroSentinelEntries,
        int oneShortSnapshots,
        List<OmittedSlotCandidateDiagnostics> omissionCandidates) {
        StarterSlotDiagnostics {
            Objects.requireNonNull(configurationAsOf, "configurationAsOf must not be null");
            rosterPositions = List.copyOf(Objects.requireNonNull(rosterPositions, "rosterPositions must not be null"));
            supportedStartingSlots = List.copyOf(Objects.requireNonNull(
                supportedStartingSlots, "supportedStartingSlots must not be null"));
            unsupportedSlots = List.copyOf(Objects.requireNonNull(unsupportedSlots, "unsupportedSlots must not be null"));
            starterCountDistribution = Map.copyOf(Objects.requireNonNull(
                starterCountDistribution, "starterCountDistribution must not be null"));
            omissionCandidates = List.copyOf(Objects.requireNonNull(
                omissionCandidates, "omissionCandidates must not be null"));
            if (rosterSnapshots < 0 || snapshotsContainingZeroSentinel < 0 || literalZeroSentinelEntries < 0
                || snapshotsContainingZeroSentinel > rosterSnapshots || oneShortSnapshots < 0
                || oneShortSnapshots > rosterSnapshots) {
                throw new IllegalArgumentException("invalid starter-slot diagnostic counts");
            }
            int distributedSnapshots = starterCountDistribution.values().stream().mapToInt(Integer::intValue).sum();
            if (distributedSnapshots != rosterSnapshots) {
                throw new IllegalArgumentException("starter-count distribution must equal roster snapshot count");
            }
            if (!supportedStartingSlots.isEmpty() && omissionCandidates.size() != supportedStartingSlots.size()) {
                throw new IllegalArgumentException("BF-590 omission candidates must cover every supported starting ordinal");
            }
        }
    }
}
