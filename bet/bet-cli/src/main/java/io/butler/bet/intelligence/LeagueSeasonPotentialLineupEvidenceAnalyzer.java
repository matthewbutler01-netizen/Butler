package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.LeagueRepository;
import io.butler.bet.data.TeamRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exposes each team's governed season potential-lineup evidence in repository team-name order
 * without computing any cross-team aggregate, rank, tier, or comparison score.
 */
public final class LeagueSeasonPotentialLineupEvidenceAnalyzer {
    public static final String POLICY_ID =
        "league-season-potential-lineup-evidence-v1-team-name-order-no-ranking-no-cross-team-aggregate";

    private final Database database;

    public LeagueSeasonPotentialLineupEvidenceAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public LeagueEvidenceReport analyze(String leagueId, int season) throws SQLException {
        String normalizedLeagueId = requireText(leagueId, "leagueId");
        if (season < 1999 || season > 2100) {
            throw new IllegalArgumentException("season must be between 1999 and 2100");
        }

        var league = new LeagueRepository(database).findById(normalizedLeagueId)
            .orElseThrow(() -> new IllegalArgumentException("League not found: " + normalizedLeagueId));
        var teamAnalyzer = new LeagueTeamSeasonPotentialLineupEvidenceAnalyzer(database);
        List<TeamEvidence> teams = new ArrayList<>();
        for (var team : new TeamRepository(database).findByLeagueId(normalizedLeagueId)) {
            teams.add(new TeamEvidence(
                team.getId(),
                team.getName(),
                teamAnalyzer.analyze(normalizedLeagueId, team.getId(), season)));
        }

        return new LeagueEvidenceReport(
            POLICY_ID,
            LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE,
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WEEK_UNIVERSE,
            LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.POLICY_ID,
            normalizedLeagueId,
            league.getName(),
            season,
            List.copyOf(teams));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record TeamEvidence(
        String teamId,
        String teamName,
        LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.SeasonEvidenceReport seasonEvidence) {
        public TeamEvidence {
            requireText(teamId, "teamId");
            requireText(teamName, "teamName");
            Objects.requireNonNull(seasonEvidence, "seasonEvidence must not be null");
            if (!teamId.equals(seasonEvidence.teamId())) {
                throw new IllegalArgumentException("teamId must match nested season evidence");
            }
        }
    }

    public record LeagueEvidenceReport(
        String policyId,
        String metricScope,
        String weekUniverse,
        String teamSeasonPolicyId,
        String leagueId,
        String leagueName,
        int season,
        List<TeamEvidence> teams) {
        public LeagueEvidenceReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            if (!LeagueTeamWeekPotentialLineupCoverageAnalyzer.METRIC_SCOPE.equals(metricScope)) {
                throw new IllegalArgumentException("unexpected metricScope");
            }
            if (!LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.WEEK_UNIVERSE.equals(weekUniverse)) {
                throw new IllegalArgumentException("unexpected weekUniverse");
            }
            if (!LeagueTeamSeasonPotentialLineupEvidenceAnalyzer.POLICY_ID.equals(teamSeasonPolicyId)) {
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
                if (!leagueId.equals(team.seasonEvidence().leagueId()) || season != team.seasonEvidence().season()) {
                    throw new IllegalArgumentException("nested team evidence must match league and season");
                }
                if (previousTeamName != null && previousTeamName.compareTo(team.teamName()) > 0) {
                    throw new IllegalArgumentException("teams must preserve repository team-name order");
                }
                previousTeamName = team.teamName();
            }
        }
    }
}
