package io.butler.bet.intelligence;

import io.butler.bet.data.Database;
import io.butler.bet.data.PlayerValueRepository;
import io.butler.bet.data.RosterRepository;
import io.butler.bet.domain.PlayerValue;
import io.butler.bet.domain.Roster;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TeamStrengthAnalyzer {
    private final LeagueAnalyzer leagueAnalyzer;
    private final RosterRepository rosters;
    private final PlayerValueRepository playerValues;

    public TeamStrengthAnalyzer(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.leagueAnalyzer = new LeagueAnalyzer(database);
        this.rosters = new RosterRepository(database);
        this.playerValues = new PlayerValueRepository(database);
    }

    public StrengthReport rank(String leagueId, String source) throws SQLException {
        return rank(leagueId, source, null);
    }

    public StrengthReport rank(String leagueId, String source, LocalDate minimumAsOfDate) throws SQLException {
        String valueSource = requireText(source, "source");
        LeagueAnalyzer.LeagueReport league = leagueAnalyzer.analyze(requireText(leagueId, "leagueId"));
        List<TeamStrength> strengths = new ArrayList<>();
        int totalValuedPlayers = 0;
        int totalMissingValues = 0;
        LocalDate oldestValueDate = null;
        LocalDate latestValueDate = null;

        for (LeagueAnalyzer.TeamReport team : league.teams()) {
            ValueSummary values = playerValue(team.teamId(), valueSource);
            totalValuedPlayers += values.valuedPlayers();
            totalMissingValues += values.missingValues();
            oldestValueDate = earlier(oldestValueDate, values.oldestValueDate());
            latestValueDate = later(latestValueDate, values.latestValueDate());
            double compositionScore = score(team.positionCounts(), team.slotCounts());
            strengths.add(new TeamStrength(0, team.teamId(), team.teamName(), values.total(), compositionScore,
                values.valuedPlayers(), values.missingValues(), Map.copyOf(team.positionCounts()), Map.copyOf(team.slotCounts())));
        }

        if (!strengths.isEmpty() && totalValuedPlayers == 0) {
            throw new IllegalArgumentException("no player values found for source: " + valueSource);
        }
        if (minimumAsOfDate != null && oldestValueDate != null && oldestValueDate.isBefore(minimumAsOfDate)) {
            throw new IllegalArgumentException("player values for source " + valueSource
                + " are older than minimum as-of date " + minimumAsOfDate + ": oldest=" + oldestValueDate);
        }

        strengths.sort(Comparator.comparingDouble(TeamStrength::playerValue).reversed()
            .thenComparing(Comparator.comparingDouble(TeamStrength::compositionScore).reversed())
            .thenComparing(TeamStrength::teamName, String.CASE_INSENSITIVE_ORDER));

        List<TeamStrength> ranked = new ArrayList<>();
        for (int i = 0; i < strengths.size(); i++) {
            TeamStrength team = strengths.get(i);
            ranked.add(new TeamStrength(i + 1, team.teamId(), team.teamName(), team.playerValue(), team.compositionScore(),
                team.valuedPlayers(), team.missingValues(), team.positionCounts(), team.slotCounts()));
        }
        return new StrengthReport(leagueId.trim(), valueSource, totalValuedPlayers, totalMissingValues,
            oldestValueDate, latestValueDate, List.copyOf(ranked));
    }

    private ValueSummary playerValue(String teamId, String source) throws SQLException {
        double total = 0;
        int valuedPlayers = 0;
        int missingValues = 0;
        LocalDate oldestValueDate = null;
        LocalDate latestValueDate = null;
        for (Roster roster : rosters.findByTeamId(teamId)) {
            PlayerValue value = playerValues.findLatestByPlayerIdAndSource(roster.getPlayerId(), source).orElse(null);
            if (value == null) {
                missingValues++;
                continue;
            }
            total += value.getValue();
            valuedPlayers++;
            oldestValueDate = earlier(oldestValueDate, value.getAsOfDate());
            latestValueDate = later(latestValueDate, value.getAsOfDate());
        }
        return new ValueSummary(total, valuedPlayers, missingValues, oldestValueDate, latestValueDate);
    }

    private static LocalDate earlier(LocalDate current, LocalDate candidate) {
        if (candidate == null) return current;
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    private static LocalDate later(LocalDate current, LocalDate candidate) {
        if (candidate == null) return current;
        return current == null || candidate.isAfter(current) ? candidate : current;
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private record ValueSummary(double total, int valuedPlayers, int missingValues,
                                LocalDate oldestValueDate, LocalDate latestValueDate) {}
    public record StrengthReport(String leagueId, String source, int valuedPlayers, int missingValues,
                                 LocalDate oldestValueDate, LocalDate latestValueDate, List<TeamStrength> teams) {
        public int totalPlayers() { return valuedPlayers + missingValues; }
        public double coveragePercent() {
            return totalPlayers() == 0 ? 0.0 : valuedPlayers * 100.0 / totalPlayers();
        }
    }
    public record TeamStrength(int rank, String teamId, String teamName, double playerValue, double compositionScore,
                               int valuedPlayers, int missingValues, Map<String, Integer> positionCounts,
                               Map<String, Integer> slotCounts) {
        public double score() { return playerValue; }
    }
}
