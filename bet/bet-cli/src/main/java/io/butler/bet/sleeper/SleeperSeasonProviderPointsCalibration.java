package io.butler.bet.sleeper;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.PlayerRepository;
import io.butler.bet.data.PlayerWeekProductionRepository;
import io.butler.bet.data.ProviderPlayerWeekPointsEvidenceRepository;
import io.butler.bet.domain.Player;
import io.butler.bet.domain.ProviderPlayerWeekPointsEvidence;
import io.butler.bet.intelligence.CoveredProductionScoringPolicy;
import io.butler.bet.intelligence.LeagueScoringCoverageAnalyzer;
import io.butler.bet.intelligence.NflversePlayerWeekProductionImporter;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only BF-562 calibration of persisted Sleeper league-scored player-week points against
 * Butler's governed exact scoring of persisted nflverse weekly production on the safe overlap.
 */
public final class SleeperSeasonProviderPointsCalibration {
    public static final String POLICY_ID =
        "sleeper-season-provider-points-calibration-v1-exact-overlap-read-only";
    private static final Set<String> COMPARABLE_POSITIONS = Set.of("QB", "RB", "WR", "TE");
    private static final BigDecimal ONE_HUNDREDTH = new BigDecimal("0.01");

    private final Database database;

    public SleeperSeasonProviderPointsCalibration(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public CalibrationReport calibrate(String leagueId, int season) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }

        var league = new LeagueRepository(database).findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + normalizedLeagueId));
        var coverage = new LeagueScoringCoverageAnalyzer(database).analyzeWeek(normalizedLeagueId);
        if (!coverage.exactScoringEligible()) {
            throw new IllegalStateException(
                "Exact league scoring unavailable for calibration: " + coverage.reason());
        }

        List<ProviderPlayerWeekPointsEvidence> providerRows =
            new ProviderPlayerWeekPointsEvidenceRepository(database).findLatestByLeagueSeason(
                normalizedLeagueId, season, SleeperSeasonProviderPointsEvidenceImporter.SOURCE);
        if (providerRows.isEmpty()) {
            throw new IllegalStateException(
                "No persisted Sleeper provider-points evidence for league=" + normalizedLeagueId
                    + " season=" + season + ". Run the provider-points evidence sync first.");
        }

        LocalDate providerAsOf = providerRows.getFirst().asOfDate();
        String sourceSurface = providerRows.getFirst().sourceSurface();
        for (var row : providerRows) {
            if (!providerAsOf.equals(row.asOfDate())) {
                throw new IllegalStateException("Provider-points latest snapshot contains mixed as-of dates");
            }
            if (!sourceSurface.equals(row.sourceSurface())) {
                throw new IllegalStateException("Provider-points latest snapshot contains mixed source surfaces");
            }
        }

        Map<String, Player> playersBySleeperId = new LinkedHashMap<>();
        for (Player player : new PlayerRepository(database).findAll()) {
            String sleeperId = normalize(player.getExternalId());
            if (sleeperId == null) continue;
            Player existing = playersBySleeperId.putIfAbsent(sleeperId, player);
            if (existing != null && !existing.getId().equals(player.getId())) {
                throw new IllegalStateException("Ambiguous canonical players for Sleeper id: " + sleeperId);
            }
        }

        var productionRepository = new PlayerWeekProductionRepository(database);
        var scoringSettings = new LeagueScoringSettingsRepository(database).findByLeagueId(normalizedLeagueId);
        var scoringPolicy = new CoveredProductionScoringPolicy();
        EnumMap<NonComparableReason, Integer> reasons = new EnumMap<>(NonComparableReason.class);
        List<Comparison> comparisons = new ArrayList<>();
        int identityMappedRows = 0;

        for (var providerRow : providerRows) {
            Player player = playersBySleeperId.get(providerRow.providerPlayerId());
            if (player == null) {
                increment(reasons, NonComparableReason.NO_CANONICAL_PLAYER);
                continue;
            }
            identityMappedRows++;

            if (!COMPARABLE_POSITIONS.contains(normalizePosition(player.getPosition()))) {
                increment(reasons, NonComparableReason.UNSUPPORTED_POSITION);
                continue;
            }

            var production = productionRepository.findLatest(
                player.getId(), season, providerRow.week(), NflversePlayerWeekProductionImporter.SOURCE);
            if (production.isEmpty()) {
                increment(reasons, NonComparableReason.NO_WEEKLY_PRODUCTION);
                continue;
            }

            BigDecimal butlerPoints;
            try {
                butlerPoints = scoringPolicy.score(production.orElseThrow(), scoringSettings).totalPoints();
            } catch (IllegalStateException exactScoringUnavailable) {
                increment(reasons, NonComparableReason.EXACT_SCORING_UNAVAILABLE);
                continue;
            }
            BigDecimal delta = butlerPoints.subtract(providerRow.points());
            comparisons.add(new Comparison(
                providerRow.providerPlayerId(),
                player.getId(),
                player.getPosition(),
                providerRow.week(),
                providerRow.points(),
                butlerPoints,
                delta));
        }

        MetricSummary metrics = summarize(comparisons);
        Map<NonComparableReason, Integer> immutableReasons = Map.copyOf(reasons);
        return new CalibrationReport(
            POLICY_ID,
            normalizedLeagueId,
            league.getName(),
            season,
            SleeperSeasonProviderPointsEvidenceImporter.SOURCE,
            sourceSurface,
            providerAsOf,
            NflversePlayerWeekProductionImporter.SOURCE,
            coverage.policyId(),
            CoveredProductionScoringPolicy.POLICY_ID,
            providerRows.size(),
            identityMappedRows,
            comparisons.size(),
            immutableReasons,
            metrics,
            CalibrationState.REPORTED);
    }

    static MetricSummary summarize(List<Comparison> comparisons) {
        comparisons = List.copyOf(Objects.requireNonNull(comparisons, "comparisons must not be null"));
        if (comparisons.isEmpty()) {
            return new MetricSummary(0, 0, 0, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        }

        BigDecimal signedSum = BigDecimal.ZERO;
        BigDecimal absoluteSum = BigDecimal.ZERO;
        List<BigDecimal> absoluteDeltas = new ArrayList<>();
        int exactMatches = 0;
        int withinOneHundredth = 0;
        for (Comparison comparison : comparisons) {
            Objects.requireNonNull(comparison, "comparison must not be null");
            BigDecimal delta = comparison.delta();
            BigDecimal absolute = delta.abs();
            signedSum = signedSum.add(delta);
            absoluteSum = absoluteSum.add(absolute);
            absoluteDeltas.add(absolute);
            if (delta.compareTo(BigDecimal.ZERO) == 0) exactMatches++;
            if (absolute.compareTo(ONE_HUNDREDTH) <= 0) withinOneHundredth++;
        }
        absoluteDeltas.sort(Comparator.naturalOrder());
        BigDecimal divisor = BigDecimal.valueOf(comparisons.size());
        return new MetricSummary(
            comparisons.size(),
            exactMatches,
            withinOneHundredth,
            Optional.of(signedSum.divide(divisor, MathContext.DECIMAL128)),
            Optional.of(absoluteSum.divide(divisor, MathContext.DECIMAL128)),
            Optional.of(nearestRank(absoluteDeltas, 50)),
            Optional.of(nearestRank(absoluteDeltas, 95)),
            Optional.of(absoluteDeltas.getLast()));
    }

    private static BigDecimal nearestRank(List<BigDecimal> sortedValues, int percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            throw new IllegalArgumentException("sortedValues must not be empty");
        }
        if (percentile <= 0 || percentile > 100) {
            throw new IllegalArgumentException("percentile must be between 1 and 100");
        }
        int rank = (sortedValues.size() * percentile + 99) / 100;
        return sortedValues.get(rank - 1);
    }

    private static void increment(EnumMap<NonComparableReason, Integer> reasons, NonComparableReason reason) {
        reasons.merge(reason, 1, Integer::sum);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizePosition(String value) {
        String normalized = normalize(value);
        return normalized == null ? "" : normalized.toUpperCase(java.util.Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    public enum NonComparableReason {
        NO_CANONICAL_PLAYER,
        UNSUPPORTED_POSITION,
        NO_WEEKLY_PRODUCTION,
        EXACT_SCORING_UNAVAILABLE
    }

    public enum CalibrationState {
        REPORTED
    }

    public record Comparison(
        String providerPlayerId,
        String playerId,
        String position,
        int week,
        BigDecimal providerPoints,
        BigDecimal butlerPoints,
        BigDecimal delta) {
        public Comparison {
            providerPlayerId = requireText(providerPlayerId, "providerPlayerId");
            playerId = requireText(playerId, "playerId");
            position = requireText(position, "position");
            if (week <= 0) throw new IllegalArgumentException("week must be positive");
            Objects.requireNonNull(providerPoints, "providerPoints must not be null");
            Objects.requireNonNull(butlerPoints, "butlerPoints must not be null");
            Objects.requireNonNull(delta, "delta must not be null");
            if (delta.compareTo(butlerPoints.subtract(providerPoints)) != 0) {
                throw new IllegalArgumentException("delta must equal Butler points minus provider points");
            }
        }
    }

    public record MetricSummary(
        int comparableRows,
        int exactMatches,
        int withinOneHundredth,
        Optional<BigDecimal> meanSignedDelta,
        Optional<BigDecimal> meanAbsoluteDelta,
        Optional<BigDecimal> p50AbsoluteDelta,
        Optional<BigDecimal> p95AbsoluteDelta,
        Optional<BigDecimal> maxAbsoluteDelta) {
        public MetricSummary {
            if (comparableRows < 0 || exactMatches < 0 || withinOneHundredth < 0
                || exactMatches > comparableRows || withinOneHundredth > comparableRows) {
                throw new IllegalArgumentException("metric counts are inconsistent");
            }
            meanSignedDelta = requireMetric(meanSignedDelta, "meanSignedDelta", comparableRows);
            meanAbsoluteDelta = requireMetric(meanAbsoluteDelta, "meanAbsoluteDelta", comparableRows);
            p50AbsoluteDelta = requireMetric(p50AbsoluteDelta, "p50AbsoluteDelta", comparableRows);
            p95AbsoluteDelta = requireMetric(p95AbsoluteDelta, "p95AbsoluteDelta", comparableRows);
            maxAbsoluteDelta = requireMetric(maxAbsoluteDelta, "maxAbsoluteDelta", comparableRows);
        }

        private static Optional<BigDecimal> requireMetric(
            Optional<BigDecimal> value, String field, int comparableRows) {
            Objects.requireNonNull(value, field + " must not be null");
            if ((comparableRows == 0) != value.isEmpty()) {
                throw new IllegalArgumentException(field + " presence must match comparable rows");
            }
            return value;
        }
    }

    public record CalibrationReport(
        String policyId,
        String leagueId,
        String leagueName,
        int season,
        String providerSource,
        String providerSourceSurface,
        LocalDate providerAsOf,
        String butlerProductionSource,
        String scoringCoveragePolicyId,
        String scoringPolicyId,
        int providerRows,
        int identityMappedRows,
        int comparableRows,
        Map<NonComparableReason, Integer> nonComparableReasons,
        MetricSummary metrics,
        CalibrationState state) {
        public CalibrationReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            leagueId = requireText(leagueId, "leagueId");
            leagueName = requireText(leagueName, "leagueName");
            if (season < 1999 || season > 2100) throw new IllegalArgumentException("invalid season");
            providerSource = requireText(providerSource, "providerSource");
            providerSourceSurface = requireText(providerSourceSurface, "providerSourceSurface");
            Objects.requireNonNull(providerAsOf, "providerAsOf must not be null");
            butlerProductionSource = requireText(butlerProductionSource, "butlerProductionSource");
            scoringCoveragePolicyId = requireText(scoringCoveragePolicyId, "scoringCoveragePolicyId");
            scoringPolicyId = requireText(scoringPolicyId, "scoringPolicyId");
            if (providerRows <= 0 || identityMappedRows < 0 || comparableRows < 0
                || identityMappedRows > providerRows || comparableRows > identityMappedRows) {
                throw new IllegalArgumentException("row counts are inconsistent");
            }
            nonComparableReasons = Map.copyOf(Objects.requireNonNull(
                nonComparableReasons, "nonComparableReasons must not be null"));
            int nonComparable = nonComparableReasons.values().stream().mapToInt(Integer::intValue).sum();
            if (nonComparable != providerRows - comparableRows) {
                throw new IllegalArgumentException("non-comparable reasons must reconcile to provider rows");
            }
            if (nonComparableReasons.getOrDefault(NonComparableReason.NO_CANONICAL_PLAYER, 0)
                != providerRows - identityMappedRows) {
                throw new IllegalArgumentException("identity-mapped rows must reconcile to missing canonical players");
            }
            metrics = Objects.requireNonNull(metrics, "metrics must not be null");
            if (metrics.comparableRows() != comparableRows) {
                throw new IllegalArgumentException("metric comparable rows must match report");
            }
            Objects.requireNonNull(state, "state must not be null");
        }

        public int nonComparableRows() {
            return providerRows - comparableRows;
        }
    }
}
