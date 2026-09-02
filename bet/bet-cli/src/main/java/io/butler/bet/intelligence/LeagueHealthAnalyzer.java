package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.DraftPickRepository;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueValueFormatRepository;
import io.butler.bet.domain.League;
import io.butler.bet.domain.LeagueValueFormat;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Summarizes whether a persisted league has enough trustworthy local data for the core Butler
 * analysis workflows. Freshness is only evaluated when the caller supplies an explicit cutoff.
 * Movement readiness is reported independently because a league can be ready for current-value
 * analysis before it has two provider snapshots for change analysis.
 */
public final class LeagueHealthAnalyzer {
    private final LeagueRepository leagues;
    private final LeagueValueFormatRepository formats;
    private final DraftPickRepository draftPicks;
    private final LeagueAnalyzer leagueAnalyzer;
    private final LeagueValueSourceResolver sources;
    private final FranchiseValueReadinessAnalyzer franchiseReadiness;
    private final LeagueMovementReadinessAnalyzer movementReadiness;

    public LeagueHealthAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagues = new LeagueRepository(database);
        this.formats = new LeagueValueFormatRepository(database);
        this.draftPicks = new DraftPickRepository(database);
        this.leagueAnalyzer = new LeagueAnalyzer(database);
        this.sources = new LeagueValueSourceResolver(database);
        this.franchiseReadiness = new FranchiseValueReadinessAnalyzer(database);
        this.movementReadiness = new LeagueMovementReadinessAnalyzer(database);
    }

    public HealthReport analyze(String leagueId) throws SQLException {
        return analyzeInternal(leagueId, null, null);
    }

    public HealthReport analyze(String leagueId, String sourceOverride) throws SQLException {
        return analyzeInternal(leagueId, sourceOverride, null);
    }

    public HealthReport analyze(String leagueId, LocalDate minimumAsOfDate) throws SQLException {
        return analyzeInternal(leagueId, null,
            Objects.requireNonNull(minimumAsOfDate, "minimumAsOfDate must not be null"));
    }

    public HealthReport analyze(String leagueId, String sourceOverride,
                                LocalDate minimumAsOfDate) throws SQLException {
        return analyzeInternal(leagueId, sourceOverride,
            Objects.requireNonNull(minimumAsOfDate, "minimumAsOfDate must not be null"));
    }

    private HealthReport analyzeInternal(String leagueId, String sourceOverride,
                                         LocalDate minimumAsOfDate) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        League league = leagues.findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("league not found: " + normalizedLeagueId));
        var leagueReport = leagueAnalyzer.analyze(normalizedLeagueId);
        var format = formats.findByLeagueId(normalizedLeagueId).orElse(null);
        int pickCount = draftPicks.findByLeagueId(normalizedLeagueId).size();

        String source = null;
        boolean automaticSource = false;
        if (sourceOverride != null && !sourceOverride.isBlank()) {
            source = sourceOverride.trim();
        } else {
            try {
                source = sources.resolve(normalizedLeagueId);
                automaticSource = true;
            } catch (IllegalArgumentException unavailable) {
                // The league exists; missing/UNKNOWN format metadata is itself a health result.
            }
        }

        if (source == null) {
            return new HealthReport(
                league.getId(), league.getExternalId(), league.getName(),
                leagueReport.teamCount(), leagueReport.rosteredPlayers(), pickCount,
                format, false, null, false, minimumAsOfDate,
                HealthStatus.SOURCE_REQUIRED, null, null, sourceDiagnostics(format));
        }

        FranchiseValueReadinessAnalyzer.ReadinessReport franchise = minimumAsOfDate == null
            ? franchiseReadiness.analyze(normalizedLeagueId, source)
            : franchiseReadiness.analyze(normalizedLeagueId, source, minimumAsOfDate);
        LeagueMovementReadinessAnalyzer.ReadinessReport movement = movementReadiness.analyze(normalizedLeagueId, source);
        HealthStatus status = classify(franchise.status());

        return new HealthReport(
            league.getId(), league.getExternalId(), league.getName(),
            leagueReport.teamCount(), leagueReport.rosteredPlayers(), pickCount,
            format, format != null && format != LeagueValueFormat.UNKNOWN,
            source, automaticSource, minimumAsOfDate,
            status, franchise, movement, diagnostics(franchise, movement));
    }

    private static HealthStatus classify(FranchiseValueReadinessAnalyzer.Readiness readiness) {
        return switch (readiness) {
            case EMPTY -> HealthStatus.EMPTY;
            case UNAVAILABLE -> HealthStatus.VALUES_UNAVAILABLE;
            case STALE -> HealthStatus.STALE;
            case PARTIAL -> HealthStatus.PARTIAL;
            case READY -> HealthStatus.READY;
        };
    }

    private static List<String> sourceDiagnostics(LeagueValueFormat format) {
        if (format == null) {
            return List.of("League value format metadata is unavailable; re-import the Sleeper league or supply a source explicitly.");
        }
        return List.of("League value format is UNKNOWN; supply a source explicitly until the league format can be determined.");
    }

    private static List<String> diagnostics(FranchiseValueReadinessAnalyzer.ReadinessReport franchise,
                                            LeagueMovementReadinessAnalyzer.ReadinessReport movement) {
        var result = new java.util.ArrayList<String>();
        switch (franchise.status()) {
            case EMPTY -> result.add("No current player or draft-pick assets are available for franchise analysis.");
            case UNAVAILABLE -> result.add("No current franchise assets have values for source " + franchise.source() + ".");
            case PARTIAL -> result.add("Franchise values are incomplete: missing players=" + franchise.missingPlayers()
                + ", missing picks=" + franchise.missingDraftPicks() + ".");
            case STALE -> result.add("Franchise values fail the requested minimum as-of date: stale assets="
                + franchise.staleAssets() + ".");
            case READY -> result.add("Current franchise-value analysis is ready.");
        }
        switch (movement.readiness()) {
            case UNAVAILABLE -> result.add("Movement analysis is unavailable until two source snapshots exist.");
            case BLOCKED -> result.add("Movement analysis is blocked because no rostered players are comparable across the two snapshots.");
            case PARTIAL -> result.add("Movement analysis is partial: comparable players=" + movement.comparablePlayers()
                + "/" + movement.totalPlayers() + ".");
            case READY -> result.add("Movement analysis is ready.");
        }
        return List.copyOf(result);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum HealthStatus {
        EMPTY,
        SOURCE_REQUIRED,
        VALUES_UNAVAILABLE,
        STALE,
        PARTIAL,
        READY
    }

    public record HealthReport(String leagueId, String sleeperLeagueId, String leagueName,
                               int teams, int rosteredPlayers, int draftPicks,
                               LeagueValueFormat valueFormat, boolean formatDetected,
                               String source, boolean automaticSource,
                               LocalDate minimumAsOfDate,
                               HealthStatus status,
                               FranchiseValueReadinessAnalyzer.ReadinessReport franchiseReadiness,
                               LeagueMovementReadinessAnalyzer.ReadinessReport movementReadiness,
                               List<String> diagnostics) {
        public boolean sourceResolved() { return source != null; }
        public boolean franchiseRankingsReady() {
            return franchiseReadiness != null && franchiseReadiness.rankable();
        }
        public boolean movementReady() {
            return movementReadiness != null
                && movementReadiness.readiness() == LeagueMovementReadinessAnalyzer.Readiness.READY;
        }
        public boolean coreAnalysisReady() { return status == HealthStatus.READY; }
    }
}
