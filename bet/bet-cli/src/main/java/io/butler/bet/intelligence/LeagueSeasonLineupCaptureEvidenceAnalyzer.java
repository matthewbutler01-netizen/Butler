package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exposes each team's governed season lineup capture evidence in repository team-name order
 * under one governed league-season historical scoring lane, without computing any cross-team
 * aggregate, rank, tier, comparison score, or manager judgment.
 */
public final class LeagueSeasonLineupCaptureEvidenceAnalyzer {
    public static final String POLICY_ID =
        "league-season-lineup-capture-evidence-v2-historical-scoring-lane-team-name-order-no-ranking-no-cross-team-aggregate";
    public static final String PRESENTATION_SCOPE =
        "TEAM_BY_TEAM_LINEUP_CAPTURE_ONLY_REPOSITORY_TEAM_NAME_ORDER_GOVERNED_HISTORICAL_SCORING_LANE_SEPARATE_COVERAGE_DENOMINATORS_NO_CROSS_TEAM_AGGREGATE_OR_RANKING";

    private final Database database;

    public LeagueSeasonLineupCaptureEvidenceAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public LeagueEvidenceReport analyze(String leagueId, int season) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }

        HistoricalScoringLaneSelector.Selection scoringLane =
            new HistoricalScoringLaneSelector(database).select(normalizedLeagueId, season);

        var league = new LeagueRepository(database).findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + normalizedLeagueId));
        var teamAnalyzer = new LeagueTeamSeasonLineupCaptureEvidenceAnalyzer(database);
        List<TeamEvidence> teams = new ArrayList<>();
        for (var team : new TeamRepository(database).findByLeagueId(normalizedLeagueId)) {
            var seasonEvidence = teamAnalyzer.analyze(normalizedLeagueId, team.getId(), season);
            requireSameScoringLane(scoringLane, seasonEvidence);
            teams.add(new TeamEvidence(team.getId(), team.getName(), seasonEvidence));
        }

        return new LeagueEvidenceReport(
            POLICY_ID,
            LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.METRIC_SCOPE,
            LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WEEK_UNIVERSE,
            PRESENTATION_SCOPE,
            scoringLane.policyId(),
            scoringLane.lane(),
            scoringLane.scoringPolicyId(),
            LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.POLICY_ID,
            normalizedLeagueId,
            league.getName(),
            season,
            List.copyOf(teams));
    }

    private static void requireSameScoringLane(
        HistoricalScoringLaneSelector.Selection selected,
        LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.SeasonLineupCaptureReport nested) {
        var source = nested.sourceSeasonPointsGap();
        if (!selected.policyId().equals(source.scoringLaneSelectionPolicyId())
            || selected.lane() != source.scoringLane()
            || !selected.scoringPolicyId().equals(source.scoringPolicyId())) {
            throw new IllegalStateException(
                "League-season lineup capture unavailable: nested team-season evidence moved to a different historical scoring lane");
        }
    }

    private static String expectedScoringPolicy(HistoricalScoringLaneSelector.Lane lane) {
        Objects.requireNonNull(lane, "scoringLane must not be null");
        return lane == HistoricalScoringLaneSelector.Lane.SLEEPER_PROVIDER_NATIVE
            ? HistoricalScoringLaneSelector.PROVIDER_NATIVE_SCORING_POLICY_ID
            : CoveredProductionScoringPolicy.POLICY_ID;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record TeamEvidence(
        String teamId,
        String teamName,
        LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.SeasonLineupCaptureReport seasonEvidence) {
        public TeamEvidence {
            requireText(teamId, "teamId");
            requireText(teamName, "teamName");
            Objects.requireNonNull(seasonEvidence, "seasonEvidence must not be null");
            if (!teamId.equals(seasonEvidence.sourceSeasonPointsGap().teamId())) {
                throw new IllegalArgumentException("teamId must match nested season capture evidence");
            }
        }
    }

    public record LeagueEvidenceReport(
        String policyId,
        String metricScope,
        String weekUniverse,
        String presentationScope,
        String scoringLaneSelectionPolicyId,
        HistoricalScoringLaneSelector.Lane scoringLane,
        String scoringPolicyId,
        String teamSeasonPolicyId,
        String leagueId,
        String leagueName,
        int season,
        List<TeamEvidence> teams) {
        public LeagueEvidenceReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.METRIC_SCOPE.equals(metricScope)) {
                throw new IllegalArgumentException("unexpected metricScope");
            }
            if (!LeagueTeamSeasonLineupPointsGapEvidenceAnalyzer.WEEK_UNIVERSE.equals(weekUniverse)) {
                throw new IllegalArgumentException("unexpected weekUniverse");
            }
            if (!PRESENTATION_SCOPE.equals(presentationScope)) {
                throw new IllegalArgumentException("unexpected presentationScope");
            }
            if (!HistoricalScoringLaneSelector.POLICY_ID.equals(scoringLaneSelectionPolicyId)) {
                throw new IllegalArgumentException("unexpected scoringLaneSelectionPolicyId");
            }
            Objects.requireNonNull(scoringLane, "scoringLane must not be null");
            if (!expectedScoringPolicy(scoringLane).equals(scoringPolicyId)) {
                throw new IllegalArgumentException("scoring policy does not match selected league-season lane");
            }
            if (!LeagueTeamSeasonLineupCaptureEvidenceAnalyzer.POLICY_ID.equals(teamSeasonPolicyId)) {
                throw new IllegalArgumentException("unexpected teamSeasonPolicyId");
            }
            requireText(leagueId, "leagueId");
            requireText(leagueName, "leagueName");
            if (season < 1999 || season > 2100) {
                throw new IllegalArgumentException("season must be between 1999 and 2100");
            }
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
            String previousTeamName = null;
            for (TeamEvidence team : teams) {
                var source = team.seasonEvidence().sourceSeasonPointsGap();
                if (!leagueId.equals(source.leagueId()) || season != source.season()) {
                    throw new IllegalArgumentException("nested team capture evidence must match league and season");
                }
                if (!scoringLaneSelectionPolicyId.equals(source.scoringLaneSelectionPolicyId())
                    || scoringLane != source.scoringLane()
                    || !scoringPolicyId.equals(source.scoringPolicyId())) {
                    throw new IllegalArgumentException(
                        "nested team capture evidence must match selected league-season scoring lane");
                }
                if (previousTeamName != null && previousTeamName.compareTo(team.teamName()) > 0) {
                    throw new IllegalArgumentException("teams must preserve repository team-name order");
                }
                previousTeamName = team.teamName();
            }
        }

        /** Source-compatible nflverse constructor retained for callers that built the v1 report directly. */
        public LeagueEvidenceReport(
            String policyId,
            String metricScope,
            String weekUniverse,
            String presentationScope,
            String teamSeasonPolicyId,
            String leagueId,
            String leagueName,
            int season,
            List<TeamEvidence> teams) {
            this(
                policyId,
                metricScope,
                weekUniverse,
                presentationScope,
                HistoricalScoringLaneSelector.POLICY_ID,
                HistoricalScoringLaneSelector.Lane.NFLVERSE_EXACT,
                CoveredProductionScoringPolicy.POLICY_ID,
                teamSeasonPolicyId,
                leagueId,
                leagueName,
                season,
                teams);
        }
    }
}
