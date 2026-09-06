package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.ProviderPlayerWeekPointsEvidenceRepository;
import io.butler.bet.intelligence.LeagueScoringCoverageAnalyzer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only BF-565 audit of every persisted Sleeper provider-points league-season.
 * The universe is evidence-defined before any scoring or calibration result is observed.
 */
public final class SleeperProviderPointsCalibrationCorpusAudit {
    public static final String POLICY_ID =
        "sleeper-provider-points-calibration-corpus-audit-v1-complete-persisted-universe-read-only";
    private static final String SOURCE = SleeperSeasonProviderPointsEvidenceImporter.SOURCE;

    private final Database database;

    public SleeperProviderPointsCalibrationCorpusAudit(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public AuditReport audit() throws SQLException {
        var providerEvidence = new ProviderPlayerWeekPointsEvidenceRepository(database);
        var leagueRepository = new LeagueRepository(database);
        var coverageAnalyzer = new LeagueScoringCoverageAnalyzer(database);
        var calibration = new SleeperSeasonProviderPointsCalibration(database);

        List<AuditEntry> entries = new ArrayList<>();
        for (var ref : providerEvidence.findDistinctLeagueSeasons(SOURCE)) {
            var league = leagueRepository.findById(ref.leagueId())
                .orElseThrow(() -> new IllegalStateException(
                    "Persisted provider-points evidence references missing league: " + ref.leagueId()));
            int providerRows = providerEvidence.findLatestByLeagueSeason(
                ref.leagueId(), ref.season(), SOURCE).size();
            if (providerRows <= 0) {
                throw new IllegalStateException(
                    "Distinct provider-points league-season has no latest snapshot: "
                        + ref.leagueId() + "/" + ref.season());
            }

            var coverage = coverageAnalyzer.analyzeWeek(ref.leagueId());
            List<String> unsupportedKeys = coverage.rules().stream()
                .filter(rule -> rule.state() == LeagueScoringCoverageAnalyzer.RuleState.UNSUPPORTED_NONZERO)
                .map(LeagueScoringCoverageAnalyzer.RuleCoverage::statKey)
                .sorted()
                .toList();

            if (!coverage.exactScoringEligible()) {
                entries.add(new AuditEntry(
                    ref.leagueId(), league.getName(), ref.season(), providerRows,
                    coverage.state(), coverage.supportedNonzeroRules(), coverage.unsupportedNonzeroRules(),
                    unsupportedKeys, EntryState.RULE_INELIGIBLE,
                    Optional.empty(), Optional.of(coverage.reason())));
                continue;
            }

            try {
                var report = calibration.calibrate(ref.leagueId(), ref.season());
                if (report.providerRows() != providerRows) {
                    throw new IllegalStateException(
                        "BF-562 provider row count does not match BF-565 latest-snapshot count");
                }
                entries.add(new AuditEntry(
                    ref.leagueId(), league.getName(), ref.season(), providerRows,
                    coverage.state(), coverage.supportedNonzeroRules(), coverage.unsupportedNonzeroRules(),
                    unsupportedKeys, EntryState.CALIBRATED,
                    Optional.of(report), Optional.empty()));
            } catch (RuntimeException calibrationError) {
                entries.add(new AuditEntry(
                    ref.leagueId(), league.getName(), ref.season(), providerRows,
                    coverage.state(), coverage.supportedNonzeroRules(), coverage.unsupportedNonzeroRules(),
                    unsupportedKeys, EntryState.CALIBRATION_ERROR,
                    Optional.empty(), Optional.of(message(calibrationError))));
            }
        }

        List<AuditEntry> immutableEntries = List.copyOf(entries);
        return new AuditReport(POLICY_ID, SOURCE, immutableEntries, summarize(immutableEntries));
    }

    static CorpusSummary summarize(List<AuditEntry> entries) {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
        int ruleEligible = 0;
        int calibrated = 0;
        int calibrationErrors = 0;
        int withComparableRows = 0;
        int providerRows = 0;
        int comparableRows = 0;
        int exactMatches = 0;
        int withinOneHundredth = 0;
        EnumMap<SleeperSeasonProviderPointsCalibration.NonComparableReason, Integer> reasons =
            new EnumMap<>(SleeperSeasonProviderPointsCalibration.NonComparableReason.class);

        for (AuditEntry entry : entries) {
            providerRows += entry.providerRows();
            if (entry.coverageState() == LeagueScoringCoverageAnalyzer.CoverageState.COMPLETE) {
                ruleEligible++;
            }
            if (entry.state() == EntryState.CALIBRATION_ERROR) calibrationErrors++;
            if (entry.calibration().isEmpty()) continue;

            calibrated++;
            var report = entry.calibration().orElseThrow();
            comparableRows += report.comparableRows();
            exactMatches += report.metrics().exactMatches();
            withinOneHundredth += report.metrics().withinOneHundredth();
            if (report.comparableRows() > 0) withComparableRows++;
            report.nonComparableReasons().forEach((reason, count) -> reasons.merge(reason, count, Integer::sum));
        }

        return new CorpusSummary(
            entries.size(),
            ruleEligible,
            entries.size() - ruleEligible,
            calibrated,
            calibrationErrors,
            withComparableRows,
            providerRows,
            comparableRows,
            exactMatches,
            withinOneHundredth,
            Map.copyOf(reasons));
    }

    private static String message(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    public enum EntryState {
        RULE_INELIGIBLE,
        CALIBRATED,
        CALIBRATION_ERROR
    }

    public record AuditEntry(
        String leagueId,
        String leagueName,
        int season,
        int providerRows,
        LeagueScoringCoverageAnalyzer.CoverageState coverageState,
        int supportedNonzeroRules,
        int unsupportedNonzeroRules,
        List<String> unsupportedNonzeroKeys,
        EntryState state,
        Optional<SleeperSeasonProviderPointsCalibration.CalibrationReport> calibration,
        Optional<String> detail) {

        public AuditEntry {
            leagueId = requireText(leagueId, "leagueId");
            leagueName = requireText(leagueName, "leagueName");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
            if (providerRows <= 0) throw new IllegalArgumentException("providerRows must be positive");
            Objects.requireNonNull(coverageState, "coverageState must not be null");
            if (supportedNonzeroRules < 0 || unsupportedNonzeroRules < 0) {
                throw new IllegalArgumentException("rule counts must not be negative");
            }
            unsupportedNonzeroKeys = List.copyOf(Objects.requireNonNull(
                unsupportedNonzeroKeys, "unsupportedNonzeroKeys must not be null"));
            if (unsupportedNonzeroKeys.size() != unsupportedNonzeroRules) {
                throw new IllegalArgumentException("unsupported keys must match unsupported rule count");
            }
            Objects.requireNonNull(state, "state must not be null");
            calibration = Objects.requireNonNull(calibration, "calibration must not be null");
            detail = Objects.requireNonNull(detail, "detail must not be null")
                .map(value -> requireText(value, "detail"));

            if ((coverageState == LeagueScoringCoverageAnalyzer.CoverageState.COMPLETE)
                != (unsupportedNonzeroRules == 0)) {
                throw new IllegalArgumentException("complete coverage must match zero unsupported rules");
            }
            if ((state == EntryState.CALIBRATED) != calibration.isPresent()) {
                throw new IllegalArgumentException("only calibrated entries may carry a calibration report");
            }
            if (state == EntryState.RULE_INELIGIBLE
                && coverageState == LeagueScoringCoverageAnalyzer.CoverageState.COMPLETE) {
                throw new IllegalArgumentException("rule-ineligible entry cannot have complete coverage");
            }
            if (state != EntryState.CALIBRATED && detail.isEmpty()) {
                throw new IllegalArgumentException("non-calibrated entries require detail");
            }
            calibration.ifPresent(report -> {
                if (!leagueId.equals(report.leagueId()) || season != report.season()
                    || providerRows != report.providerRows()) {
                    throw new IllegalArgumentException("calibration provenance must match audit entry");
                }
            });
        }
    }

    public record CorpusSummary(
        int leagueSeasons,
        int ruleEligibleLeagueSeasons,
        int ruleIneligibleLeagueSeasons,
        int calibratedLeagueSeasons,
        int calibrationErrorLeagueSeasons,
        int leagueSeasonsWithComparableRows,
        int providerRows,
        int comparableRows,
        int exactMatches,
        int withinOneHundredth,
        Map<SleeperSeasonProviderPointsCalibration.NonComparableReason, Integer> nonComparableReasons) {

        public CorpusSummary {
            if (leagueSeasons < 0 || ruleEligibleLeagueSeasons < 0 || ruleIneligibleLeagueSeasons < 0
                || calibratedLeagueSeasons < 0 || calibrationErrorLeagueSeasons < 0
                || leagueSeasonsWithComparableRows < 0 || providerRows < 0 || comparableRows < 0
                || exactMatches < 0 || withinOneHundredth < 0) {
                throw new IllegalArgumentException("summary counts must not be negative");
            }
            if (ruleEligibleLeagueSeasons + ruleIneligibleLeagueSeasons != leagueSeasons) {
                throw new IllegalArgumentException("rule eligibility counts must reconcile");
            }
            if (calibratedLeagueSeasons + calibrationErrorLeagueSeasons > ruleEligibleLeagueSeasons) {
                throw new IllegalArgumentException("calibration states cannot exceed rule-eligible corpus");
            }
            if (leagueSeasonsWithComparableRows > calibratedLeagueSeasons
                || comparableRows > providerRows || exactMatches > comparableRows
                || withinOneHundredth > comparableRows) {
                throw new IllegalArgumentException("calibration summary counts are inconsistent");
            }
            nonComparableReasons = Map.copyOf(Objects.requireNonNull(
                nonComparableReasons, "nonComparableReasons must not be null"));
        }
    }

    public record AuditReport(
        String policyId,
        String source,
        List<AuditEntry> entries,
        CorpusSummary summary) {

        public AuditReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            source = requireText(source, "source");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
            summary = Objects.requireNonNull(summary, "summary must not be null");
            if (summary.leagueSeasons() != entries.size()) {
                throw new IllegalArgumentException("summary league-seasons must match entries");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
