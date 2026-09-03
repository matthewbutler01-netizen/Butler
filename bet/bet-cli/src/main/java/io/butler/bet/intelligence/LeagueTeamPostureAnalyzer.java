package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Composes governed competitive and roster-strength tiers into team posture. */
public final class LeagueTeamPostureAnalyzer {
    private final LeaguePerformanceEvidenceAnalyzer performance;
    private final LeagueCompetitiveTierAnalyzer competitive;
    private final LeagueRosterStrengthTierAnalyzer rosterStrength;

    public LeagueTeamPostureAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.performance = new LeaguePerformanceEvidenceAnalyzer(database);
        this.competitive = new LeagueCompetitiveTierAnalyzer();
        this.rosterStrength = new LeagueRosterStrengthTierAnalyzer(database);
    }

    public PostureReport analyze(String leagueId, int season) throws SQLException {
        var competitiveReport = competitive.analyze(performance.analyze(leagueId, season));
        var rosterReport = rosterStrength.analyze(leagueId);
        return compose(competitiveReport, rosterReport);
    }

    public PostureReport analyze(String leagueId, int season, String rosterValueSource) throws SQLException {
        var competitiveReport = competitive.analyze(performance.analyze(leagueId, season));
        var rosterReport = rosterStrength.analyze(leagueId, rosterValueSource);
        return compose(competitiveReport, rosterReport);
    }

    public PostureReport analyze(String leagueId, int season, LocalDate minimumAsOfDate) throws SQLException {
        Objects.requireNonNull(minimumAsOfDate, "minimumAsOfDate must not be null");
        var competitiveReport = competitive.analyze(performance.analyze(leagueId, season));
        var rosterReport = rosterStrength.analyze(leagueId, minimumAsOfDate);
        return compose(competitiveReport, rosterReport);
    }

    public PostureReport analyze(String leagueId, int season, String rosterValueSource,
                                 LocalDate minimumAsOfDate) throws SQLException {
        Objects.requireNonNull(minimumAsOfDate, "minimumAsOfDate must not be null");
        var competitiveReport = competitive.analyze(performance.analyze(leagueId, season));
        var rosterReport = rosterStrength.analyze(leagueId, rosterValueSource, minimumAsOfDate);
        return compose(competitiveReport, rosterReport);
    }

    public static PostureReport compose(LeagueCompetitiveTierAnalyzer.CompetitiveTierReport competitiveReport,
                                        LeagueRosterStrengthTierAnalyzer.RosterStrengthReport rosterReport) {
        Objects.requireNonNull(competitiveReport, "competitiveReport must not be null");
        Objects.requireNonNull(rosterReport, "rosterReport must not be null");
        if (!competitiveReport.leagueId().equals(rosterReport.leagueId())) {
            throw new IllegalStateException("posture evidence league mismatch");
        }

        Map<String, LeagueRosterStrengthTierAnalyzer.TeamRosterStrength> rosterByTeam = new HashMap<>();
        for (var team : rosterReport.teams()) {
            if (rosterByTeam.put(team.teamId(), team) != null) {
                throw new IllegalStateException("duplicate roster-strength team: " + team.teamId());
            }
        }
        if (competitiveReport.teams().size() != rosterByTeam.size()) {
            throw new IllegalStateException("posture evidence team-set mismatch");
        }

        List<TeamPosture> teams = new ArrayList<>();
        for (var competitiveTeam : competitiveReport.teams()) {
            var rosterTeam = rosterByTeam.remove(competitiveTeam.teamId());
            if (rosterTeam == null) throw new IllegalStateException("missing roster-strength team: " + competitiveTeam.teamId());
            if (!competitiveTeam.teamName().equals(rosterTeam.teamName())) {
                throw new IllegalStateException("posture evidence team-name mismatch: " + competitiveTeam.teamId());
            }
            teams.add(new TeamPosture(competitiveTeam.teamId(), competitiveTeam.teamName(), competitiveTeam.tier(),
                rosterTeam.tier(), TeamPosturePolicy.classify(competitiveTeam.tier(), rosterTeam.tier())));
        }
        if (!rosterByTeam.isEmpty()) throw new IllegalStateException("unexpected roster-strength teams remain");

        teams.sort(java.util.Comparator.comparing(TeamPosture::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamPosture::teamId));
        return new PostureReport(competitiveReport.leagueId(), competitiveReport.season(), competitiveReport.source(),
            rosterReport.source(), TeamPosturePolicy.POLICY_ID, competitiveReport.policyId(), rosterReport.policyId(),
            competitiveReport.available() && rosterReport.available(), List.copyOf(teams));
    }

    public record TeamPosture(String teamId, String teamName,
                              LeagueCompetitiveTierPolicy.Tier competitiveTier,
                              LeagueRosterStrengthTierPolicy.Tier rosterTier,
                              TeamPosturePolicy.Posture posture) {
        public TeamPosture {
            Objects.requireNonNull(teamId, "teamId must not be null");
            Objects.requireNonNull(teamName, "teamName must not be null");
            Objects.requireNonNull(competitiveTier, "competitiveTier must not be null");
            Objects.requireNonNull(rosterTier, "rosterTier must not be null");
            Objects.requireNonNull(posture, "posture must not be null");
        }
    }

    public record PostureReport(String leagueId, int season, String performanceSource, String rosterValueSource,
                                String posturePolicyId, String competitivePolicyId, String rosterPolicyId,
                                boolean available, List<TeamPosture> teams) {
        public PostureReport {
            Objects.requireNonNull(leagueId, "leagueId must not be null");
            Objects.requireNonNull(performanceSource, "performanceSource must not be null");
            Objects.requireNonNull(rosterValueSource, "rosterValueSource must not be null");
            Objects.requireNonNull(posturePolicyId, "posturePolicyId must not be null");
            Objects.requireNonNull(competitivePolicyId, "competitivePolicyId must not be null");
            Objects.requireNonNull(rosterPolicyId, "rosterPolicyId must not be null");
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }
    }
}
