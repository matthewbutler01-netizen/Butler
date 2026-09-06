package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueScoringSettingsRepository;
import io.butler.bet.data.PlayerWeekProductionRepository;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Binds persisted league scoring evidence to the latest persisted player-week production snapshot
 * for one explicit source. Exact scoring remains blocked unless player-week scoring coverage is complete.
 */
public final class LeaguePlayerWeekScoringAnalyzer {
    public static final String POLICY_ID =
        "league-player-week-scoring-v1-latest-source-snapshot-exact-only";

    private final Database database;

    public LeaguePlayerWeekScoringAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public ScoringReport analyze(String leagueId, String playerId, int season, int week, String source)
        throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedPlayerId = requireText(playerId, "playerId");
        String normalizedSource = requireText(source, "source");
        if (season <= 0) throw new IllegalArgumentException("season must be positive");
        if (week <= 0) throw new IllegalArgumentException("week must be positive");

        var coverage = new LeagueScoringCoverageAnalyzer(database).analyzeWeek(normalizedLeagueId);
        if (!coverage.exactScoringEligible()) {
            throw new IllegalStateException(
                "Exact league scoring unavailable for " + normalizedLeagueId + ": " + coverage.reason());
        }

        var production = new PlayerWeekProductionRepository(database)
            .findLatest(normalizedPlayerId, season, week, normalizedSource)
            .orElseThrow(() -> new IllegalStateException(
                "No persisted weekly production for player=" + normalizedPlayerId
                    + " season=" + season + " week=" + week + " source=" + normalizedSource));
        var settings = new LeagueScoringSettingsRepository(database).findByLeagueId(normalizedLeagueId);
        var score = new CoveredProductionScoringPolicy().score(production, settings);

        return new ScoringReport(
            POLICY_ID,
            coverage.policyId(),
            score.policyId(),
            coverage.leagueId(),
            coverage.leagueName(),
            normalizedPlayerId,
            season,
            week,
            normalizedSource,
            production.id(),
            production.asOfDate(),
            score);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record ScoringReport(
        String policyId,
        String coveragePolicyId,
        String scoringPolicyId,
        String leagueId,
        String leagueName,
        String playerId,
        int season,
        int week,
        String source,
        String productionId,
        LocalDate productionAsOf,
        CoveredProductionScoringPolicy.WeekScoreResult score) {
        public ScoringReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            requireText(coveragePolicyId, "coveragePolicyId");
            requireText(scoringPolicyId, "scoringPolicyId");
            requireText(leagueId, "leagueId");
            requireText(leagueName, "leagueName");
            requireText(playerId, "playerId");
            if (season <= 0) throw new IllegalArgumentException("season must be positive");
            if (week <= 0) throw new IllegalArgumentException("week must be positive");
            requireText(source, "source");
            requireText(productionId, "productionId");
            Objects.requireNonNull(productionAsOf, "productionAsOf must not be null");
            Objects.requireNonNull(score, "score must not be null");
            if (!playerId.equals(score.playerId()) || season != score.season() || week != score.week()
                || !productionId.equals(score.productionId())) {
                throw new IllegalArgumentException("score provenance must match selected weekly production");
            }
        }
    }
}
