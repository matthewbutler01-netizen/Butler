package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.TeamRepository;
import io.butler.bet.data.TeamSeasonPerformanceRepository;
import io.butler.bet.domain.TeamSeasonPerformance;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Neutral observed competitive-performance evidence. No contender/rebuilder classification. */
public final class LeaguePerformanceEvidenceAnalyzer {
    public static final String DEFAULT_SOURCE = "sleeper";

    private final TeamRepository teams;
    private final TeamSeasonPerformanceRepository performance;

    public LeaguePerformanceEvidenceAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.teams = new TeamRepository(database);
        this.performance = new TeamSeasonPerformanceRepository(database);
    }

    public PerformanceReport analyze(String leagueId, int season) throws SQLException {
        return analyze(leagueId, season, DEFAULT_SOURCE);
    }

    public PerformanceReport analyze(String leagueId, int season, String source) throws SQLException {
        requireText(leagueId, "leagueId");
        requireText(source, "source");
        if (season < 1999 || season > 2100) throw new IllegalArgumentException("season must be between 1999 and 2100");

        var leagueTeams = teams.findByLeagueId(leagueId.trim());
        if (leagueTeams.isEmpty()) throw new IllegalArgumentException("league has no teams: " + leagueId);
        Map<String, TeamSeasonPerformance> byTeam = new HashMap<>();
        for (var snapshot : performance.findLatestByLeague(leagueId.trim(), season, source.trim())) {
            TeamSeasonPerformance previous = byTeam.putIfAbsent(snapshot.teamId(), snapshot);
            if (previous != null) throw new IllegalStateException("duplicate latest performance snapshot for team: " + snapshot.teamId());
        }

        List<TeamPerformanceEvidence> result = new ArrayList<>();
        for (var team : leagueTeams) {
            var snapshot = byTeam.get(team.getId());
            if (snapshot == null) {
                result.add(new TeamPerformanceEvidence(team.getId(), team.getName(), null));
            } else {
                if (!leagueId.trim().equals(snapshot.leagueId()) || snapshot.season() != season || !source.trim().equals(snapshot.source())) {
                    throw new IllegalStateException("team performance evidence identity mismatch: " + team.getId());
                }
                result.add(new TeamPerformanceEvidence(team.getId(), team.getName(), snapshot));
            }
        }
        result.sort(Comparator.comparing(TeamPerformanceEvidence::teamName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(TeamPerformanceEvidence::teamId));
        return new PerformanceReport(leagueId.trim(), season, source.trim(), List.copyOf(result));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    public record TeamPerformanceEvidence(String teamId, String teamName, TeamSeasonPerformance performance) {
        public boolean available() { return performance != null; }
    }

    public record PerformanceReport(String leagueId, int season, String source, List<TeamPerformanceEvidence> teams) {
        public PerformanceReport {
            teams = List.copyOf(Objects.requireNonNull(teams, "teams must not be null"));
        }
        public int coveredTeams() { return (int) teams.stream().filter(TeamPerformanceEvidence::available).count(); }
        public int missingTeams() { return teams.size() - coveredTeams(); }
        public double coveragePercent() { return teams.isEmpty() ? 0.0 : coveredTeams() * 100.0 / teams.size(); }
        public boolean complete() { return missingTeams() == 0; }
    }
}
