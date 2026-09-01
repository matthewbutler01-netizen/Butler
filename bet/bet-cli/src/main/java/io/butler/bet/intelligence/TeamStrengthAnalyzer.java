package io.butler.bet.intelligence;

import io.butler.bet.data.Database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TeamStrengthAnalyzer {
    private final LeagueAnalyzer leagueAnalyzer;

    public TeamStrengthAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagueAnalyzer = new LeagueAnalyzer(database);
    }

    public StrengthReport rank(String leagueId) throws SQLException {
        LeagueAnalyzer.LeagueReport league = leagueAnalyzer.analyze(leagueId);
        List<TeamStrength> strengths = new ArrayList<>();

        for (LeagueAnalyzer.TeamReport team : league.teams()) {
            double score = score(team.positionCounts(), team.slotCounts());
            strengths.add(new TeamStrength(0, team.teamId(), team.teamName(), score,
                Map.copyOf(team.positionCounts()), Map.copyOf(team.slotCounts())));
        }

        strengths.sort(Comparator.comparingDouble(TeamStrength::score).reversed()
            .thenComparing(TeamStrength::teamName, String.CASE_INSENSITIVE_ORDER));

        List<TeamStrength> ranked = new ArrayList<>();
        for (int i = 0; i < strengths.size(); i++) {
            TeamStrength team = strengths.get(i);
            ranked.add(new TeamStrength(i + 1, team.teamId(), team.teamName(), team.score(), team.positionCounts(), team.slotCounts()));
        }
        return new StrengthReport(leagueId, List.copyOf(ranked));
    }

    static double score(Map<String, Integer> positions, Map<String, Integer> slots) {
        double score = 0;
        score += positions.getOrDefault("QB", 0) * 3.0;
        score += positions.getOrDefault("RB", 0) * 2.0;
        score += positions.getOrDefault("WR", 0) * 2.0;
        score += positions.getOrDefault("TE", 0) * 2.0;
        score += positions.getOrDefault("K", 0) * 0.5;
        score += positions.getOrDefault("DEF", 0) * 0.5;
        score += slots.getOrDefault("STARTER", 0) * 1.0;
        score += slots.getOrDefault("BENCH", 0) * 0.25;
        score += slots.getOrDefault("RESERVE", 0) * 0.10;
        score += slots.getOrDefault("TAXI", 0) * 0.10;
        return score;
    }

    public record StrengthReport(String leagueId, List<TeamStrength> teams) {}
    public record TeamStrength(int rank, String teamId, String teamName, double score,
                               Map<String, Integer> positionCounts, Map<String, Integer> slotCounts) {}
}
