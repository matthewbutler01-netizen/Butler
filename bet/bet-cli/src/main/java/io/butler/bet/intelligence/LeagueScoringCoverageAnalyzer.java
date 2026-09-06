package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.LeagueScoringSettingsRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Determines whether persisted league scoring rules are exactly representable by Butler's raw
 * production evidence at the requested production grain. This analyzer never calculates fantasy points.
 */
public final class LeagueScoringCoverageAnalyzer {
    public static final String POLICY_ID =
        "league-scoring-coverage-v1-exact-production-fields-fail-closed";

    private final Database database;

    public LeagueScoringCoverageAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    /** Preserves the original BF-521 player-season coverage contract. */
    public CoverageReport analyze(String leagueId) throws SQLException {
        return analyzeForGrain(
            leagueId,
            SupportedScoringStat.ProductionGrain.SEASON_AND_WEEK,
            "player-season");
    }

    /** Exact coverage for scoring one persisted player-week production row. */
    public CoverageReport analyzeWeek(String leagueId) throws SQLException {
        return analyzeForGrain(
            leagueId,
            SupportedScoringStat.ProductionGrain.WEEK_ONLY,
            "player-week");
    }

    private CoverageReport analyzeForGrain(
        String leagueId,
        SupportedScoringStat.ProductionGrain productionGrain,
        String productionLabel) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        var league = new LeagueRepository(database).findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + normalizedLeagueId));
        var settings = new LeagueScoringSettingsRepository(database).findByLeagueId(normalizedLeagueId);

        if (settings.isEmpty()) {
            return new CoverageReport(
                POLICY_ID,
                league.getId(),
                league.getName(),
                CoverageState.NO_SCORING_SETTINGS,
                List.of(),
                0,
                0,
                0,
                "No persisted provider scoring settings are available; exact " + productionLabel
                    + " scoring coverage cannot be established.");
        }

        List<RuleCoverage> rules = new ArrayList<>();
        int supportedNonzero = 0;
        int ignoredZero = 0;
        int unsupportedNonzero = 0;
        for (var entry : settings.entrySet()) {
            String statKey = entry.getKey();
            double points = entry.getValue();
            SupportedScoringStat supported = SupportedScoringStat.find(statKey);
            RuleState state;
            String productionField = null;
            if (Double.compare(points, 0.0d) == 0) {
                state = RuleState.ZERO_IGNORED;
                ignoredZero++;
            } else if (supported != null && supported.supports(productionGrain)) {
                state = RuleState.SUPPORTED;
                productionField = supported.productionField();
                supportedNonzero++;
            } else {
                state = RuleState.UNSUPPORTED_NONZERO;
                unsupportedNonzero++;
            }
            rules.add(new RuleCoverage(statKey, points, state, productionField));
        }

        CoverageState state = unsupportedNonzero == 0
            ? CoverageState.COMPLETE
            : CoverageState.INCOMPLETE;
        String reason = state == CoverageState.COMPLETE
            ? "Every nonzero scoring rule is exactly representable for stored raw " + productionLabel + " production."
            : "At least one nonzero scoring rule is not exactly representable for Butler's stored raw "
                + productionLabel + " production.";
        return new CoverageReport(
            POLICY_ID,
            league.getId(),
            league.getName(),
            state,
            List.copyOf(rules),
            supportedNonzero,
            ignoredZero,
            unsupportedNonzero,
            reason);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum CoverageState {
        COMPLETE,
        INCOMPLETE,
        NO_SCORING_SETTINGS
    }

    public enum RuleState {
        SUPPORTED,
        ZERO_IGNORED,
        UNSUPPORTED_NONZERO
    }

    public record RuleCoverage(
        String statKey,
        double pointsPerUnit,
        RuleState state,
        String productionField) {
        public RuleCoverage {
            requireText(statKey, "statKey");
            if (!Double.isFinite(pointsPerUnit)) throw new IllegalArgumentException("pointsPerUnit must be finite");
            Objects.requireNonNull(state, "state must not be null");
            if ((state == RuleState.SUPPORTED) != (productionField != null)) {
                throw new IllegalArgumentException("only supported rules may carry a productionField");
            }
            if (productionField != null) requireText(productionField, "productionField");
        }
    }

    public record CoverageReport(
        String policyId,
        String leagueId,
        String leagueName,
        CoverageState state,
        List<RuleCoverage> rules,
        int supportedNonzeroRules,
        int ignoredZeroRules,
        int unsupportedNonzeroRules,
        String reason) {
        public CoverageReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            requireText(leagueId, "leagueId");
            requireText(leagueName, "leagueName");
            Objects.requireNonNull(state, "state must not be null");
            rules = List.copyOf(Objects.requireNonNull(rules, "rules must not be null"));
            if (supportedNonzeroRules < 0 || ignoredZeroRules < 0 || unsupportedNonzeroRules < 0) {
                throw new IllegalArgumentException("rule counts must not be negative");
            }
            if (rules.size() != supportedNonzeroRules + ignoredZeroRules + unsupportedNonzeroRules) {
                throw new IllegalArgumentException("rule counts must match rules");
            }
            requireText(reason, "reason");
        }

        public boolean exactScoringEligible() {
            return state == CoverageState.COMPLETE;
        }
    }
}
