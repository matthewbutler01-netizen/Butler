package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamWeekRosterEvidenceRepository;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregates governed team-week potential-lineup evidence across only the team weeks that Butler
 * has actually observed through persisted Sleeper roster evidence.
 */
public final class LeagueTeamSeasonPotentialLineupEvidenceAnalyzer {
    public static final String POLICY_ID =
        "team-season-potential-lineup-evidence-v1-observed-roster-weeks-complete-only-aggregate";
    public static final String WEEK_UNIVERSE =
        "OBSERVED_SLEEPER_TEAM_WEEK_ROSTER_EVIDENCE_ONLY";
    public static final String AVERAGE_POLICY = "BIGDECIMAL_DECIMAL128_OVER_QUALIFYING_COMPLETE_WEEKS";

    private final Database database;

    public LeagueTeamSeasonPotentialLineupEvidenceAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public SeasonEvidenceReport analyze(String leagueId, String teamId, int season) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        String normalizedTeamId = requireText(teamId, "teamId");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }

        var team = new TeamRepository(database).findById(normalizedTeamId)
            .orElseThrow(() -> new IllegalArgumentException("Team not found: " + normalizedTeamId));
        if (!normalizedLeagueId.equals(team.getLeagueId())) {
            throw new IllegalArgumentException(
                "Team " + normalizedTeamId + " does not belong to league " + normalizedLeagueId);
        }

        var observedRosters = new TeamWeekRosterEvidenceRepository(database).findLatestByTeamSeason(
            normalizedLeagueId,
            normalizedTeamId,
            season,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.SLEEPER_SOURCE);

        LeagueTeamWeekPotentialLineupCoverageAnalyzer coverageAnalyzer =
            new LeagueTeamWeekPotentialLineupCoverageAnalyzer(database);
        LeagueTeamWeekPotentialLineupAnalyzer potentialAnalyzer =
            new LeagueTeamWeekPotentialLineupAnalyzer(database);
        List<WeekEvidence> weeks = new ArrayList<>();

        for (var observedRoster : observedRosters) {
            var coverage = coverageAnalyzer.analyze(
                normalizedLeagueId, normalizedTeamId, season, observedRoster.week());
            if (!observedRoster.asOfDate().equals(coverage.rosterEvidenceAsOf())) {
                weeks.add(WeekEvidence.blocked(
                    observedRoster.week(),
                    observedRoster.asOfDate(),
                    coverage,
                    List.of("Latest Sleeper team-week roster evidence moved after season week enumeration")));
                continue;
            }
            if (!coverage.ready()) {
                weeks.add(WeekEvidence.blocked(
                    observedRoster.week(), observedRoster.asOfDate(), coverage, coverage.blockers()));
                continue;
            }

            LeagueTeamWeekPotentialLineupAnalyzer.PotentialLineupReport potential;
            try {
                potential = potentialAnalyzer.analyze(
                    normalizedLeagueId, normalizedTeamId, season, observedRoster.week());
            } catch (IllegalStateException e) {
                weeks.add(WeekEvidence.blocked(
                    observedRoster.week(),
                    observedRoster.asOfDate(),
                    coverage,
                    List.of("Potential-lineup calculation invalidated after readiness: " + e.getMessage())));
                continue;
            }

            if (!observedRoster.asOfDate().equals(potential.rosterEvidenceAsOf())) {
                weeks.add(WeekEvidence.blocked(
                    observedRoster.week(),
                    observedRoster.asOfDate(),
                    coverage,
                    List.of("Sleeper team-week roster evidence moved during potential-lineup calculation")));
                continue;
            }

            weeks.add(potential.lineup().complete()
                ? WeekEvidence.qualifying(observedRoster.week(), observedRoster.asOfDate(), coverage, potential)
                : WeekEvidence.incomplete(observedRoster.week(), observedRoster.asOfDate(), coverage, potential));
        }

        List<WeekEvidence> immutableWeeks = List.copyOf(weeks);
        return new SeasonEvidenceReport(
            POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE,
            WEEK_UNIVERSE,
            AVERAGE_POLICY,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.POLICY_ID,
            LeagueTeamWeekPotentialLineupAnalyzer.POLICY_ID,
            normalizedLeagueId,
            normalizedTeamId,
            season,
            immutableWeeks,
            aggregate(immutableWeeks));
    }

    private static SeasonAggregate aggregate(List<WeekEvidence> weeks) {
        int blocked = 0;
        int incomplete = 0;
        int qualifying = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (WeekEvidence week : weeks) {
            switch (week.state()) {
                case BLOCKED -> blocked++;
                case INCOMPLETE_LINEUP -> incomplete++;
                case QUALIFYING_COMPLETE -> {
                    qualifying++;
                    total = total.add(week.potentialLineup().lineup().totalPoints());
                }
            }
        }
        Optional<BigDecimal> qualifyingTotal = qualifying == 0 ? Optional.empty() : Optional.of(total);
        Optional<BigDecimal> qualifyingAverage = qualifying == 0
            ? Optional.empty()
            : Optional.of(total.divide(BigDecimal.valueOf(qualifying), MathContext.DECIMAL128));
        return new SeasonAggregate(
            weeks.size(), blocked, incomplete, qualifying, qualifyingTotal, qualifyingAverage);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum WeekState {
        QUALIFYING_COMPLETE,
        INCOMPLETE_LINEUP,
        BLOCKED
    }

    public record WeekEvidence(
        int week,
        WeekState state,
        LocalDate enumeratedRosterEvidenceAsOf,
        LeagueTeamWeekPotentialLineupCoverageAnalyzer.CoverageReport coverage,
        LeagueTeamWeekPotentialLineupAnalyzer.PotentialLineupReport potentialLineup,
        List<String> blockers) {
        public WeekEvidence {
            if (week <= 0) throw new IllegalArgumentException("week must be positive");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(enumeratedRosterEvidenceAsOf, "enumeratedRosterEvidenceAsOf must not be null");
            Objects.requireNonNull(coverage, "coverage must not be null");
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers must not be null"));
            if (coverage.week() != week) throw new IllegalArgumentException("coverage week must match week");
            switch (state) {
                case BLOCKED -> {
                    if (potentialLineup != null) throw new IllegalArgumentException("blocked week cannot include potential lineup");
                    if (blockers.isEmpty()) throw new IllegalArgumentException("blocked week must include blockers");
                }
                case INCOMPLETE_LINEUP -> {
                    if (potentialLineup == null || potentialLineup.lineup().complete()) {
                        throw new IllegalArgumentException("incomplete week must include an incomplete potential lineup");
                    }
                    if (!blockers.isEmpty()) throw new IllegalArgumentException("incomplete week cannot include blockers");
                }
                case QUALIFYING_COMPLETE -> {
                    if (potentialLineup == null || !potentialLineup.lineup().complete()) {
                        throw new IllegalArgumentException("qualifying week must include a complete potential lineup");
                    }
                    if (!blockers.isEmpty()) throw new IllegalArgumentException("qualifying week cannot include blockers");
                }
            }
            if (potentialLineup != null && potentialLineup.week() != week) {
                throw new IllegalArgumentException("potential lineup week must match week");
            }
        }

        static WeekEvidence blocked(
            int week,
            LocalDate rosterAsOf,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.CoverageReport coverage,
            List<String> blockers) {
            return new WeekEvidence(week, WeekState.BLOCKED, rosterAsOf, coverage, null, blockers);
        }

        static WeekEvidence incomplete(
            int week,
            LocalDate rosterAsOf,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.CoverageReport coverage,
            LeagueTeamWeekPotentialLineupAnalyzer.PotentialLineupReport potential) {
            return new WeekEvidence(
                week, WeekState.INCOMPLETE_LINEUP, rosterAsOf, coverage, potential, List.of());
        }

        static WeekEvidence qualifying(
            int week,
            LocalDate rosterAsOf,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.CoverageReport coverage,
            LeagueTeamWeekPotentialLineupAnalyzer.PotentialLineupReport potential) {
            return new WeekEvidence(
                week, WeekState.QUALIFYING_COMPLETE, rosterAsOf, coverage, potential, List.of());
        }
    }

    public record SeasonAggregate(
        int observedWeeks,
        int blockedWeeks,
        int incompleteLineupWeeks,
        int qualifyingCompleteWeeks,
        Optional<BigDecimal> qualifyingTotalPotentialPoints,
        Optional<BigDecimal> qualifyingAveragePotentialPoints) {
        public SeasonAggregate {
            if (observedWeeks < 0 || blockedWeeks < 0 || incompleteLineupWeeks < 0 || qualifyingCompleteWeeks < 0) {
                throw new IllegalArgumentException("week counts must not be negative");
            }
            if (blockedWeeks + incompleteLineupWeeks + qualifyingCompleteWeeks != observedWeeks) {
                throw new IllegalArgumentException("week-state counts must sum to observedWeeks");
            }
            qualifyingTotalPotentialPoints = Objects.requireNonNull(
                qualifyingTotalPotentialPoints, "qualifyingTotalPotentialPoints must not be null");
            qualifyingAveragePotentialPoints = Objects.requireNonNull(
                qualifyingAveragePotentialPoints, "qualifyingAveragePotentialPoints must not be null");
            if (qualifyingCompleteWeeks == 0) {
                if (qualifyingTotalPotentialPoints.isPresent() || qualifyingAveragePotentialPoints.isPresent()) {
                    throw new IllegalArgumentException("zero qualifying weeks cannot expose aggregate points");
                }
            } else if (qualifyingTotalPotentialPoints.isEmpty() || qualifyingAveragePotentialPoints.isEmpty()) {
                throw new IllegalArgumentException("qualifying weeks must expose total and average points");
            }
        }
    }

    public record SeasonEvidenceReport(
        String policyId,
        String metricScope,
        String weekUniverse,
        String averagePolicy,
        String coveragePolicyId,
        String potentialLineupPolicyId,
        String leagueId,
        String teamId,
        int season,
        List<WeekEvidence> weeks,
        SeasonAggregate aggregate) {
        public SeasonEvidenceReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE.equals(metricScope)) {
                throw new IllegalArgumentException("unexpected metricScope");
            }
            if (!WEEK_UNIVERSE.equals(weekUniverse)) throw new IllegalArgumentException("unexpected weekUniverse");
            if (!AVERAGE_POLICY.equals(averagePolicy)) throw new IllegalArgumentException("unexpected averagePolicy");
            if (!LeagueTeamWeekPotentialLineupCoverageAnalyzer.POLICY_ID.equals(coveragePolicyId)) {
                throw new IllegalArgumentException("unexpected coveragePolicyId");
            }
            if (!LeagueTeamWeekPotentialLineupAnalyzer.POLICY_ID.equals(potentialLineupPolicyId)) {
                throw new IllegalArgumentException("unexpected potentialLineupPolicyId");
            }
            requireText(leagueId, "leagueId");
            requireText(teamId, "teamId");
            if (season < 1999 || season > 2100) {
                throw new IllegalArgumentException("season must be between 1999 and 2100");
            }
            weeks = List.copyOf(Objects.requireNonNull(weeks, "weeks must not be null"));
            Objects.requireNonNull(aggregate, "aggregate must not be null");
            if (aggregate.observedWeeks() != weeks.size()) {
                throw new IllegalArgumentException("aggregate observedWeeks must match weeks");
            }
            int previousWeek = 0;
            for (WeekEvidence week : weeks) {
                if (week.week() <= previousWeek) {
                    throw new IllegalArgumentException("weeks must be unique and strictly ascending");
                }
                if (!leagueId.equals(week.coverage().leagueId()) || !teamId.equals(week.coverage().teamId())
                    || season != week.coverage().season()) {
                    throw new IllegalArgumentException("week coverage identity must match season report");
                }
                if (week.potentialLineup() != null
                    && (!leagueId.equals(week.potentialLineup().leagueId())
                        || !teamId.equals(week.potentialLineup().teamId())
                        || season != week.potentialLineup().season())) {
                    throw new IllegalArgumentException("week potential-lineup identity must match season report");
                }
                previousWeek = week.week();
            }
        }
    }
}
