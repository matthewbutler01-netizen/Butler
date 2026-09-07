package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.ProviderPlayerWeekPointsEvidenceRepository;
import io.butler.bet.sleeper.SleeperProviderNativeSeasonScoringAudit;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * BF-586 read-only audit of the complete persisted provider-points frame through governed
 * provider-native historical scoring and BF-518 lineup-sensitivity evidence.
 *
 * <p>The inclusion frame is fixed before any downstream outcome is observed: every distinct
 * persisted Sleeper provider-points league-season appears exactly once. BLOCKED and unavailable
 * entries remain visible. This analyzer does not score candidates, change readiness, fit a
 * threshold, estimate confidence, or evaluate managers.</p>
 */
public final class SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer {
    public static final String POLICY_ID =
        "sleeper-provider-native-lineup-sensitivity-corpus-audit-v1-bf565-fixed-frame-bf518-reuse-read-only-no-selection";
    public static final String FRAME_POLICY =
        "EVERY_DISTINCT_PERSISTED_SLEEPER_PROVIDER_POINTS_LEAGUE_SEASON_EXACTLY_ONCE_BEFORE_DOWNSTREAM_OUTCOME";
    public static final String METRIC_SCOPE =
        "DESCRIPTIVE_PROVIDER_NATIVE_SCORING_AND_BF518_LINEUP_SENSITIVITY_EVIDENCE_OVER_FIXED_PERSISTED_FRAME_NO_READINESS_CHANGE_NO_THRESHOLD_SELECTION";

    private final Database database;

    public SleeperProviderNativeLineupSensitivityCorpusAuditAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public AuditReport audit() throws SQLException {
        var providerEvidence = new ProviderPlayerWeekPointsEvidenceRepository(database);
        var leagues = new LeagueRepository(database);
        var selector = new HistoricalScoringLaneSelector(database);
        var commonUniverse = new LeagueSeasonLineupCaptureCommonUniverseEvidenceAnalyzer(database);

        List<AuditEntry> entries = new ArrayList<>();
        for (var ref : providerEvidence.findDistinctLeagueSeasons(SleeperProviderNativeSeasonScoringAudit.SOURCE)) {
            var league = leagues.findById(ref.leagueId())
                .orElseThrow(() -> new IllegalStateException(
                    "Persisted provider-points evidence references missing league: " + ref.leagueId()));
            var selection = selector.select(ref.leagueId(), ref.season());
            if (selection.lane() != HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE) {
                throw new IllegalStateException(
                    "BF-565 fixed-frame entry did not select provider-native historical scoring: "
                        + ref.leagueId() + "/" + ref.season());
            }

            if (!selection.ready()) {
                entries.add(new AuditEntry(
                    ref.leagueId(), league.getName(), ref.season(), EntryState.PROVIDER_NATIVE_BLOCKED,
                    selection, Optional.empty(), Optional.empty()));
                continue;
            }

            try {
                var source = commonUniverse.analyze(ref.leagueId(), ref.season());
                if (!HistoricalScoringLaneSelector.POLICY_ID.equals(source.scoringLaneSelectionPolicyId())
                    || source.scoringLane() != HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE
                    || !HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID.equals(source.scoringPolicyId())) {
                    throw new IllegalStateException(
                        "BF-586 downstream source did not preserve provider-native historical scoring provenance");
                }
                var bf518 = LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.LeagueSeasonAudit
                    .fromSource(source);
                entries.add(new AuditEntry(
                    ref.leagueId(), league.getName(), ref.season(), EntryState.DOWNSTREAM_AUDITED,
                    selection, Optional.of(bf518), Optional.empty()));
            } catch (IllegalStateException sourceUnavailable) {
                entries.add(new AuditEntry(
                    ref.leagueId(), league.getName(), ref.season(), EntryState.SOURCE_EVIDENCE_UNAVAILABLE,
                    selection, Optional.empty(), Optional.of(message(sourceUnavailable))));
            }
        }

        List<AuditEntry> immutableEntries = List.copyOf(entries);
        return new AuditReport(
            POLICY_ID, FRAME_POLICY, METRIC_SCOPE, SleeperProviderNativeSeasonScoringAudit.SOURCE,
            immutableEntries, summarize(immutableEntries));
    }

    static CorpusSummary summarize(List<AuditEntry> entries) {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
        int providerReady = 0;
        int providerBlocked = 0;
        int downstreamAudited = 0;
        int sourceUnavailable = 0;
        int withAvailableCutoffs = 0;
        int availableCutoffs = 0;
        int excludedCutoffs = 0;
        Set<String> leagueIds = new TreeSet<>();
        Set<Integer> seasons = new TreeSet<>();
        Map<Integer, Integer> teamCounts = new TreeMap<>();
        Map<Integer, Integer> commonWeekCounts = new TreeMap<>();
        EnumMap<LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffState, Integer> cutoffStates =
            new EnumMap<>(LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffState.class);

        for (AuditEntry entry : entries) {
            leagueIds.add(entry.leagueId());
            seasons.add(entry.season());
            if (entry.selection().ready()) providerReady++; else providerBlocked++;
            if (entry.state() == EntryState.SOURCE_EVIDENCE_UNAVAILABLE) sourceUnavailable++;
            if (entry.downstreamAudit().isEmpty()) continue;

            downstreamAudited++;
            var audit = entry.downstreamAudit().orElseThrow();
            int teamCount = audit.sourceCommonUniverse().teams().size();
            int commonWeekCount = audit.sourceCommonUniverse().commonComparableWeeks().size();
            teamCounts.merge(teamCount, 1, Integer::sum);
            commonWeekCounts.merge(commonWeekCount, 1, Integer::sum);

            boolean hasAvailable = false;
            for (var cutoff : audit.cutoffs()) {
                cutoffStates.merge(cutoff.state(), 1, Integer::sum);
                if (cutoff.state()
                    == LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffState.AVAILABLE) {
                    availableCutoffs++;
                    hasAvailable = true;
                } else {
                    excludedCutoffs++;
                }
            }
            if (hasAvailable) withAvailableCutoffs++;
        }

        return new CorpusSummary(
            entries.size(), leagueIds.size(), seasons.size(), providerReady, providerBlocked,
            downstreamAudited, sourceUnavailable, withAvailableCutoffs, availableCutoffs, excludedCutoffs,
            Map.copyOf(teamCounts), Map.copyOf(commonWeekCounts), Map.copyOf(cutoffStates));
    }

    private static String message(RuntimeException error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum EntryState {
        PROVIDER_NATIVE_BLOCKED,
        DOWNSTREAM_AUDITED,
        SOURCE_EVIDENCE_UNAVAILABLE
    }

    public record AuditEntry(
        String leagueId,
        String leagueName,
        int season,
        EntryState state,
        HistoricalScoringLaneSelector.Selection selection,
        Optional<LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.LeagueSeasonAudit> downstreamAudit,
        Optional<String> detail) {

        public AuditEntry {
            leagueId = requireText(leagueId, "leagueId");
            leagueName = requireText(leagueName, "leagueName");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(selection, "selection must not be null");
            downstreamAudit = Objects.requireNonNull(downstreamAudit, "downstreamAudit must not be null");
            detail = Objects.requireNonNull(detail, "detail must not be null")
                .map(value -> requireText(value, "detail"));

            if (!leagueId.equals(selection.leagueId()) || season != selection.season()
                || selection.lane() != HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE) {
                throw new IllegalArgumentException("BF-586 entry identity/lane must match provider-native selection");
            }
            if (state == EntryState.PROVIDER_NATIVE_BLOCKED) {
                if (selection.ready() || downstreamAudit.isPresent() || detail.isPresent()) {
                    throw new IllegalArgumentException("blocked provider-native entry must fail closed before downstream audit");
                }
            } else {
                if (!selection.ready()) {
                    throw new IllegalArgumentException("downstream BF-586 states require a READY provider-native selection");
                }
                if (state == EntryState.DOWNSTREAM_AUDITED) {
                    if (downstreamAudit.isEmpty() || detail.isPresent()) {
                        throw new IllegalArgumentException("audited entry requires BF-518 evidence and no error detail");
                    }
                    var audit = downstreamAudit.orElseThrow();
                    if (!leagueId.equals(audit.leagueId()) || !leagueName.equals(audit.leagueName())
                        || season != audit.season()) {
                        throw new IllegalArgumentException("BF-518 audit identity must match BF-586 entry");
                    }
                    var source = audit.sourceCommonUniverse();
                    if (!HistoricalScoringLaneSelector.POLICY_ID.equals(source.scoringLaneSelectionPolicyId())
                        || source.scoringLane() != HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE
                        || !HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID.equals(source.scoringPolicyId())) {
                        throw new IllegalArgumentException("BF-518 source must preserve provider-native scoring provenance");
                    }
                } else if (downstreamAudit.isPresent() || detail.isEmpty()) {
                    throw new IllegalArgumentException("source-unavailable entry requires detail and no partial BF-518 audit");
                }
            }
        }
    }

    public record CorpusSummary(
        int fixedFrameLeagueSeasons,
        int distinctLeagueIds,
        int distinctSeasons,
        int providerNativeReadyLeagueSeasons,
        int providerNativeBlockedLeagueSeasons,
        int downstreamAuditedLeagueSeasons,
        int sourceEvidenceUnavailableLeagueSeasons,
        int leagueSeasonsWithAvailableCutoffs,
        int availableCutoffs,
        int excludedCutoffs,
        Map<Integer, Integer> repositoryTeamCountDistribution,
        Map<Integer, Integer> commonWeekCountDistribution,
        Map<LeagueLineupCaptureRankingSensitivityCalibrationCorpusAuditAnalyzer.CutoffState, Integer> cutoffStateCounts) {

        public CorpusSummary {
            if (fixedFrameLeagueSeasons < 0 || distinctLeagueIds < 0 || distinctSeasons < 0
                || providerNativeReadyLeagueSeasons < 0 || providerNativeBlockedLeagueSeasons < 0
                || downstreamAuditedLeagueSeasons < 0 || sourceEvidenceUnavailableLeagueSeasons < 0
                || leagueSeasonsWithAvailableCutoffs < 0 || availableCutoffs < 0 || excludedCutoffs < 0) {
                throw new IllegalArgumentException("BF-586 summary counts must not be negative");
            }
            if (providerNativeReadyLeagueSeasons + providerNativeBlockedLeagueSeasons != fixedFrameLeagueSeasons
                || downstreamAuditedLeagueSeasons + sourceEvidenceUnavailableLeagueSeasons
                    != providerNativeReadyLeagueSeasons
                || leagueSeasonsWithAvailableCutoffs > downstreamAuditedLeagueSeasons
                || distinctLeagueIds > fixedFrameLeagueSeasons || distinctSeasons > fixedFrameLeagueSeasons) {
                throw new IllegalArgumentException("BF-586 summary counts are inconsistent");
            }
            repositoryTeamCountDistribution = Map.copyOf(Objects.requireNonNull(
                repositoryTeamCountDistribution, "repositoryTeamCountDistribution must not be null"));
            commonWeekCountDistribution = Map.copyOf(Objects.requireNonNull(
                commonWeekCountDistribution, "commonWeekCountDistribution must not be null"));
            cutoffStateCounts = Map.copyOf(Objects.requireNonNull(cutoffStateCounts, "cutoffStateCounts must not be null"));
        }
    }

    public record AuditReport(
        String policyId,
        String framePolicy,
        String metricScope,
        String source,
        List<AuditEntry> entries,
        CorpusSummary summary) {

        public AuditReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!FRAME_POLICY.equals(framePolicy)) throw new IllegalArgumentException("unexpected framePolicy");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            source = requireText(source, "source");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
            summary = Objects.requireNonNull(summary, "summary must not be null");
            if (!summary.equals(summarize(entries))) {
                throw new IllegalArgumentException("BF-586 summary must match complete fixed-frame entries");
            }
            for (int i = 1; i < entries.size(); i++) {
                AuditEntry previous = entries.get(i - 1);
                AuditEntry current = entries.get(i);
                if (previous.season() > current.season()
                    || (previous.season() == current.season()
                        && previous.leagueId().compareTo(current.leagueId()) >= 0)) {
                    throw new IllegalArgumentException("BF-586 entries must preserve BF-565 season/league ordering");
                }
            }
        }
    }
}
