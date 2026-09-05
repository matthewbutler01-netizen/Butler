package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregates descriptive team-week lineup points-gap evidence across only observed Sleeper roster
 * weeks while preserving every observed week's comparability state.
 *
 * <p>Only weeks with both complete governed potential and started lineups contribute to totals.
 * This artifact does not calculate an efficiency percentage, rank, tier, recommendation, or
 * manager attribution.</p>
 */
public final class LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer {
    public static final String POLICY_ID =
        "team-season-lineup-points-gap-evidence-v1-observed-roster-weeks-complete-comparisons-only-no-attribution";
    public static final String METRIC_SCOPE =
        "RETROSPECTIVE_TEAM_SEASON_RECALCULATED_POTENTIAL_MINUS_STARTED_POINTS_OVER_COMPARABLE_COMPLETE_OBSERVED_ROSTER_WEEKS_ONLY_NOT_MANAGER_EFFICIENCY";
    public static final String WEEK_UNIVERSE = LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WEEK_UNIVERSE;
    public static final String AGGREGATE_POLICY =
        "TOTALS_OVER_COMPARABLE_COMPLETE_OBSERVED_WEEKS_ONLY_NO_NORMALIZATION";

    private final Database database;

    public LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public SeasonEvidenceReport analyze(String leagueId, String teamId, int season) throws SQLException {
        LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.SeasonEvidenceReport sourceSeason =
            new LeagueTeamSeasonPotentialLineupEvidenceAnalyzer(database).analyze(leagueId, teamId, season);
        LeagueTeamWeekStartedLineupEvidenceAnalyzer startedAnalyzer =
            new LeagueTeamWeekStartedLineupEvidenceAnalyzer(database);
        LeagueTeamWeekLineupPointsGapEvidenceAnalyzer gapAnalyzer =
            new LeagueTeamWeekLineupPointsGapEvidenceAnalyzer(database);

        List<WeekEvidence> weeks = new ArrayList<>();
        for (var sourceWeek : sourceSeason.weeks()) {
            switch (sourceWeek.state()) {
                case BLOCKED -> weeks.add(WeekEvidence.blocked(sourceWeek, sourceWeek.blockers()));
                case INCOMPLETE_LINEUP -> weeks.add(WeekEvidence.potentialIncomplete(sourceWeek));
                case QUALIFYING_COMPLETE -> analyzePotentialCompleteWeek(
                    sourceSeason, sourceWeek, startedAnalyzer, gapAnalyzer, weeks);
            }
        }

        List<WeekEvidence> immutableWeeks = List.copyOf(weeks);
        return new SeasonEvidenceReport(
            POLICY_ID,
            METRIC_SCOPE,
            WEEK_UNIVERSE,
            AGGREGATE_POLICY,
            sourceSeason.policyId(),
            LeagueTeamWeekStartedLineupEvidenceAnalyzer.POLICY_ID,
            LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.POLICY_ID,
            sourceSeason.leagueId(),
            sourceSeason.teamId(),
            sourceSeason.season(),
            immutableWeeks,
            aggregate(immutableWeeks));
    }

    private static void analyzePotentialCompleteWeek(
        LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.SeasonEvidenceReport sourceSeason,
        LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekEvidence sourceWeek,
        LeagueTeamWeekStartedLineupEvidenceAnalyzer startedAnalyzer,
        LeagueTeamWeekLineupPointsGapEvidenceAnalyzer gapAnalyzer,
        List<WeekEvidence> weeks) throws SQLException {

        LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedLineupReport started;
        try {
            started = startedAnalyzer.analyze(
                sourceSeason.leagueId(), sourceSeason.teamId(), sourceSeason.season(), sourceWeek.week());
        } catch (IllegalStateException e) {
            weeks.add(WeekEvidence.blocked(
                sourceWeek,
                List.of("Started-lineup evidence unavailable after potential qualification: " + e.getMessage())));
            return;
        }

        if (!sourceWeek.enumeratedRosterEvidenceAsOf().equals(started.rosterEvidenceAsOf())) {
            weeks.add(WeekEvidence.blocked(
                sourceWeek,
                List.of("Sleeper team-week roster evidence moved during started-lineup calculation")));
            return;
        }
        if (!startedMatchesSourcePotential(sourceWeek.potentialLineup(), started)) {
            weeks.add(WeekEvidence.blocked(
                sourceWeek,
                List.of("Governed configuration or production evidence moved during started-lineup calculation")));
            return;
        }
        if (!started.complete()) {
            weeks.add(WeekEvidence.startedIncomplete(sourceWeek, started));
            return;
        }

        LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.LineupPointsGapReport gap;
        try {
            gap = gapAnalyzer.analyze(
                sourceSeason.leagueId(), sourceSeason.teamId(), sourceSeason.season(), sourceWeek.week());
        } catch (IllegalStateException e) {
            weeks.add(WeekEvidence.blocked(
                sourceWeek,
                List.of("Lineup points-gap calculation unavailable after complete-lineup qualification: "
                    + e.getMessage())));
            return;
        }

        if (!sourceWeek.enumeratedRosterEvidenceAsOf().equals(gap.rosterEvidenceAsOf())) {
            weeks.add(WeekEvidence.blocked(
                sourceWeek,
                List.of("Sleeper team-week roster evidence moved during points-gap calculation")));
            return;
        }
        if (!gapMatchesSourceEvidence(sourceWeek.potentialLineup(), started, gap)) {
            weeks.add(WeekEvidence.blocked(
                sourceWeek,
                List.of("Governed configuration, production, or scored lineup evidence moved during points-gap calculation")));
            return;
        }
        weeks.add(WeekEvidence.comparable(sourceWeek, started, gap));
    }

    private static boolean startedMatchesSourcePotential(
        LeagueTeamWeekPotentialLineupAnalyzer.PotentialLineupReport potential,
        LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedLineupReport started) {
        return potential.leagueConfigurationAsOf().equals(started.leagueConfigurationAsOf())
            && potential.rosterEvidenceAsOf().equals(started.rosterEvidenceAsOf())
            && potential.productionCoverageAsOf().equals(started.productionCoverageAsOf())
            && potential.productionSourceUri().equals(started.productionSourceUri())
            && potential.scoringPolicyId().equals(started.scoringPolicyId())
            && potential.eligibilityPolicyId().equals(started.eligibilityPolicyId());
    }

    private static boolean gapMatchesSourceEvidence(
        LeagueTeamWeekPotentialLineupAnalyzer.PotentialLineupReport potential,
        LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedLineupReport started,
        LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.LineupPointsGapReport gap) {
        return potential.leagueConfigurationAsOf().equals(gap.leagueConfigurationAsOf())
            && potential.rosterEvidenceAsOf().equals(gap.rosterEvidenceAsOf())
            && potential.productionCoverageAsOf().equals(gap.productionCoverageAsOf())
            && potential.productionSourceUri().equals(gap.productionSourceUri())
            && potential.scoringPolicyId().equals(gap.scoringPolicyId())
            && potential.solverPolicyId().equals(gap.solverPolicyId())
            && potential.eligibilityPolicyId().equals(gap.eligibilityPolicyId())
            && potential.lineup().totalPoints().compareTo(gap.potentialPoints()) == 0
            && started.totalStartedPoints().compareTo(gap.startedPoints()) == 0;
    }

    private static SeasonAggregate aggregate(List<WeekEvidence> weeks) {
        int blocked = 0;
        int potentialIncomplete = 0;
        int startedIncomplete = 0;
        int comparable = 0;
        BigDecimal totalStarted = BigDecimal.ZERO;
        BigDecimal totalPotential = BigDecimal.ZERO;
        BigDecimal totalGap = BigDecimal.ZERO;

        for (WeekEvidence week : weeks) {
            switch (week.state()) {
                case BLOCKED -> blocked++;
                case POTENTIAL_INCOMPLETE -> potentialIncomplete++;
                case STARTED_INCOMPLETE -> startedIncomplete++;
                case COMPARABLE_COMPLETE -> {
                    comparable++;
                    totalStarted = totalStarted.add(week.pointsGap().startedPoints());
                    totalPotential = totalPotential.add(week.pointsGap().potentialPoints());
                    totalGap = totalGap.add(week.pointsGap().pointsGap());
                }
            }
        }

        if (comparable == 0) {
            return new SeasonAggregate(
                weeks.size(), blocked, potentialIncomplete, startedIncomplete, comparable,
                Optional.empty(), Optional.empty(), Optional.empty());
        }
        return new SeasonAggregate(
            weeks.size(), blocked, potentialIncomplete, startedIncomplete, comparable,
            Optional.of(totalStarted), Optional.of(totalPotential), Optional.of(totalGap));
    }

    private static void requireAggregateMatchesWeeks(List<WeekEvidence> weeks, SeasonAggregate aggregate) {
        SeasonAggregate expected = aggregate(weeks);
        if (aggregate.observedWeeks() != expected.observedWeeks()
            || aggregate.blockedWeeks() != expected.blockedWeeks()
            || aggregate.potentialIncompleteWeeks() != expected.potentialIncompleteWeeks()
            || aggregate.startedIncompleteWeeks() != expected.startedIncompleteWeeks()
            || aggregate.comparableCompleteWeeks() != expected.comparableCompleteWeeks()) {
            throw new IllegalArgumentException("aggregate week-state counts must match nested week evidence");
        }
        if (!optionalDecimalEquals(
                aggregate.comparableTotalStartedPoints(), expected.comparableTotalStartedPoints())
            || !optionalDecimalEquals(
                aggregate.comparableTotalPotentialPoints(), expected.comparableTotalPotentialPoints())
            || !optionalDecimalEquals(
                aggregate.comparableTotalPointsGap(), expected.comparableTotalPointsGap())) {
            throw new IllegalArgumentException("aggregate comparable totals must match nested week evidence");
        }
    }

    private static boolean optionalDecimalEquals(Optional<BigDecimal> left, Optional<BigDecimal> right) {
        if (left.isEmpty() || right.isEmpty()) return left.isEmpty() && right.isEmpty();
        return left.orElseThrow().compareTo(right.orElseThrow()) == 0;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public enum WeekState {
        COMPARABLE_COMPLETE,
        POTENTIAL_INCOMPLETE,
        STARTED_INCOMPLETE,
        BLOCKED
    }

    public record WeekEvidence(
        int week,
        WeekState state,
        LocalDate enumeratedRosterEvidenceAsOf,
        LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekEvidence sourcePotentialWeek,
        LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedLineupReport startedLineup,
        LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.LineupPointsGapReport pointsGap,
        List<String> blockers) {

        public WeekEvidence {
            if (week <= 0) throw new IllegalArgumentException("week must be positive");
            Objects.requireNonNull(state, "state must not be null");
            Objects.requireNonNull(enumeratedRosterEvidenceAsOf, "enumeratedRosterEvidenceAsOf must not be null");
            Objects.requireNonNull(sourcePotentialWeek, "sourcePotentialWeek must not be null");
            blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers must not be null"));
            if (sourcePotentialWeek.week() != week
                || !sourcePotentialWeek.enumeratedRosterEvidenceAsOf().equals(enumeratedRosterEvidenceAsOf)) {
                throw new IllegalArgumentException("source potential week identity must match week evidence");
            }

            switch (state) {
                case BLOCKED -> {
                    if (startedLineup != null || pointsGap != null) {
                        throw new IllegalArgumentException("blocked week cannot include started or gap evidence");
                    }
                    if (blockers.isEmpty()) throw new IllegalArgumentException("blocked week must include blockers");
                    if (sourcePotentialWeek.state()
                        == LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekState.INCOMPLETE_LINEUP) {
                        throw new IllegalArgumentException("potential-incomplete week must use POTENTIAL_INCOMPLETE state");
                    }
                }
                case POTENTIAL_INCOMPLETE -> {
                    if (sourcePotentialWeek.state()
                        != LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekState.INCOMPLETE_LINEUP) {
                        throw new IllegalArgumentException("potential-incomplete state requires incomplete source potential");
                    }
                    if (startedLineup != null || pointsGap != null || !blockers.isEmpty()) {
                        throw new IllegalArgumentException("potential-incomplete week cannot include downstream evidence");
                    }
                }
                case STARTED_INCOMPLETE -> {
                    requireSourcePotentialComplete(sourcePotentialWeek);
                    if (startedLineup == null || startedLineup.complete()) {
                        throw new IllegalArgumentException("started-incomplete state requires incomplete started evidence");
                    }
                    if (pointsGap != null || !blockers.isEmpty()) {
                        throw new IllegalArgumentException("started-incomplete week cannot include gap evidence or blockers");
                    }
                    requireStartedIdentity(week, enumeratedRosterEvidenceAsOf, startedLineup);
                    if (!startedMatchesSourcePotential(sourcePotentialWeek.potentialLineup(), startedLineup)) {
                        throw new IllegalArgumentException("started-incomplete evidence must match source governed provenance");
                    }
                }
                case COMPARABLE_COMPLETE -> {
                    requireSourcePotentialComplete(sourcePotentialWeek);
                    if (startedLineup == null || !startedLineup.complete() || pointsGap == null || !blockers.isEmpty()) {
                        throw new IllegalArgumentException("comparable week requires complete started and gap evidence");
                    }
                    requireStartedIdentity(week, enumeratedRosterEvidenceAsOf, startedLineup);
                    if (pointsGap.week() != week
                        || !pointsGap.rosterEvidenceAsOf().equals(enumeratedRosterEvidenceAsOf)) {
                        throw new IllegalArgumentException("gap identity must match observed week evidence");
                    }
                    if (!startedMatchesSourcePotential(sourcePotentialWeek.potentialLineup(), startedLineup)
                        || !gapMatchesSourceEvidence(sourcePotentialWeek.potentialLineup(), startedLineup, pointsGap)) {
                        throw new IllegalArgumentException("comparable evidence must preserve one governed provenance boundary");
                    }
                }
            }
        }

        static WeekEvidence blocked(
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekEvidence sourceWeek,
            List<String> blockers) {
            return new WeekEvidence(
                sourceWeek.week(), WeekState.BLOCKED, sourceWeek.enumeratedRosterEvidenceAsOf(),
                sourceWeek, null, null, blockers);
        }

        static WeekEvidence potentialIncomplete(
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekEvidence sourceWeek) {
            return new WeekEvidence(
                sourceWeek.week(), WeekState.POTENTIAL_INCOMPLETE, sourceWeek.enumeratedRosterEvidenceAsOf(),
                sourceWeek, null, null, List.of());
        }

        static WeekEvidence startedIncomplete(
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekEvidence sourceWeek,
            LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedLineupReport started) {
            return new WeekEvidence(
                sourceWeek.week(), WeekState.STARTED_INCOMPLETE, sourceWeek.enumeratedRosterEvidenceAsOf(),
                sourceWeek, started, null, List.of());
        }

        static WeekEvidence comparable(
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekEvidence sourceWeek,
            LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedLineupReport started,
            LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.LineupPointsGapReport gap) {
            return new WeekEvidence(
                sourceWeek.week(), WeekState.COMPARABLE_COMPLETE, sourceWeek.enumeratedRosterEvidenceAsOf(),
                sourceWeek, started, gap, List.of());
        }

        private static void requireSourcePotentialComplete(
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekEvidence sourceWeek) {
            if (sourceWeek.state()
                != LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WeekState.QUALIFYING_COMPLETE) {
                throw new IllegalArgumentException("downstream lineup comparison requires qualifying source potential");
            }
        }

        private static void requireStartedIdentity(
            int week,
            LocalDate rosterAsOf,
            LeagueTeamWeekStartedLineupEvidenceAnalyzer.StartedLineupReport started) {
            if (started.week() != week || !started.rosterEvidenceAsOf().equals(rosterAsOf)) {
                throw new IllegalArgumentException("started lineup identity must match observed week evidence");
            }
        }
    }

    public record SeasonAggregate(
        int observedWeeks,
        int blockedWeeks,
        int potentialIncompleteWeeks,
        int startedIncompleteWeeks,
        int comparableCompleteWeeks,
        Optional<BigDecimal> comparableTotalStartedPoints,
        Optional<BigDecimal> comparableTotalPotentialPoints,
        Optional<BigDecimal> comparableTotalPointsGap) {

        public SeasonAggregate {
            if (observedWeeks < 0 || blockedWeeks < 0 || potentialIncompleteWeeks < 0
                || startedIncompleteWeeks < 0 || comparableCompleteWeeks < 0) {
                throw new IllegalArgumentException("week counts must not be negative");
            }
            if (blockedWeeks + potentialIncompleteWeeks + startedIncompleteWeeks + comparableCompleteWeeks
                != observedWeeks) {
                throw new IllegalArgumentException("week-state counts must sum to observedWeeks");
            }
            comparableTotalStartedPoints = Objects.requireNonNull(
                comparableTotalStartedPoints, "comparableTotalStartedPoints must not be null");
            comparableTotalPotentialPoints = Objects.requireNonNull(
                comparableTotalPotentialPoints, "comparableTotalPotentialPoints must not be null");
            comparableTotalPointsGap = Objects.requireNonNull(
                comparableTotalPointsGap, "comparableTotalPointsGap must not be null");

            boolean anyPresent = comparableTotalStartedPoints.isPresent()
                || comparableTotalPotentialPoints.isPresent()
                || comparableTotalPointsGap.isPresent();
            boolean allPresent = comparableTotalStartedPoints.isPresent()
                && comparableTotalPotentialPoints.isPresent()
                && comparableTotalPointsGap.isPresent();
            if (comparableCompleteWeeks == 0) {
                if (anyPresent) throw new IllegalArgumentException("zero comparable weeks cannot expose totals");
            } else {
                if (!allPresent) throw new IllegalArgumentException("comparable weeks must expose all totals");
                BigDecimal started = comparableTotalStartedPoints.orElseThrow();
                BigDecimal potential = comparableTotalPotentialPoints.orElseThrow();
                BigDecimal gap = comparableTotalPointsGap.orElseThrow();
                if (gap.compareTo(BigDecimal.ZERO) < 0
                    || potential.subtract(started).compareTo(gap) != 0) {
                    throw new IllegalArgumentException(
                        "comparable season totals must preserve non-negative potential-minus-started gap");
                }
            }
        }
    }

    public record SeasonEvidenceReport(
        String policyId,
        String metricScope,
        String weekUniverse,
        String aggregatePolicy,
        String sourcePotentialSeasonPolicyId,
        String startedLineupPolicyId,
        String pointsGapPolicyId,
        String leagueId,
        String teamId,
        int season,
        List<WeekEvidence> weeks,
        SeasonAggregate aggregate) {

        public SeasonEvidenceReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!METRIC_SCOPE.equals(metricScope)) throw new IllegalArgumentException("unexpected metricScope");
            if (!WEEK_UNIVERSE.equals(weekUniverse)) throw new IllegalArgumentException("unexpected weekUniverse");
            if (!AGGREGATE_POLICY.equals(aggregatePolicy)) {
                throw new IllegalArgumentException("unexpected aggregatePolicy");
            }
            if (!LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.POLICY_ID.equals(sourcePotentialSeasonPolicyId)) {
                throw new IllegalArgumentException("unexpected sourcePotentialSeasonPolicyId");
            }
            if (!LeagueTeamWeekStartedLineupEvidenceAnalyzer.POLICY_ID.equals(startedLineupPolicyId)) {
                throw new IllegalArgumentException("unexpected startedLineupPolicyId");
            }
            if (!LeagueTeamWeekLineupPointsGapEvidenceAnalyzer.POLICY_ID.equals(pointsGapPolicyId)) {
                throw new IllegalArgumentException("unexpected pointsGapPolicyId");
            }
            requireText(leagueId, "leagueId");
            requireText(teamId, "teamId");
            if (season < 1999 || season > 2100) {
                throw new IllegalArgumentException("season must be between 1999 and 2100");
            }
            weeks = List.copyOf(Objects.requireNonNull(weeks, "weeks must not be null"));
            Objects.requireNonNull(aggregate, "aggregate must not be null");
            int previousWeek = 0;
            for (WeekEvidence week : weeks) {
                if (week.week() <= previousWeek) {
                    throw new IllegalArgumentException("weeks must be unique and strictly ascending");
                }
                var source = week.sourcePotentialWeek();
                if (!leagueId.equals(source.coverage().leagueId()) || !teamId.equals(source.coverage().teamId())
                    || season != source.coverage().season()) {
                    throw new IllegalArgumentException("source week identity must match season report");
                }
                if (week.startedLineup() != null
                    && (!leagueId.equals(week.startedLineup().leagueId())
                        || !teamId.equals(week.startedLineup().teamId())
                        || season != week.startedLineup().season())) {
                    throw new IllegalArgumentException("started week identity must match season report");
                }
                if (week.pointsGap() != null
                    && (!leagueId.equals(week.pointsGap().leagueId())
                        || !teamId.equals(week.pointsGap().teamId())
                        || season != week.pointsGap().season())) {
                    throw new IllegalArgumentException("gap week identity must match season report");
                }
                previousWeek = week.week();
            }
            requireAggregateMatchesWeeks(weeks, aggregate);
        }
    }
}
