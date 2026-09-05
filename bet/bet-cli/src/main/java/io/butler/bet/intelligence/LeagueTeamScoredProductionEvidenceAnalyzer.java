package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregates observed rostered-player scoring evidence by team without ranking teams or treating
 * full-roster historical production as lineup strength.
 */
public final class LeagueTeamScoredProductionEvidenceAnalyzer {
    public static final String POLICY_ID =
        "league-team-scored-production-evidence-v1-full-roster-observed-no-ranking";

    private final Database database;

    public LeagueTeamScoredProductionEvidenceAnalyzer(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public TeamEvidenceReport analyze(String leagueId, int season, String source) throws SQLException {
        var playerReport = new LeagueScoredProductionEvidenceAnalyzer(database).analyze(leagueId, season, source);
        Map<String, MutableTeamEvidence> byTeam = new LinkedHashMap<>();
        for (var player : playerReport.players()) {
            var team = byTeam.computeIfAbsent(player.teamId(), ignored ->
                new MutableTeamEvidence(player.teamId(), player.teamName()));
            team.totalPlayers++;
            if (player.available()) {
                team.coveredPlayers++;
                team.observedFantasyPoints = team.observedFantasyPoints.add(player.fantasyPoints());
            }
        }

        List<TeamEvidence> teams = new ArrayList<>();
        for (var team : byTeam.values()) {
            teams.add(new TeamEvidence(
                team.teamId,
                team.teamName,
                team.totalPlayers,
                team.coveredPlayers,
                team.observedFantasyPoints));
        }
        return new TeamEvidenceReport(
            POLICY_ID,
            playerReport.policyId(),
            playerReport.coveragePolicyId(),
            playerReport.scoringPolicyId(),
            playerReport.leagueId(),
            playerReport.leagueName(),
            playerReport.season(),
            playerReport.source(),
            List.copyOf(teams));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static final class MutableTeamEvidence {
        private final String teamId;
        private final String teamName;
        private int totalPlayers;
        private int coveredPlayers;
        private BigDecimal observedFantasyPoints = BigDecimal.ZERO;

        private MutableTeamEvidence(String teamId, String teamName) {
            this.teamId = teamId;
            this.teamName = teamName;
        }
    }

    public record TeamEvidence(
        String teamId,
        String teamName,
        int totalPlayers,
        int coveredPlayers,
        BigDecimal observedFantasyPoints) {
        public TeamEvidence {
            requireText(teamId, "teamId");
            requireText(teamName, "teamName");
            if (totalPlayers < 0) throw new IllegalArgumentException("totalPlayers must not be negative");
            if (coveredPlayers < 0 || coveredPlayers > totalPlayers) {
                throw new IllegalArgumentException("coveredPlayers must be within totalPlayers");
            }
            Objects.requireNonNull(observedFantasyPoints, "observedFantasyPoints must not be null");
        }

        public boolean complete() {
            return coveredPlayers == totalPlayers;
        }

        public double coveragePercent() {
            return totalPlayers == 0 ? 100.0 : coveredPlayers * 100.0 / totalPlayers;
        }
    }

    public record TeamEvidenceReport(
        String policyId,
        String playerEvidencePolicyId,
        String coveragePolicyId,
        String scoringPolicyId,
        String leagueId,
        String leagueName,
        int season,
        String source,
        List<TeamEvidence> teams) {
        public TeamEvidenceReport {
            if (!POLICY_ID.equals(policyId)) throw new IllegalArgumentException("unexpected policyId");
            requireText(playerEvidencePolicyId, "playerEvidencePolicyId");
            requireText(coveragePolicyId, "coveragePolicyId");
            requireText(scoringPolicyId, "scoringPolicyId");
            requireText(leagueId, "leagueId");
            requireText(leagueName, "leagueName");
            if (season <= 0) throw new IllegalArgumentException("season must be positive");
            requireText(source, "source");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }
    }
}
